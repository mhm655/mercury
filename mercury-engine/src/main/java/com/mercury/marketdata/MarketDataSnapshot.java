package com.mercury.marketdata;

import com.mercury.core.MercuryException;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.curve.Interpolation;
import com.mercury.curve.YieldCurve;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable set of market observations - the entire market as of one moment.
 *
 * <h2>Immutability is load-bearing</h2>
 * This type is not immutable for tidiness. Three of the project's headline capabilities
 * depend on it:
 *
 * <ul>
 *   <li><b>Reproducibility.</b> Pricing is a pure function of instrument, snapshot and
 *       valuation date. If a snapshot could change under a pricer, revaluing the same
 *       position twice could give two answers and the golden-master test would be
 *       impossible.</li>
 *   <li><b>Stress testing and Greeks.</b> Both work by producing a <em>modified</em> market
 *       and revaluing. {@link #withShock} returns a new snapshot rather than mutating this
 *       one, so the base case is never disturbed and shocks compose without ordering
 *       hazards.</li>
 *   <li><b>Parallel Monte Carlo.</b> Thousands of worker tasks read snapshots concurrently.
 *       Because nothing can write to one, there is no synchronisation on the hot path at
 *       all - not a lock held briefly, none.</li>
 * </ul>
 *
 * <h2>It has a date, because a snapshot is a moment</h2>
 * The valuation date is part of the snapshot rather than a separate argument threaded
 * alongside it. That was not true before M5b, and the omission only became untenable when
 * curves arrived: a discount curve is a set of dated pillars measured from a reference date,
 * so a market holding curve pillars but no date of its own would have had to be told, at
 * every read, which day it was describing.
 *
 * <p>Holding it here also removes a class of error that had no way to be caught: a market
 * built for one date and used to value a portfolio on another produced a plausible number and
 * no complaint. {@code PortfolioValuationService} now checks.
 *
 * <h2>Missing data is an error, not a zero</h2>
 * Asking for a value that is not present throws {@link MissingMarketDataException} rather
 * than defaulting. A missing spot price silently read as zero would price an option at its
 * discounted strike and quietly report a plausible, wrong number - the worst failure mode
 * available. Loud beats plausible.
 */
public final class MarketDataSnapshot {

    private final LocalDate valuationDate;
    private final Interpolation curveInterpolation;
    private final Map<MarketDataKey, Double> values;

    private MarketDataSnapshot(LocalDate valuationDate, Interpolation curveInterpolation,
                               Map<MarketDataKey, Double> values) {
        this.valuationDate = valuationDate;
        this.curveInterpolation = curveInterpolation;
        this.values = values;
    }

    public static Builder builder(LocalDate valuationDate) {
        return new Builder(valuationDate);
    }

    /** An empty market. Useful only in tests asserting that missing data is rejected. */
    public static MarketDataSnapshot empty(LocalDate valuationDate) {
        return new MarketDataSnapshot(Objects.requireNonNull(valuationDate, "valuationDate"),
                Interpolation.LOG_LINEAR_DISCOUNT, Map.of());
    }

    /** The moment this market describes. Every curve in it is referenced here. */
    public LocalDate valuationDate() {
        return valuationDate;
    }

    // ------------------------------------------------------------------ reads

    /**
     * @throws MissingMarketDataException if {@code key} is not present
     */
    public double get(MarketDataKey key) {
        Objects.requireNonNull(key, "key");
        Double value = values.get(key);
        if (value == null) {
            throw new MissingMarketDataException(key, values.keySet());
        }
        return value;
    }

    public double spot(InstrumentId instrumentId) {
        return get(MarketDataKey.spot(instrumentId));
    }

    public double volatility(InstrumentId instrumentId) {
        return get(MarketDataKey.volatility(instrumentId));
    }

    /**
     * The discount curve for {@code currency}, assembled from every zero-rate pillar the
     * snapshot holds for it and referenced at {@link #valuationDate()}.
     *
     * <p>Rebuilt on each call rather than cached. A curve of a dozen pillars costs a handful
     * of date arithmetic operations to assemble, valuation asks for it once per instrument,
     * and a cache would be the first mutable state in a type whose immutability is what makes
     * it safe to share across Monte Carlo workers without synchronisation. If a profile ever
     * shows this mattering, the fix is to hoist the curve out of the pricing loop rather than
     * to make the snapshot stateful.
     *
     * @throws MissingMarketDataException if the snapshot holds no pillar for {@code currency}
     */
    public YieldCurve yieldCurve(Currency currency) {
        Objects.requireNonNull(currency, "currency");
        YieldCurve.Builder curve = YieldCurve.builder(valuationDate)
                .interpolation(curveInterpolation);
        boolean any = false;

        for (Map.Entry<MarketDataKey, Double> entry : values.entrySet()) {
            if (entry.getKey() instanceof MarketDataKey.ZeroRate pillar
                    && pillar.currency() == currency) {
                curve.pillar(pillar.pillarDate(), entry.getValue());
                any = true;
            }
        }
        if (!any) {
            throw new MissingMarketDataException(
                    MarketDataKey.zeroRate(currency, valuationDate.plusYears(1)), values.keySet());
        }
        return curve.build();
    }

    /**
     * Units of {@code to} per one unit of {@code from}.
     *
     * <p>Resolves in three steps, and the order matters:
     *
     * <ol>
     *   <li>A currency against itself is 1, without consulting the snapshot. Requiring a
     *       USD/USD entry would be noise, and {@link CurrencyPair} rejects such a pair
     *       anyway.</li>
     *   <li>A directly quoted pair is used as stored.</li>
     *   <li>Otherwise the inverse pair is inverted. Storing only one direction means the two
     *       can never drift apart - a snapshot holding EUR/USD at 1.10 and USD/EUR at 0.92
     *       would imply a round-trip profit that exists only in the data.</li>
     * </ol>
     *
     * <p><b>No triangulation.</b> GBP to USD is not derived from GBP/EUR and EUR/USD; only
     * the pair itself and its inverse are consulted. Cross rates through a vehicle currency
     * need a stated base currency and a rule for which crosses are legal, and inferring one
     * silently would let a portfolio value against a rate nobody quoted. Listed in
     * {@code KNOWN_GAPS.md}.
     *
     * @throws MissingMarketDataException if neither direction is present
     */
    public double fxRate(Currency from, Currency to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from == to) {
            return 1.0;
        }
        MarketDataKey direct = MarketDataKey.fxRate(CurrencyPair.of(from, to));
        if (values.containsKey(direct)) {
            return values.get(direct);
        }
        MarketDataKey inverse = MarketDataKey.fxRate(CurrencyPair.of(to, from));
        Double inverseRate = values.get(inverse);
        if (inverseRate == null) {
            throw new MissingMarketDataException(direct, values.keySet());
        }
        return 1.0 / inverseRate;
    }

    public boolean contains(MarketDataKey key) {
        return values.containsKey(key);
    }

    public int size() {
        return values.size();
    }

    // ----------------------------------------------------------------- shocks

    /**
     * A new snapshot with {@code shock} applied to every key it matches.
     *
     * <p><b>This single method is the mechanism behind three separate features.</b> A stress
     * scenario is a composite shock; a Greek is a small shock plus a revaluation; a Monte
     * Carlo path is a shock drawn from a distribution. None of them needs machinery beyond
     * this, which is why the abstraction is worth its weight - see
     * {@code DESIGN_PROPOSAL.md} section 5.3.
     *
     * <p>This snapshot is left untouched, so the unshocked base case remains available for
     * the comparison every one of those features has to make.
     *
     * <p>The result is validated exactly as a built snapshot is. Before that check existed,
     * this method was a hole in the type's invariants: the builder refused a non-positive
     * spot while a shock could impose one, and the resulting NaN surfaced deep inside a
     * pricer with nothing to say which observation was at fault. A shocked market is still a
     * market, so it obeys the same rules.
     *
     * @throws MarketDataKey.InvalidMarketDataException if the shock produces an illegal value
     */
    public MarketDataSnapshot withShock(MarketShock shock) {
        Objects.requireNonNull(shock, "shock");
        Map<MarketDataKey, Double> shocked = new LinkedHashMap<>(values.size());
        values.forEach((key, value) -> {
            double result = shock.appliesTo(key) ? shock.shockFor(key, value) : value;
            key.requireValidValue(result);
            shocked.put(key, result);
        });
        return new MarketDataSnapshot(valuationDate, curveInterpolation, Map.copyOf(shocked));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MarketDataSnapshot other
                && valuationDate.equals(other.valuationDate)
                && curveInterpolation == other.curveInterpolation
                && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(valuationDate, curveInterpolation, values);
    }

    @Override
    public String toString() {
        return "MarketDataSnapshot(" + valuationDate + ", " + values.size() + " observations)";
    }

    /** Accumulates observations, then freezes them. */
    public static final class Builder {

        private final LocalDate valuationDate;
        private final Map<MarketDataKey, Double> values = new HashMap<>();
        private Interpolation curveInterpolation = Interpolation.LOG_LINEAR_DISCOUNT;

        private Builder(LocalDate valuationDate) {
            this.valuationDate = Objects.requireNonNull(valuationDate, "valuationDate");
        }

        /** How curves assembled from this snapshot fill in the gaps between their pillars. */
        public Builder curveInterpolation(Interpolation interpolation) {
            this.curveInterpolation = Objects.requireNonNull(interpolation, "interpolation");
            return this;
        }

        /**
         * The one place values enter a snapshot under construction, and therefore the one
         * place that has to validate.
         *
         * <p>The rule itself belongs to the key, not to this builder. That is not tidiness:
         * a builder holding the rules for every kind of market data is a second copy of them,
         * and the copy is what {@code withShock} was able to walk around.
         *
         * @throws MarketDataKey.InvalidMarketDataException if the value is illegal for the key
         */
        public Builder with(MarketDataKey key, double value) {
            Objects.requireNonNull(key, "key");
            key.requireValidValue(value);
            values.put(key, value);
            return this;
        }

        /** Positive, in the instrument's own currency. */
        public Builder spot(InstrumentId instrumentId, double price) {
            return with(MarketDataKey.spot(instrumentId), price);
        }

        /** Volatility as a decimal: {@code 0.25} is 25%. */
        public Builder volatility(InstrumentId instrumentId, double annualisedVolatility) {
            return with(MarketDataKey.volatility(instrumentId), annualisedVolatility);
        }

        /**
         * One pillar of a currency's discount curve: the continuously-compounded zero rate out
         * to {@code pillarDate}, as a decimal. Negative rates are permitted; see
         * {@link MarketDataKey.ZeroRate}.
         */
        public Builder zeroRate(Currency currency, LocalDate pillarDate, double rate) {
            return with(MarketDataKey.zeroRate(currency, pillarDate), rate);
        }

        /**
         * A flat curve for {@code currency}: one rate at every horizon.
         *
         * <p>Kept as the convenient case rather than as a different mechanism. A single pillar
         * extrapolates flat both ways, so this discounts at exactly {@code e^-rt} - identical
         * to the flat rate the engine used before curves existed, which is what let the whole
         * pricing stack move onto curves without a single reference value changing.
         */
        public Builder discountRate(Currency currency, double rate) {
            return zeroRate(currency, valuationDate.plusYears(1), rate);
        }

        /**
         * Every pillar of an already-built curve, typically one fitted by
         * {@code CurveBootstrapper}.
         *
         * <p>The pillars go in as individual keys rather than the curve going in whole. That is
         * the point of keying market data by pillar: a curve stored as one opaque value would
         * need its own shock mechanism, while a curve stored as pillars is shocked by the same
         * {@link MarketShock} that moves a spot price.
         *
         * @throws IllegalArgumentException if the curve is referenced at another date
         */
        public Builder curve(Currency currency, YieldCurve curve) {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(curve, "curve");
            if (!curve.referenceDate().equals(valuationDate)) {
                throw new IllegalArgumentException(
                        "Curve for " + currency.code() + " is referenced at "
                                + curve.referenceDate() + " but the snapshot is as of "
                                + valuationDate + ". Discounting from the wrong day shifts every "
                                + "factor on the curve by that many days of interest.");
            }
            curve.pillars().forEach((date, rate) -> zeroRate(currency, date, rate));
            return this;
        }

        /** Units of the pair's quote currency per one unit of its base currency. */
        public Builder fxRate(CurrencyPair pair, double rate) {
            Objects.requireNonNull(pair, "pair");
            return with(MarketDataKey.fxRate(pair), rate);
        }

        public MarketDataSnapshot build() {
            return new MarketDataSnapshot(valuationDate, curveInterpolation, Map.copyOf(values));
        }
    }

    /** Raised when a pricer asks for market data the snapshot does not hold. */
    public static final class MissingMarketDataException extends MercuryException {

        private final transient MarketDataKey key;

        MissingMarketDataException(MarketDataKey key, Set<MarketDataKey> available) {
            super("No market data for " + key.describe() + ". The snapshot holds: "
                    + (available.isEmpty() ? "nothing"
                            : available.stream().map(MarketDataKey::describe).sorted().toList())
                    + ". Missing data is an error rather than a zero, because a spot price read "
                    + "as zero produces a plausible but wrong valuation.");
            this.key = key;
        }

        public MarketDataKey key() {
            return key;
        }
    }
}
