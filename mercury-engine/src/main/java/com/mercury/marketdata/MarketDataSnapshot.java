package com.mercury.marketdata;

import com.mercury.core.MercuryException;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
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
 * <h2>Missing data is an error, not a zero</h2>
 * Asking for a value that is not present throws {@link MissingMarketDataException} rather
 * than defaulting. A missing spot price silently read as zero would price an option at its
 * discounted strike and quietly report a plausible, wrong number - the worst failure mode
 * available. Loud beats plausible.
 */
public final class MarketDataSnapshot {

    private final Map<MarketDataKey, Double> values;

    private MarketDataSnapshot(Map<MarketDataKey, Double> values) {
        this.values = values;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** An empty market. Useful only in tests asserting that missing data is rejected. */
    public static MarketDataSnapshot empty() {
        return new MarketDataSnapshot(Map.of());
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

    public double discountRate(Currency currency) {
        return get(MarketDataKey.discountRate(currency));
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
        return new MarketDataSnapshot(Map.copyOf(shocked));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MarketDataSnapshot other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "MarketDataSnapshot(" + values.size() + " observations)";
    }

    /** Accumulates observations, then freezes them. */
    public static final class Builder {

        private final Map<MarketDataKey, Double> values = new HashMap<>();

        private Builder() {
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
         * Continuously-compounded rate as a decimal: {@code 0.05} is 5%. Negative rates are
         * permitted; see {@link MarketDataKey.DiscountRate}.
         */
        public Builder discountRate(Currency currency, double rate) {
            return with(MarketDataKey.discountRate(currency), rate);
        }

        /** Units of the pair's quote currency per one unit of its base currency. */
        public Builder fxRate(CurrencyPair pair, double rate) {
            Objects.requireNonNull(pair, "pair");
            return with(MarketDataKey.fxRate(pair), rate);
        }

        public MarketDataSnapshot build() {
            return new MarketDataSnapshot(Map.copyOf(values));
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
