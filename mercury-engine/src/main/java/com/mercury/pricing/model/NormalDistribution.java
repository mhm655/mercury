package com.mercury.pricing.model;

/**
 * The standard normal cumulative distribution, N(x).
 *
 * <p>Black-Scholes needs it and the JDK does not provide it. Rather than pull in a numerics
 * library for one function, this uses the Zelen and Severo rational approximation
 * (Abramowitz and Stegun 26.2.17), whose absolute error is bounded by <b>7.5e-8</b>.
 *
 * <h2>Is that accurate enough?</h2>
 * Yes, and the reason is worth stating rather than assuming. An option price is roughly
 * {@code S x N(d1)}, so an error of 7.5e-8 in N produces an error of about 7.5e-8 x S - under
 * a hundredth of a cent on a 100 spot. That is several orders of magnitude smaller than the
 * error already introduced by Black-Scholes' own assumptions: constant volatility, lognormal
 * returns, no dividends. Chasing machine precision here would be refining the fourth decimal
 * of an answer whose first decimal is model-dependent.
 *
 * <h2>How the error propagates</h2>
 * The bound applies to N, not to a price, and it has to be carried through. A call is
 * {@code S N(d1) - K e^-rT N(d2)}, so an error of {@code eps} in N shows up as roughly
 * {@code (S + K) eps} in the price - on a spot of 100 that is about 1.5e-5, not 7.5e-8.
 * Reference-value tests must set their tolerances from the propagated figure; assuming the
 * input bound applies to the output produces a test that fails on correct code.
 *
 * <p>Stateless and thread-safe, which matters because Monte Carlo will call it from every
 * worker at once.
 */
public final class NormalDistribution {

    private static final double A1 = 0.319381530;
    private static final double A2 = -0.356563782;
    private static final double A3 = 1.781477937;
    private static final double A4 = -1.821255978;
    private static final double A5 = 1.330274429;
    private static final double GAMMA = 0.2316419;
    private static final double INVERSE_ROOT_TWO_PI = 1.0 / Math.sqrt(2.0 * Math.PI);

    private NormalDistribution() {
    }

    /**
     * P(Z &lt;= x) for a standard normal Z.
     *
     * <p>Evaluated on |x| and reflected for negatives, because the approximation is defined
     * for the positive tail and the distribution is symmetric. Reflecting rather than
     * extending the polynomial keeps accuracy uniform across the range.
     */
    public static double cumulative(double x) {
        if (Double.isNaN(x)) {
            throw new IllegalArgumentException("N(x) is undefined for NaN");
        }
        if (x == Double.POSITIVE_INFINITY) {
            return 1.0;
        }
        if (x == Double.NEGATIVE_INFINITY) {
            return 0.0;
        }
        double absolute = Math.abs(x);
        double k = 1.0 / (1.0 + GAMMA * absolute);
        double polynomial = k * (A1 + k * (A2 + k * (A3 + k * (A4 + k * A5))));
        double upperTail = density(absolute) * polynomial;
        return x >= 0.0 ? 1.0 - upperTail : upperTail;
    }

    /** The standard normal probability density, used by the approximation and by Vega. */
    public static double density(double x) {
        return INVERSE_ROOT_TWO_PI * Math.exp(-0.5 * x * x);
    }
}
