package com.mercury.core.math;

import com.mercury.core.MercuryException;
import java.util.function.DoubleUnaryOperator;

/**
 * Finds where a continuous function crosses zero, by bisection.
 *
 * <h2>Why bisection and not Newton-Raphson</h2>
 * Newton converges quadratically and bisection linearly, so on paper this is the worse
 * algorithm. It is the right one here for three reasons, in order of weight:
 *
 * <ul>
 *   <li><b>It cannot fail to converge.</b> Given a bracket where the function changes sign,
 *       the interval halves every iteration and the root stays trapped inside it the whole
 *       time. Newton can oscillate, walk off to infinity, or land on a stationary point and
 *       divide by zero - and a curve bootstrapper that occasionally returns nonsense is far
 *       worse than one that is slower than it needed to be.</li>
 *   <li><b>There is no derivative to hand.</b> The function being solved is "reprice this
 *       instrument under a curve built with this pillar value". Differentiating that with
 *       respect to the pillar means differentiating through the interpolator, so Newton
 *       would need a numerical derivative anyway - two evaluations per step instead of one,
 *       spending most of the advantage.</li>
 *   <li><b>It is not on any hot path.</b> A curve is built once and then read many times.
 *       Sixty iterations per pillar, on a curve of a dozen pillars, is well under a thousand
 *       evaluations for the entire construction.</li>
 * </ul>
 *
 * <p>If profiling ever showed curve construction mattering, Brent's method is the drop-in
 * replacement: it keeps the bracket, so it keeps the guarantee, and falls back to bisection
 * whenever its faster interpolation step misbehaves. That is a change worth making with a
 * measurement in hand, not before.
 *
 * <h2>Failure is loud</h2>
 * Both ways this can fail - no sign change to bracket, and no convergence within the
 * iteration budget - throw. A root finder that quietly returns its best guess hands back a
 * number that looks like an answer, and the caller has no way to tell it apart from one.
 *
 * <p>Stateless and thread-safe.
 */
public final class RootFinder {

    /**
     * Absolute tolerance on the function value. Curve residuals are discount factors and
     * present values of order one, so this is close to the precision a double can carry
     * through the arithmetic that produced them.
     */
    public static final double DEFAULT_TOLERANCE = 1e-12;

    /**
     * Enough halvings to reduce any practical bracket below the tolerance: a bracket of
     * width 1 shrinks by 2^-100, far below what a double can represent.
     */
    private static final int MAX_ITERATIONS = 100;

    /** How far the search widens the bracket before giving up. */
    private static final int MAX_EXPANSIONS = 60;

    private RootFinder() {
    }

    /**
     * Solves {@code f(x) = 0} within a bracket known to contain a sign change.
     *
     * @throws NoRootException if the function has the same sign at both ends
     */
    public static double bisect(DoubleUnaryOperator f, double lower, double upper,
                                double tolerance) {
        double low = lower;
        double high = upper;
        double fLow = f.applyAsDouble(low);
        double fHigh = f.applyAsDouble(high);

        if (fLow == 0.0) {
            return low;
        }
        if (fHigh == 0.0) {
            return high;
        }
        if (Math.signum(fLow) == Math.signum(fHigh)) {
            throw new NoRootException(low, high, fLow, fHigh);
        }

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            // Midpoint as low + (high - low) / 2 rather than (low + high) / 2, for the same
            // reason binary search uses it: the sum can overflow, and it loses precision when
            // the two ends differ widely in magnitude.
            double mid = low + (high - low) / 2.0;
            double fMid = f.applyAsDouble(mid);

            if (Math.abs(fMid) <= tolerance || (high - low) / 2.0 <= tolerance) {
                return mid;
            }
            if (Math.signum(fMid) == Math.signum(fLow)) {
                low = mid;
                fLow = fMid;
            } else {
                high = mid;
            }
        }
        throw new NoConvergenceException(low, high, MAX_ITERATIONS);
    }

    /**
     * Solves {@code f(x) = 0} without being told a bracket, by widening one around
     * {@code guess} until the function changes sign.
     *
     * <p>The bracket grows geometrically from {@code initialWidth}. Callers know roughly
     * where their answer lives - a bootstrapped zero rate is a few percent - but not which
     * side of the guess it falls on, and demanding a correct bracket up front would push that
     * problem onto every caller.
     *
     * @throws NoRootException if no sign change is found within the search limit
     */
    public static double solve(DoubleUnaryOperator f, double guess, double initialWidth,
                               double tolerance) {
        if (initialWidth <= 0 || !Double.isFinite(initialWidth)) {
            throw new IllegalArgumentException(
                    "Initial bracket width must be positive and finite, but was " + initialWidth);
        }
        double width = initialWidth;
        double fGuess = f.applyAsDouble(guess);
        if (fGuess == 0.0) {
            return guess;
        }

        for (int i = 0; i < MAX_EXPANSIONS; i++) {
            double low = guess - width;
            double high = guess + width;

            // Bisect the half that actually brackets, rather than the whole widened interval.
            // Once the interval is large both halves may bracket a root; taking the nearer one
            // keeps the answer closest to the caller's guess, which for a curve pillar is the
            // economically sensible root rather than an artefact far away from it.
            if (Math.signum(f.applyAsDouble(low)) != Math.signum(fGuess)) {
                return bisect(f, low, guess, tolerance);
            }
            if (Math.signum(f.applyAsDouble(high)) != Math.signum(fGuess)) {
                return bisect(f, guess, high, tolerance);
            }
            width *= 2.0;
        }
        throw new NoRootException(guess - width, guess + width, fGuess, fGuess);
    }

    /** Convenience overload using {@link #DEFAULT_TOLERANCE}. */
    public static double solve(DoubleUnaryOperator f, double guess, double initialWidth) {
        return solve(f, guess, initialWidth, DEFAULT_TOLERANCE);
    }

    /** Raised when no sign change could be found, so no root can be guaranteed. */
    public static final class NoRootException extends MercuryException {
        NoRootException(double low, double high, double fLow, double fHigh) {
            super("No sign change between x=" + low + " (f=" + fLow + ") and x=" + high
                    + " (f=" + fHigh + "), so this interval is not guaranteed to contain a "
                    + "root. Bisection deliberately refuses to guess: a best effort returned "
                    + "here would be indistinguishable from an answer.");
        }
    }

    /** Raised when the iteration budget runs out, which should not happen in practice. */
    public static final class NoConvergenceException extends MercuryException {
        NoConvergenceException(double low, double high, int iterations) {
            super("Bisection did not converge after " + iterations + " iterations; the bracket "
                    + "is still [" + low + ", " + high + "]. A bracket halving 100 times cannot "
                    + "still be wide, so this indicates a discontinuous or NaN-producing "
                    + "function rather than a slow one.");
        }
    }
}
