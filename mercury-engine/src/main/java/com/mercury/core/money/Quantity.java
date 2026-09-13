package com.mercury.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A signed amount of an instrument: shares, contracts, or notional.
 *
 * <h2>Why signed</h2>
 * A position of -100 shares is short, and short positions are ordinary. Encoding
 * direction in the sign rather than in a separate flag means position arithmetic is
 * plain addition - applying a sell to a long position, or to a short one, is the same
 * line of code with no branching. Orders pair an unsigned quantity with an explicit
 * {@code Side} instead, because "sell -100" is not a thing anyone means to say.
 *
 * <h2>Why BigDecimal</h2>
 * Bond and swap notionals are not integers ({@code 1,000,000.50} is a legitimate
 * notional), and fractional share trading is now routine, so a {@code long} count would
 * not cover the domain.
 *
 * <p><b>Not used by the order book.</b> Exchange-traded quantities are whole units, so
 * {@code Order} and the matching loop use a primitive {@code long} and never allocate one of
 * these; see {@code Order}'s javadoc. This type is for positions, ledgers and OTC notionals.
 *
 * <p>Instances are immutable and thread-safe.
 */
public record Quantity(BigDecimal value) implements Comparable<Quantity> {

    /**
     * Decimal places retained. Eight is well beyond any real lot convention and keeps
     * {@code equals} well defined by giving every instance the same scale - the same
     * reasoning as {@link Money}'s normalisation.
     */
    public static final int SCALE = 8;

    public static final Quantity ZERO = Quantity.of(BigDecimal.ZERO);

    public Quantity {
        Objects.requireNonNull(value, "value");
        DecimalBounds.requireReasonable(value, "Quantity");
        value = value.setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    public static Quantity of(BigDecimal value) {
        return new Quantity(value);
    }

    public static Quantity of(long value) {
        return new Quantity(BigDecimal.valueOf(value));
    }

    public static Quantity of(String value) {
        return new Quantity(new BigDecimal(value));
    }

    public Quantity plus(Quantity other) {
        return new Quantity(value.add(other.value));
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    /** A long position. */
    public boolean isLong() {
        return value.signum() > 0;
    }

    /** A short position. */
    public boolean isShort() {
        return value.signum() < 0;
    }

    /** {@code -1}, {@code 0} or {@code 1}. */
    public int signum() {
        return value.signum();
    }

    /**
     * Multiplies a per-unit amount by this quantity.
     *
     * <p>Lives here rather than on {@link Money} because quantity is the natural
     * multiplier and this keeps the one rounding step in a single place: consideration
     * is computed once from price and quantity, never accumulated step by step.
     */
    public Money times(Money perUnit) {
        return perUnit.multipliedBy(value);
    }

    @Override
    public int compareTo(Quantity other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.stripTrailingZeros().toPlainString();
    }
}
