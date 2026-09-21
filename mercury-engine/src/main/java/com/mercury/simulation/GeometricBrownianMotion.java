package com.mercury.simulation;

import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Draws a terminal value from geometric Brownian motion.
 *
 * <h2>Exact, not discretized</h2>
 * A general stochastic differential equation has to be stepped through time
 * (Euler-Maruyama or better), accumulating discretization error with every step. GBM does
 * not need that here: it has a closed-form solution for its terminal distribution,
 *
 * <pre>
 *   S(T) = S(0) * exp( (mu - sigma^2 / 2) T + sigma sqrt(T) Z ),   Z ~ N(0, 1)
 * </pre>
 *
 * so drawing {@code S(T)} directly from one standard normal is an <b>exact</b> sample from
 * the true terminal distribution - the only error left is Monte Carlo sampling error (which
 * shrinks as {@code O(1/sqrt(N))} in path count), not time-stepping error. Simulating
 * intermediate points would cost more and teach this method nothing it does not already
 * know exactly.
 *
 * <h2>Reproducibility</h2>
 * Takes a {@link RandomGenerator} rather than owning one, the same "injected, not read"
 * discipline {@code SimulationClock} enforces for time (`docs/DESIGN_PROPOSAL.md` section
 * 7.2: "injected clock, injected seed"). A caller seeds it once - typically a
 * {@code SplittableRandom}, chosen so that M13 could split a stream per block of paths, which
 * {@code PathBlocks} now does - and this draws from whatever state it is handed:
 * deterministic given a deterministic generator, and callable from any thread that owns its
 * own generator. That is what makes a seeded run bit-identical on one worker or twelve.
 */
public final class GeometricBrownianMotion {

    private GeometricBrownianMotion() {
    }

    /**
     * One terminal value, {@code years} forward from {@code spot}, drawing its own standard
     * normal from {@code rng}.
     *
     * @param spot       the starting value; must be positive
     * @param drift      the annualised drift {@code mu} - the risk-neutral rate for
     *                   risk-neutral pricing, or an assumed real-world drift (often zero
     *                   over a short horizon) for a real-world simulation. This method does
     *                   not decide which; the caller does, by what it passes.
     * @param volatility annualised volatility {@code sigma}; must be non-negative
     * @param years      time horizon in years; must be non-negative
     * @param rng        the source of randomness for this one draw
     * @throws IllegalArgumentException if any argument is {@code NaN} or infinite, {@code spot}
     *                                  is not positive, or {@code volatility} or {@code years}
     *                                  is negative
     */
    public static double terminalValue(double spot, double drift, double volatility,
                                       double years, RandomGenerator rng) {
        Objects.requireNonNull(rng, "rng");
        return terminalValueFromStandardNormal(spot, drift, volatility, years, rng.nextGaussian());
    }

    /**
     * {@link #terminalValue}, given an already-drawn standard normal {@code z} rather than
     * drawing one itself.
     *
     * <h2>Why this exists separately, M20</h2>
     * A single-factor simulation ({@link #terminalValue}) can draw its own {@code Z} because
     * nothing else needs to see it first. A <em>correlated</em> multi-factor simulation cannot:
     * {@code CorrelatedMonteCarloVaRCalculator} draws one independent standard normal per risk
     * factor, multiplies the vector through a correlation matrix's Cholesky factor to produce
     * correlated normals, and only then needs this formula applied to each factor's own
     * correlated {@code z} - the drawing and the formula are genuinely two different steps
     * once more than one factor is involved, so this method is the formula alone, exposed
     * rather than duplicated.
     *
     * @param z the standard normal driving this draw - {@code Z ~ N(0, 1)} in the class javadoc's
     *          formula, whether drawn independently or produced by correlating several draws
     * @throws IllegalArgumentException if any argument is {@code NaN} or infinite, {@code spot}
     *                                  is not positive, {@code volatility} or {@code years} is
     *                                  negative, or {@code z} is not finite
     */
    public static double terminalValueFromStandardNormal(double spot, double drift,
                                                          double volatility, double years, double z) {
        // Checked as !(x >= lowerBound), not x < lowerBound: a NaN argument fails every
        // ordinary comparison, including x < lowerBound, and would otherwise flow silently
        // through Math.sqrt and Math.exp into a NaN result rather than being rejected here.
        // Double.isFinite additionally catches +/-Infinity, which passes a bare ">= 0" check
        // but is exactly as useless a result once it reaches Math.exp.
        if (!Double.isFinite(spot) || !(spot > 0.0)) {
            throw new IllegalArgumentException("spot must be finite and positive, but was " + spot);
        }
        if (!Double.isFinite(drift)) {
            throw new IllegalArgumentException("drift must be finite, but was " + drift);
        }
        if (!Double.isFinite(volatility) || !(volatility >= 0.0)) {
            throw new IllegalArgumentException(
                    "volatility must be finite and non-negative, but was " + volatility);
        }
        if (!Double.isFinite(years) || !(years >= 0.0)) {
            throw new IllegalArgumentException("years must be finite and non-negative, but was " + years);
        }
        if (!Double.isFinite(z)) {
            throw new IllegalArgumentException("z must be finite, but was " + z);
        }

        if (years == 0.0) {
            // No time has passed - the terminal value is the spot, and sigma*sqrt(0) is zero
            // regardless of volatility, so a nonzero z would multiply against zero for no
            // reason.
            return spot;
        }

        double exponent = (drift - 0.5 * volatility * volatility) * years
                + volatility * Math.sqrt(years) * z;
        return spot * Math.exp(exponent);
    }
}
