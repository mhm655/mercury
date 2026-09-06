package com.mercury.marketdata;

import com.mercury.core.MercuryException;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
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
     */
    public MarketDataSnapshot withShock(MarketShock shock) {
        Objects.requireNonNull(shock, "shock");
        Map<MarketDataKey, Double> shocked = new LinkedHashMap<>(values.size());
        values.forEach((key, value) ->
                shocked.put(key, shock.appliesTo(key) ? shock.shockFor(key, value) : value));
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

        public Builder with(MarketDataKey key, double value) {
            Objects.requireNonNull(key, "key");
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Market data must be finite, but " + key.describe() + " was " + value);
            }
            values.put(key, value);
            return this;
        }

        public Builder spot(InstrumentId instrumentId, double price) {
            if (price <= 0) {
                throw new IllegalArgumentException(
                        "Spot price for " + instrumentId + " must be positive, but was " + price);
            }
            return with(MarketDataKey.spot(instrumentId), price);
        }

        /** Volatility as a decimal: {@code 0.25} is 25%. */
        public Builder volatility(InstrumentId instrumentId, double annualisedVolatility) {
            if (annualisedVolatility < 0) {
                throw new IllegalArgumentException(
                        "Volatility for " + instrumentId + " must not be negative, but was "
                                + annualisedVolatility);
            }
            return with(MarketDataKey.volatility(instrumentId), annualisedVolatility);
        }

        /**
         * Continuously-compounded rate as a decimal: {@code 0.05} is 5%.
         *
         * <p>Negative rates are permitted. They are unusual but real - EUR and JPY policy
         * rates have been below zero - and rejecting them would encode a market condition as
         * a validation rule.
         */
        public Builder discountRate(Currency currency, double rate) {
            return with(MarketDataKey.discountRate(currency), rate);
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
