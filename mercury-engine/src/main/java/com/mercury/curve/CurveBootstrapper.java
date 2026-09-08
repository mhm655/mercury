package com.mercury.curve;

import com.mercury.core.MercuryException;
import com.mercury.core.math.RootFinder;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a yield curve that reprices the quotes it was given.
 *
 * <h2>The idea</h2>
 * A curve is not observed. What is observed is a handful of prices - deposit rates, swap
 * rates - and the curve is whatever term structure is consistent with all of them at once.
 * Bootstrapping finds it one pillar at a time, shortest first:
 *
 * <ol>
 *   <li>Take the shortest quote. Solve for the zero rate at its pillar date that makes the
 *       curve reprice it exactly. That fixes the first pillar.</li>
 *   <li>Take the next. Everything before its maturity is now known, so the only free
 *       parameter is its own pillar. Solve for that.</li>
 *   <li>Repeat. Each step has exactly one unknown, which is what makes the problem tractable
 *       at all - fitting every pillar simultaneously would be a multi-dimensional solve with
 *       no guarantee of a unique answer.</li>
 * </ol>
 *
 * <p>Order matters and is not left to the caller: quotes are sorted by pillar date, so
 * passing them in the wrong order is not a mistake a user can make.
 *
 * <h2>Each step has exactly one unknown only if the pillars are in the right place</h2>
 * The argument above quietly assumes that a quote's last cashflow lands on its own pillar. It
 * did not, in the first version of this code, and the bootstrap was subtly wrong as a result:
 * pillars sat on nominal tenor dates while instruments paid on adjusted business days, so a
 * 2Y swap whose nominal maturity was a Sunday paid one day into the 2Y-to-5Y interpolation
 * interval. Step two therefore depended on step three, and the finished curve missed that one
 * quote by 1.5 basis points of present value while fitting every other quote exactly.
 *
 * <p>Asking each quote for {@link CurveInstrument#pillarDate} is what restores the property
 * the algorithm needs. The moral is worth keeping: a sequential algorithm is only sequential
 * if the data is arranged the way the argument assumed.
 *
 * <h2>Why every step goes through a solver</h2>
 * A single-payment quote inverts algebraically and could skip the search. Every step is
 * solved the same way regardless, because two code paths where one suffices means two places
 * for a convention to drift - for a saving of microseconds on an operation that happens once
 * per curve.
 *
 * <h2>The curve checks its own work</h2>
 * When construction finishes, every input quote is repriced against the finished curve. If
 * any of them misses, the bootstrap throws instead of returning. That is a real check and not
 * a formality: it is exactly what caught the pillar-date defect above. A curve that silently
 * ignores one of its own inputs cannot be told apart from a correct one until something is
 * priced with it.
 *
 * <p>Stateless and thread-safe.
 */
public final class CurveBootstrapper {

    /**
     * How closely the finished curve must reprice each input.
     *
     * <p>Looser than the solver's own tolerance on purpose. Bisection drives each residual to
     * roughly 1e-12, but a later pillar can still move an earlier quote very slightly through
     * the interpolator, so the final check allows for that without allowing a real misfit - a
     * basis point of error is around 1e-4, six orders of magnitude above this.
     */
    public static final double PAR_TOLERANCE = 1e-10;

    /** A plausible interest rate to start each search from, in the absence of anything better. */
    private static final double INITIAL_GUESS = 0.02;

    /** The first bracket tried around the guess, then doubled until it brackets a root. */
    private static final double INITIAL_BRACKET = 0.01;

    private CurveBootstrapper() {
    }

    /**
     * Fits a curve to {@code quotes}, using the default flat-forward interpolation.
     *
     * @throws BootstrapFailedException if the finished curve does not reprice every quote
     */
    public static YieldCurve bootstrap(LocalDate referenceDate, List<CurveInstrument> quotes) {
        return bootstrap(referenceDate, Interpolation.LOG_LINEAR_DISCOUNT, quotes);
    }

    /**
     * Fits a curve to {@code quotes}.
     *
     * <p>The interpolation scheme is an input rather than a detail, because the curve it
     * produces genuinely depends on it: the same quotes fitted flat-forward and fitted linear
     * in zero rates agree at every pillar and disagree everywhere else.
     *
     * @throws BootstrapFailedException if the finished curve does not reprice every quote
     */
    public static YieldCurve bootstrap(LocalDate referenceDate, Interpolation interpolation,
                                       List<CurveInstrument> quotes) {
        Objects.requireNonNull(referenceDate, "referenceDate");
        Objects.requireNonNull(interpolation, "interpolation");
        Objects.requireNonNull(quotes, "quotes");
        if (quotes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Bootstrapping needs at least one quote. A curve fitted to nothing is a "
                            + "curve that agrees with nothing.");
        }

        List<CurveInstrument> ordered = new ArrayList<>(quotes);
        ordered.sort(Comparator.comparing(quote -> quote.pillarDate(referenceDate)));
        requireDistinctPillars(referenceDate, ordered);

        YieldCurve curve = null;
        double guess = INITIAL_GUESS;

        for (CurveInstrument quote : ordered) {
            LocalDate pillar = quote.pillarDate(referenceDate);
            YieldCurve previous = curve;

            double solved = RootFinder.solve(
                    rate -> quote.repricingError(
                            candidate(previous, referenceDate, interpolation, pillar, rate)),
                    guess, INITIAL_BRACKET);

            curve = candidate(previous, referenceDate, interpolation, pillar, solved);

            // The next pillar is usually close to this one, so the previous answer is a far
            // better starting point than a constant - it keeps the bracket search short and,
            // on a steep curve, keeps it on the economically sensible side of the guess.
            guess = solved;
        }

        verify(curve, ordered);
        return curve;
    }

    /** The curve as it would be with one more pillar - or the first curve, if there is none yet. */
    private static YieldCurve candidate(YieldCurve previous, LocalDate referenceDate,
                                        Interpolation interpolation, LocalDate pillar,
                                        double rate) {
        if (previous == null) {
            return YieldCurve.builder(referenceDate)
                    .interpolation(interpolation)
                    .pillar(pillar, rate)
                    .build();
        }
        return previous.withPillar(pillar, rate);
    }

    /**
     * Two quotes maturing on one date would be solved one after the other, the second simply
     * overwriting the first. Caught up front so the error names the real problem rather than
     * surfacing later as a curve that mysteriously does not fit its own input.
     */
    private static void requireDistinctPillars(LocalDate referenceDate,
                                               List<CurveInstrument> ordered) {
        Set<LocalDate> seen = new HashSet<>();
        for (CurveInstrument quote : ordered) {
            LocalDate pillar = quote.pillarDate(referenceDate);
            if (!seen.add(pillar)) {
                throw new IllegalArgumentException(
                        "Two quotes both fix the pillar at " + pillar + ", the second of them "
                                + quote.describe() + ". One date cannot carry two rates, and "
                                + "keeping whichever was solved last would drop a quote "
                                + "silently.");
            }
        }
    }

    private static void verify(YieldCurve curve, List<CurveInstrument> quotes) {
        for (CurveInstrument quote : quotes) {
            double error = quote.repricingError(curve);
            if (!(Math.abs(error) <= PAR_TOLERANCE)) {
                throw new BootstrapFailedException(quote, error, curve);
            }
        }
    }

    /** Raised when the finished curve does not reprice one of the quotes it was built from. */
    public static final class BootstrapFailedException extends MercuryException {
        BootstrapFailedException(CurveInstrument quote, double error, YieldCurve curve) {
            super("The bootstrapped curve does not reprice " + quote.describe()
                    + ": residual " + error + ", tolerance " + PAR_TOLERANCE + ". The curve is "
                    + curve + " with pillars " + curve.pillars() + ". A curve that silently "
                    + "ignores one of its own inputs cannot be told apart from a correct one "
                    + "until something is priced with it, so this fails instead of returning.");
        }
    }
}
