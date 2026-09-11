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
 * 7.2: "injected clock, injected seed"). A caller seeds it once (typically a
 * {@code SplittableRandom}, chosen for M13's future per-task splitting, not needed yet since
 * M12 is single-threaded) and this draws from whatever state it is handed - deterministic
 * given a deterministic generator, and callable from any thread that owns its own generator.
 */
public final class GeometricBrownianMotion {

    private GeometricBrownianMotion() {
    }

    /**
     * One terminal value, {@code years} forward from {@code spot}.
     *
     * @param spot       the starting value; must be positive
     * @param drift      the annualised drift {@code mu} - the risk-neutral rate for
     *                   risk-neutral pricing, or an assumed real-world drift (often zero
     *                   over a short horizon) for a real-world simulation. This method does
     *                   not decide which; the caller does, by what it passes.
     * @param volatility annualised volatility {@code sigma}; must be non-negative
     * @param years      time horizon in years; must be non-negative
     * @param rng        the source of randomness for this one draw
     * @throws IllegalArgumentException if {@code spot} is not positive, or {@code volatility}
     *                                  or {@code years} is negative
     */
    public static double terminalValue(double spot, double drift, double volatility,
                                       double years, RandomGenerator rng) {
        if (!(spot > 0.0)) {
            throw new IllegalArgumentException("spot must be positive, but was " + spot);
        }
        if (volatility < 0.0) {
            throw new IllegalArgumentException("volatility must not be negative, but was " + volatility);
        }
        if (years < 0.0) {
            throw new IllegalArgumentException("years must not be negative, but was " + years);
        }
        Objects.requireNonNull(rng, "rng");

        if (years == 0.0) {
            // No time has passed - the terminal value is the spot, and sigma*sqrt(0) is zero
            // regardless of volatility, so drawing Z would multiply a real random number by
            // zero for no reason.
            return spot;
        }

        double z = rng.nextGaussian();
        double exponent = (drift - 0.5 * volatility * volatility) * years
                + volatility * Math.sqrt(years) * z;
        return spot * Math.exp(exponent);
    }
}
