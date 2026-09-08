package com.mercury.curve;

/**
 * How a curve fills in the gaps between the points it was built from.
 *
 * <h2>This is a modelling choice, not a numerical detail</h2>
 * A curve is quoted at a handful of tenors and asked about every date in between. What
 * happens between the pillars is invented by whoever built the curve, and different
 * inventions give different prices for the same instrument. Two desks holding identical
 * quotes and different interpolation will not agree on the value of a bond maturing between
 * two pillars. Naming the scheme is therefore part of naming the curve.
 *
 * <h2>What each one really does</h2>
 * Both schemes here agree exactly at the pillars and differ only in between, but they differ
 * in what they keep smooth:
 *
 * <ul>
 *   <li>{@link #LINEAR_ZERO} interpolates the zero rate itself. Simple, and the easiest to
 *       explain, but it implies forward rates that jump at every pillar and drift linearly
 *       between them - the zero curve looks smooth while the forward curve, which is what
 *       actually prices a floating coupon, is visibly not.</li>
 *   <li>{@link #LOG_LINEAR_DISCOUNT} interpolates the logarithm of the discount factor,
 *       which is the same as interpolating {@code r x t} linearly. That makes the
 *       instantaneous forward rate <em>constant</em> between pillars - which is why the
 *       market calls it flat-forward, and why it is the usual choice for a curve that will
 *       be asked for forward rates.</li>
 * </ul>
 *
 * <p>Neither is smoother than the other in any useful sense: both are piecewise linear in
 * something, so both have a kink at every pillar. Cubic-spline schemes remove the kink and
 * buy an unpleasant problem in exchange - a spline through market quotes can produce negative
 * forward rates between pillars, which is an arbitrage the input data never contained. That
 * trade is why flat-forward remains standard despite being the less elegant curve to draw.
 *
 * <h2>Why an enum with behaviour</h2>
 * The same reasoning as {@code DayCountConvention} - see ADR 0002. The set is closed and
 * owned by the engine, each member is a pure function with no state, and an enum gives
 * exhaustive switching, cheap equality and a name that round-trips through configuration.
 */
public enum Interpolation {

    /**
     * Linear in the zero rate.
     *
     * <pre>
     *   r(t) = r0 + (r1 - r0) x (t - t0) / (t1 - t0)
     * </pre>
     */
    LINEAR_ZERO {
        @Override
        double zeroRateBetween(double t0, double r0, double t1, double r1, double t) {
            return r0 + (r1 - r0) * (t - t0) / (t1 - t0);
        }
    },

    /**
     * Linear in the log discount factor, equivalently linear in {@code r x t}.
     *
     * <pre>
     *   r(t) x t = r0 x t0 + (r1 x t1 - r0 x t0) x (t - t0) / (t1 - t0)
     * </pre>
     *
     * <p>Since {@code ln DF(t) = -r(t) x t}, a straight line in {@code r x t} is a straight
     * line in {@code ln DF}, and the slope of {@code ln DF} is minus the instantaneous
     * forward rate. A constant slope is therefore a constant forward rate over the interval.
     */
    LOG_LINEAR_DISCOUNT {
        @Override
        double zeroRateBetween(double t0, double r0, double t1, double r1, double t) {
            double y0 = r0 * t0;
            double y1 = r1 * t1;
            double y = y0 + (y1 - y0) * (t - t0) / (t1 - t0);
            return y / t;
        }
    };

    /**
     * The zero rate at {@code t}, strictly between the pillars at {@code t0} and {@code t1}.
     *
     * <p>Package-private: callers interpolate through {@link YieldCurve}, which owns the
     * pillars, the ordering and the extrapolation rules. Exposing a bare two-point
     * interpolation would invite a caller to pick its own neighbouring pillars, which is
     * exactly the decision that must not be duplicated.
     *
     * <p>{@code t} is always positive here - a curve extrapolates flat below its first pillar
     * rather than interpolating down to zero - so {@code LOG_LINEAR_DISCOUNT} cannot divide
     * by zero.
     */
    abstract double zeroRateBetween(double t0, double r0, double t1, double r1, double t);
}
