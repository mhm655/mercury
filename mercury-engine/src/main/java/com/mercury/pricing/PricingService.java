package com.mercury.pricing;

import com.mercury.core.MercuryException;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.marketdata.MarketDataSnapshot;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Routes an instrument to a model that can price it.
 *
 * <h2>The Expression Problem, and why this shape</h2>
 * Two requirements pull in opposite directions: add a new <em>instrument</em> without editing
 * existing code, and add a new <em>model</em> for an existing instrument without editing it
 * either. That tension is the Expression Problem, and the choice of dispatch is the whole
 * answer.
 *
 * <table border="1">
 *   <caption>Dispatch strategies</caption>
 *   <tr><th>Approach</th><th>New instrument</th><th>New model</th></tr>
 *   <tr><td>{@code instrument.price(..)}</td><td>fine</td>
 *       <td><b>impossible</b> - one method, one model</td></tr>
 *   <tr><td>{@code instanceof} chain</td><td><b>edit the chain</b></td><td>edit the chain</td></tr>
 *   <tr><td>Visitor</td><td><b>edit the interface and every visitor</b></td><td>fine</td></tr>
 *   <tr><td><b>Type-keyed registry (this)</b></td><td>register one more</td><td>register one more</td></tr>
 * </table>
 *
 * <p>Visitor is the interesting rejection: it is the textbook answer to polymorphic dispatch
 * without {@code instanceof}, and it fails the requirement that matters most here. Adding an
 * instrument would mean a new method on the visitor interface and a change to every existing
 * implementation - the open-closed violation, relocated rather than removed.
 *
 * <p>Adding a sixth instrument to this design costs: one instrument class, one model class,
 * one registration line. Nothing existing is touched. M15 proves that in a single commit.
 *
 * <h2>The unchecked cast</h2>
 * Generics cannot express "a map whose value type depends on its key", so retrieving a
 * {@code PricingModel<T>} keyed by {@code Class<T>} needs one unchecked cast. It is confined
 * to {@link #modelFor}, and it is safe because {@link #register} rejects any model whose
 * {@link PricingModel#instrumentType()} disagrees with what it is keyed under. Saying so
 * plainly is more credible than hiding a cast behind a wrapper that provides no more safety.
 *
 * <p>Instances are immutable once built and safe to share across threads.
 */
public final class PricingService {

    private final Map<Class<?>, Map<ModelName, PricingModel<?>>> modelsByType;
    private final Map<Class<?>, ModelName> defaultModels;

    private PricingService(Map<Class<?>, Map<ModelName, PricingModel<?>>> modelsByType,
                           Map<Class<?>, ModelName> defaultModels) {
        this.modelsByType = modelsByType;
        this.defaultModels = defaultModels;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Prices {@code instrument} with its default model.
     *
     * <p>Note the parameter is the base interface: callers dispatch polymorphically and never
     * mention a concrete instrument type.
     *
     * @throws NoPricingModelException if nothing is registered for this instrument's type
     */
    public ValuationResult price(FinancialInstrument instrument,
                                 MarketDataSnapshot market, LocalDate asOf) {
        Objects.requireNonNull(instrument, "instrument");
        ModelName defaultModel = defaultModels.get(instrument.getClass());
        if (defaultModel == null) {
            throw new NoPricingModelException(instrument, modelsByType.keySet());
        }
        return price(instrument, defaultModel, market, asOf);
    }

    /**
     * Prices {@code instrument} with a named model - the seam that makes cross-model
     * validation possible.
     *
     * @throws NoPricingModelException if that model is not registered for this type
     */
    public ValuationResult price(FinancialInstrument instrument, ModelName modelName,
                                 MarketDataSnapshot market, LocalDate asOf) {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(modelName, "modelName");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(asOf, "asOf");

        return modelFor(instrument, modelName).price(instrument, market, asOf);
    }

    /**
     * The single place an unchecked cast happens.
     *
     * <p>Sound because registration guarantees the model stored under {@code type} declares
     * that same {@code instrumentType()}, and the lookup is keyed by the instrument's own
     * runtime class.
     */
    @SuppressWarnings("unchecked")
    private PricingModel<FinancialInstrument> modelFor(FinancialInstrument instrument,
                                                       ModelName modelName) {
        Map<ModelName, PricingModel<?>> models = modelsByType.get(instrument.getClass());
        if (models == null) {
            throw new NoPricingModelException(instrument, modelsByType.keySet());
        }
        PricingModel<?> model = models.get(modelName);
        if (model == null) {
            throw new NoPricingModelException(instrument, modelName, models.keySet());
        }
        return (PricingModel<FinancialInstrument>) model;
    }

    /** Model names registered for an instrument type, for diagnostics and cross-checking. */
    public List<ModelName> modelsFor(Class<? extends FinancialInstrument> instrumentType) {
        Map<ModelName, PricingModel<?>> models = modelsByType.get(instrumentType);
        return models == null ? List.of() : List.copyOf(models.keySet());
    }

    /** True if this service can price the given instrument type. */
    public boolean canPrice(Class<? extends FinancialInstrument> instrumentType) {
        return modelsByType.containsKey(instrumentType);
    }

    @Override
    public String toString() {
        return "PricingService(" + modelsByType.size() + " instrument types)";
    }

    /**
     * Collects models, then freezes them.
     *
     * <p>The first model registered for a type becomes its default; {@link #withDefault} can
     * override that. Making the default explicit rather than "whichever was registered last"
     * keeps wiring order from silently changing valuations.
     */
    public static final class Builder {

        private final Map<Class<?>, Map<ModelName, PricingModel<?>>> modelsByType =
                new LinkedHashMap<>();
        private final Map<Class<?>, ModelName> defaultModels = new HashMap<>();

        private Builder() {
        }

        /**
         * Registers a model. This is the one line adding a new instrument costs.
         *
         * @throws IllegalArgumentException if the model's declared instrument type is null, or
         *                                  the same name is registered twice for one type
         */
        public <T extends FinancialInstrument> Builder register(PricingModel<T> model) {
            Objects.requireNonNull(model, "model");
            Class<T> type = Objects.requireNonNull(model.instrumentType(),
                    "model.instrumentType()");
            ModelName name = Objects.requireNonNull(model.name(), "model.name()");

            Map<ModelName, PricingModel<?>> models =
                    modelsByType.computeIfAbsent(type, key -> new LinkedHashMap<>());
            PricingModel<?> existing = models.putIfAbsent(name, model);
            if (existing != null) {
                throw new IllegalArgumentException(
                        "A model named " + name + " is already registered for "
                                + type.getSimpleName() + ". Two models pricing the same "
                                + "instrument must have distinct names, since the name is how a "
                                + "caller chooses between them.");
            }
            defaultModels.putIfAbsent(type, name);
            return this;
        }

        /** Chooses which registered model is used when a caller does not name one. */
        public Builder withDefault(Class<? extends FinancialInstrument> instrumentType,
                                   ModelName modelName) {
            Objects.requireNonNull(instrumentType, "instrumentType");
            Objects.requireNonNull(modelName, "modelName");

            Map<ModelName, PricingModel<?>> models = modelsByType.get(instrumentType);
            if (models == null || !models.containsKey(modelName)) {
                throw new IllegalArgumentException(
                        "Cannot default " + instrumentType.getSimpleName() + " to " + modelName
                                + " because that model is not registered for it");
            }
            defaultModels.put(instrumentType, modelName);
            return this;
        }

        public PricingService build() {
            Map<Class<?>, Map<ModelName, PricingModel<?>>> frozen = new LinkedHashMap<>();
            modelsByType.forEach((type, models) -> frozen.put(type, Map.copyOf(models)));
            return new PricingService(Map.copyOf(frozen), Map.copyOf(defaultModels));
        }
    }

    /** Raised when no registered model can price an instrument. */
    public static final class NoPricingModelException extends MercuryException {

        NoPricingModelException(FinancialInstrument instrument, java.util.Set<Class<?>> known) {
            super("No pricing model registered for " + instrument.getClass().getSimpleName()
                    + " (" + instrument.id() + "). Registered types: "
                    + known.stream().map(Class::getSimpleName).sorted().toList()
                    + ". Adding one costs a model class and a single register(..) call.");
        }

        NoPricingModelException(FinancialInstrument instrument, ModelName requested,
                                java.util.Set<ModelName> available) {
            super("No model named " + requested + " for "
                    + instrument.getClass().getSimpleName() + " (" + instrument.id()
                    + "). Available: " + available.stream().map(ModelName::value).sorted().toList());
        }
    }
}
