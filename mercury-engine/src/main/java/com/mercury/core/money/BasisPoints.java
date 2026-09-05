package com.mercury.core.money;

/**
 * An interest-rate quantity in basis points: one bp is one hundredth of a percent,
 * {@code 0.0001} in decimal.
 *
 * <h2>Why a type rather than a bare double</h2>
 * Rates are quoted in three units that all look like plain numbers - 150 bp, 1.5%, 0.015
 * - and mixing them up is off by a factor of 100 or 10,000. Every rate shock and curve
 * bump in the risk engine is expressed in bp ("rates +150bp", "DV01 is the P&amp;L for
 * +1bp"), so the unit is worth naming in the type system. {@link #asDecimal()} is the
 * only way out, which makes each conversion a visible, greppable step.
 *
 * <h2>Why double and not BigDecimal</h2>
 * This sits on the model side of the split described in {@link Money}: basis points feed
 * discount factors, Greeks and Monte Carlo paths, all of which are approximations
 * evaluated in {@code double}. Exact decimal arithmetic here would cost speed in the
 * hottest loops in the project and buy no accuracy that survives the first
 * {@code Math.exp}.
 *
 * <p>Signed: a shock can be negative. Immutable and thread-safe.
 */
public record BasisPoints(double value) {

    public static final BasisPoints ZERO = new BasisPoints(0.0);

    /** One basis point - the standard DV01 bump. */
    public static final BasisPoints ONE = new BasisPoints(1.0);

    public BasisPoints {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Basis points must be finite, but was " + value);
        }
    }

    public static BasisPoints of(double value) {
        return new BasisPoints(value);
    }

    /** {@code BasisPoints.ofPercent(1.5)} is 150 bp. */
    public static BasisPoints ofPercent(double percent) {
        return new BasisPoints(percent * 100.0);
    }

    /** {@code BasisPoints.ofDecimal(0.015)} is 150 bp. */
    public static BasisPoints ofDecimal(double decimal) {
        return new BasisPoints(decimal * 10_000.0);
    }

    /** As a decimal fraction: 150 bp becomes {@code 0.015}. The rate-maths form. */
    public double asDecimal() {
        return value / 10_000.0;
    }

    /** As a percentage: 150 bp becomes {@code 1.5}. */
    public double asPercent() {
        return value / 100.0;
    }

    public BasisPoints plus(BasisPoints other) {
        return new BasisPoints(value + other.value);
    }

    public BasisPoints minus(BasisPoints other) {
        return new BasisPoints(value - other.value);
    }

    public BasisPoints negated() {
        return new BasisPoints(-value);
    }

    public BasisPoints scaledBy(double factor) {
        return new BasisPoints(value * factor);
    }

    @Override
    public String toString() {
        return value + "bp";
    }
}
