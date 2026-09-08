package com.mercury.marketdata;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
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
        requirePositive(factor, "factor");
        MarketDataKey target = MarketDataKey.spot(instrumentId);
        return leaf(target::equals, value -> value * factor,
                "spot:" + instrumentId + " x" + factor);
    }

    /** Multiplies every spot price by {@code factor} - an index-wide move. */
    static MarketShock scaleAllSpots(double factor) {
        requirePositive(factor, "factor");
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

    /**
     * Shifts every pillar of one currency's discount curve - a parallel shift. The DV01 bump
     * is {@code BasisPoints.ONE}.
     *
     * <p>Matching every pillar rather than one key is what keeps DV01 meaning the same thing
     * it did when a currency had a single flat rate. A bump of one pillar is a different and
     * also useful risk number - key-rate duration - and is a shock this family does not have
     * yet, because nothing asks for it.
     */
    static MarketShock bumpRate(Currency currency, BasisPoints amount) {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(amount, "amount");
        return leaf(key -> key instanceof MarketDataKey.ZeroRate pillar
                        && pillar.currency() == currency,
                value -> value + amount.asDecimal(),
                "curve:" + currency.code() + " " + amount);
    }

    /**
     * Multiplies one currency pair's exchange rate by {@code factor}.
     *
     * <p><b>Matches whichever direction the snapshot stores.</b> A snapshot holds EUR/USD or
     * USD/EUR, never both, and a caller asking to move EUR/USD should not have to know which.
     * If the stored key is the inverse, the reciprocal factor is applied to it - so the pair
     * moves as asked either way, and the two directions stay exact reciprocals.
     *
     * <p>Matching only the exact key would have been three lines shorter and quietly wrong:
     * shocking a pair the snapshot happens to store the other way round would have matched
     * nothing and reported a sensitivity of zero for a currency the book was fully exposed
     * to. A silent zero is the worst answer a risk number can give.
     */
    static MarketShock scaleFxRate(CurrencyPair pair, double factor) {
        Objects.requireNonNull(pair, "pair");
        requirePositive(factor, "factor");
        MarketDataKey direct = MarketDataKey.fxRate(pair);
        MarketDataKey inverse = MarketDataKey.fxRate(pair.inverse());
        return new MarketShock() {
            @Override
            public double shockFor(MarketDataKey key, double currentValue) {
                return key.equals(direct) ? currentValue * factor : currentValue / factor;
            }

            @Override
            public boolean appliesTo(MarketDataKey key) {
                return key.equals(direct) || key.equals(inverse);
            }

            @Override
            public String toString() {
                return "fx:" + pair + " x" + factor;
            }
        };
    }

    /**
     * Multiplies every FX rate by {@code factor}.
     *
     * <p>Applied to the stored direction only, which is what keeps a shocked market
     * arbitrage-free: shocking EUR/USD up automatically shocks USD/EUR down, because the
     * inverse is derived on read rather than stored.
     */
    static MarketShock scaleAllFxRates(double factor) {
        requirePositive(factor, "factor");
        return leaf(key -> key instanceof MarketDataKey.FxRate, value -> value * factor,
                "all fx x" + factor);
    }

    /** Shifts every curve pillar in every currency - a parallel shift across the whole market. */
    static MarketShock bumpAllRates(BasisPoints amount) {
        Objects.requireNonNull(amount, "amount");
        return leaf(key -> key instanceof MarketDataKey.ZeroRate,
                value -> value + amount.asDecimal(), "all curves " + amount);
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

    /**
     * Multiplicative factors on prices, volatilities and rates are positive by nature.
     *
     * <p>{@link MarketDataKey} rejects the resulting value too, so this is a second line
     * rather than the only one - but it fails at the point the mistake was made, naming the
     * factor, instead of later naming an observation the caller never typed.
     */
    private static void requirePositive(double value, String name) {
        requireFinite(value, name);
        if (value <= 0) {
            throw new IllegalArgumentException(
                    name + " must be positive, but was " + value
                            + ". A scale factor multiplies a quantity that is itself positive; "
                            + "an additive move is a bump, not a scale.");
        }
    }
}
