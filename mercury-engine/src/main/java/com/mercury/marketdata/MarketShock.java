package com.mercury.marketdata;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Predicate;

/**
 * A transformation of market data: which observations it touches, and what it does to them.
 *
 * <h2>Why the interface is shaped this way</h2>
 * A shock could have been {@code MarketDataSnapshot apply(MarketDataSnapshot)}, which is more
 * general. Splitting it into {@link #appliesTo} and {@link #shockFor} instead makes
 * composition fall out for free: combining shocks is "route each key through the parts that
 * match it", with no question of what happens when two of them rewrite the same map.
 *
 * <p>{@link #shockFor} takes the key as well as the value, so a composite can dispatch
 * per-key. A first draft of this interface took only the value, which forced the composite
 * into a method that threw - a sure sign the abstraction was wrong rather than the caller.
 *
 * <p>Scenarios genuinely are trees: "market crash" is equities down 30% <em>and</em>
 * volatility up 50% <em>and</em> rates up 150bp, and a caller applying it should not be able
 * to tell whether it holds one shock or twenty. Composite, used because the domain contains
 * a composite.
 *
 * <h2>One abstraction, three features</h2>
 * <ul>
 *   <li><b>Stress testing</b> - a named scenario is a composite shock (M11).</li>
 *   <li><b>Greeks</b> - delta is {@link #scaleSpot} by a fraction of a percent, then
 *       revalue. Every instrument with a pricer gets sensitivities with no per-instrument
 *       code.</li>
 *   <li><b>Monte Carlo</b> - each simulated path is a shock drawn from a distribution
 *       (M12).</li>
 * </ul>
 *
 * <p>Implementations must be pure and stateless: the same shock applied to the same value must
 * always give the same result, or reproducibility is lost.
 */
@FunctionalInterface
public interface MarketShock {

    /**
     * The shocked value for one observation.
     *
     * <p>Only called for keys where {@link #appliesTo} is true, but implementations should not
     * rely on that when composing.
     */
    double shockFor(MarketDataKey key, double currentValue);

    /**
     * Whether this shock changes {@code key}. Defaults to everything, so a bare lambda works;
     * the factories below narrow it.
     */
    default boolean appliesTo(MarketDataKey key) {
        return true;
    }

    /**
     * Both shocks, applied in order.
     *
     * <p>Order is only observable when the two touch the same key. Composing a scenario from
     * disjoint shocks - the normal case - is commutative.
     */
    default MarketShock and(MarketShock other) {
        Objects.requireNonNull(other, "other");
        return composite(List.of(this, other));
    }

    // ------------------------------------------------------------- factories

    /** Leaves the market untouched. The identity of {@link #and}. */
    static MarketShock none() {
        return new MarketShock() {
            @Override
            public double shockFor(MarketDataKey key, double currentValue) {
                return currentValue;
            }

            @Override
            public boolean appliesTo(MarketDataKey key) {
                return false;
            }

            @Override
            public String toString() {
                return "no shock";
            }
        };
    }

    /** Applies every shock in turn, each to the keys it matches. */
    static MarketShock composite(List<MarketShock> shocks) {
        List<MarketShock> parts = List.copyOf(shocks);
        if (parts.isEmpty()) {
            return none();
        }
        return new MarketShock() {
            @Override
            public double shockFor(MarketDataKey key, double currentValue) {
                double result = currentValue;
                for (MarketShock part : parts) {
                    if (part.appliesTo(key)) {
                        result = part.shockFor(key, result);
                    }
                }
                return result;
            }

            @Override
            public boolean appliesTo(MarketDataKey key) {
                return parts.stream().anyMatch(part -> part.appliesTo(key));
            }

            @Override
            public String toString() {
                return parts.toString();
            }
        };
    }

    /**
     * Multiplies one instrument's spot price by {@code factor}.
     *
     * <p>{@code scaleSpot(id, 0.70)} is a 30% fall. Relative rather than absolute because that
     * is how equity scenarios are quoted, and because a bump proportional to spot keeps a
     * numerical delta well conditioned across instruments priced in the tens and in the
     * thousands.
     */
    static MarketShock scaleSpot(InstrumentId instrumentId, double factor) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        requireFinite(factor, "factor");
        MarketDataKey target = MarketDataKey.spot(instrumentId);
        return leaf(target::equals, value -> value * factor,
                "spot:" + instrumentId + " x" + factor);
    }

    /** Multiplies every spot price by {@code factor} - an index-wide move. */
    static MarketShock scaleAllSpots(double factor) {
        requireFinite(factor, "factor");
        return leaf(key -> key instanceof MarketDataKey.SpotPrice, value -> value * factor,
                "all spots x" + factor);
    }

    /**
     * Adds {@code absolute} to one instrument's volatility. {@code 0.05} is five vol points.
     *
     * <p>Floored at zero: negative volatility has no meaning, and Black-Scholes would return
     * NaN from the square root rather than failing usefully.
     */
    static MarketShock bumpVolatility(InstrumentId instrumentId, double absolute) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        requireFinite(absolute, "absolute");
        MarketDataKey target = MarketDataKey.volatility(instrumentId);
        return leaf(target::equals, value -> Math.max(0.0, value + absolute),
                "vol:" + instrumentId + " +" + absolute);
    }

    /** Multiplies every volatility by {@code factor}. {@code 1.50} is "volatility up 50%". */
    static MarketShock scaleAllVolatilities(double factor) {
        requireFinite(factor, "factor");
        return leaf(key -> key instanceof MarketDataKey.Volatility,
                value -> Math.max(0.0, value * factor), "all vols x" + factor);
    }

    /** Shifts one currency's discount rate. The DV01 bump is {@code BasisPoints.ONE}. */
    static MarketShock bumpRate(Currency currency, BasisPoints amount) {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(amount, "amount");
        MarketDataKey target = MarketDataKey.discountRate(currency);
        return leaf(target::equals, value -> value + amount.asDecimal(),
                "rate:" + currency.code() + " " + amount);
    }

    /** Shifts every discount rate - a parallel shift across currencies. */
    static MarketShock bumpAllRates(BasisPoints amount) {
        Objects.requireNonNull(amount, "amount");
        return leaf(key -> key instanceof MarketDataKey.DiscountRate,
                value -> value + amount.asDecimal(), "all rates " + amount);
    }

    private static MarketShock leaf(Predicate<MarketDataKey> selector,
                                    DoubleUnaryOperator transformation,
                                    String description) {
        return new MarketShock() {
            @Override
            public double shockFor(MarketDataKey key, double currentValue) {
                return transformation.applyAsDouble(currentValue);
            }

            @Override
            public boolean appliesTo(MarketDataKey key) {
                return selector.test(key);
            }

            @Override
            public String toString() {
                return description;
            }
        };
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite, but was " + value);
        }
    }
}
