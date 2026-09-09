package com.mercury.curve;

import com.mercury.core.MercuryException;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.Tenor;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A term structure of interest rates: what a currency pays for money over each horizon.
 *
 * <h2>What it stores, and why zero rates</h2>
 * The curve holds continuously-compounded zero rates at a set of pillar dates, and computes
 * everything else from them. It could equally have stored discount factors - the two are the
 * same information, related by {@code DF(t) = e^(-r t)}. Zero rates were chosen because they
 * are what a bootstrapper solves for and what a human reads: "the five-year point is at
 * 4.1%" is a sentence about a market, while "the five-year discount factor is 0.8148" is a
 * sentence about arithmetic.
 *
 * <p>Continuous compounding throughout, matching {@code BlackScholesModel} and
 * {@code DiscountedCashflowModel}, so a bond and an option in one portfolio agree about what
 * a rate means. Quoting a rate on the wrong compounding basis is a real error of order
 * {@code r^2 T / 2} - about 5 basis points on a ten-year point at 4% - so the convention is
 * fixed here rather than left to the caller.
 *
 * <h2>Pillars are dates, not tenors</h2>
 * This looks like a detail and is not. A curve pillar has to sit on the date an instrument's
 * last cashflow actually falls, which is a business day, and not on the nominal date its
 * tenor names, which may be a weekend.
 *
 * <p>The first version of this class keyed pillars by {@link Tenor}, and the difference broke
 * the bootstrap. Reference date 28 June 2024 plus two years is Sunday 28 June 2026, so a 2Y
 * par swap makes its final payment on Monday the 29th - one day <em>past</em> its own pillar.
 * That payment therefore interpolated between the 2Y and 5Y pillars, so solving for the 2Y
 * point depended on a 5Y point that had not been solved yet, and adding the 5Y afterwards
 * moved the 2Y swap off par by about 1.5 basis points of present value. Every tenor in the
 * test market repriced exactly except that one, which is the signature of a date problem
 * rather than a numerical one.
 *
 * <p>Keying by date makes each bootstrap step genuinely sequential again. Tenors remain the
 * convenient way to <em>quote</em> a curve, so {@link Builder#pillar(Tenor, double)} resolves
 * one against the reference date - but what the curve stores is where the money moves.
 *
 * <h2>Outside the pillars, it goes flat</h2>
 * Below the first pillar and above the last, the zero rate is held constant at the nearest
 * pillar rather than extrapolated along the last slope. Continuing the slope is the obvious
 * alternative and is worse in a specific way: a curve that is upward sloping at the long end
 * will, extrapolated, keep rising forever, and the implied forward rates run away with it.
 * Flat extrapolation says the only honest thing about a horizon nobody quoted, which is that
 * the curve has no information there.
 *
 * <p>The short end matters less than it looks: {@code DF(0) = e^0 = 1} whatever rate is
 * extrapolated back to zero, so the near-dated end is well behaved by construction.
 *
 * <h2>A flat curve is a curve with one pillar</h2>
 * {@link #flat} builds a single-pillar curve, which extrapolates its one rate everywhere.
 * That is deliberate: before M5b the engine discounted at a flat rate per currency through a
 * separate code path, and keeping that as a special case would have meant two ways to
 * discount and two places for a convention to drift. There is one mechanism, and the flat
 * case is its degenerate instance.
 *
 * <p>Immutable and thread-safe. {@link #withPillar} returns a new curve, which is what lets
 * the bootstrapper build one pillar at a time without any of the intermediate curves being
 * observable.
 */
public final class YieldCurve {

    private final LocalDate referenceDate;
    private final DayCountConvention dayCount;
    private final Interpolation interpolation;

    /** Pillars in date order. Parallel arrays rather than a map: the read path is hot. */
    private final LocalDate[] dates;
    private final double[] times;
    private final double[] rates;

    private YieldCurve(LocalDate referenceDate, DayCountConvention dayCount,
                       Interpolation interpolation, Map<LocalDate, Double> pillars) {
        this.referenceDate = referenceDate;
        this.dayCount = dayCount;
        this.interpolation = interpolation;

        // A TreeMap is safe here in a way it was not when the key was a Tenor: LocalDate's
        // compareTo agrees with its equals, so no two distinct keys can collapse into one.
        // Tenor deliberately orders by nominal length in days, which makes 52W and 364D
        // compare equal while being different values, and a map keyed on it silently dropped
        // a pillar the caller had supplied.
        TreeMap<LocalDate, Double> sorted = new TreeMap<>(pillars);
        int size = sorted.size();
        this.dates = new LocalDate[size];
        this.times = new double[size];
        this.rates = new double[size];

        int i = 0;
        for (Map.Entry<LocalDate, Double> pillar : sorted.entrySet()) {
            if (!pillar.getKey().isAfter(referenceDate)) {
                throw new IllegalArgumentException(
                        "Curve pillar " + pillar.getKey() + " is not after the reference date "
                                + referenceDate + ". A pillar at or before today carries no "
                                + "information about the future, and one at exactly today would "
                                + "be a rate over a zero-length period.");
            }
            dates[i] = pillar.getKey();
            times[i] = dayCount.yearFraction(referenceDate, pillar.getKey());
            rates[i] = pillar.getValue();
            i++;
        }
    }

    public static Builder builder(LocalDate referenceDate) {
        return new Builder(referenceDate);
    }

    /**
     * A curve with one rate at every horizon - the assumption every pricer made before curves
     * existed, now expressed in the same type as a real curve rather than beside it.
     */
    public static YieldCurve flat(LocalDate referenceDate, double zeroRate) {
        return builder(referenceDate).pillar(Tenor.years(1), zeroRate).build();
    }

    // ------------------------------------------------------------------ reads

    /**
     * The continuously-compounded zero rate for a horizon of {@code years}.
     *
     * @throws IllegalArgumentException if {@code years} is negative or not finite
     */
    public double zeroRate(double years) {
        requireHorizon(years);
        if (years <= times[0]) {
            return rates[0];
        }
        int last = times.length - 1;
        if (years >= times[last]) {
            return rates[last];
        }
        int upper = indexAbove(years);
        return interpolation.zeroRateBetween(
                times[upper - 1], rates[upper - 1], times[upper], rates[upper], years);
    }

    /** The discount factor for a horizon of {@code years}: what one unit then is worth now. */
    public double discountFactor(double years) {
        return Math.exp(-zeroRate(years) * years);
    }

    /**
     * The discount factor for a date, measured from the curve's reference date.
     *
     * @throws DateBeforeCurveException if {@code date} precedes the reference date
     */
    public double discountFactor(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (date.isBefore(referenceDate)) {
            throw new DateBeforeCurveException(date, referenceDate);
        }
        return discountFactor(dayCount.yearFraction(referenceDate, date));
    }

    /**
     * The continuously-compounded forward rate covering {@code from} to {@code to}.
     *
     * <pre>
     *   f(t1, t2) = [ r(t2) x t2 - r(t1) x t1 ] / (t2 - t1)
     * </pre>
     *
     * <p>This is the rate implied by the curve for borrowing between two future dates, and it
     * is what a floating coupon will be projected from at M6. It is also the sharpest test of
     * an interpolation scheme: forwards are a difference of nearby curve points divided by a
     * small number, so a scheme that looks fine plotted as zero rates can look ragged here.
     *
     * @throws IllegalArgumentException unless {@code 0 <= from < to}
     */
    public double forwardRate(double from, double to) {
        requireHorizon(from);
        requireHorizon(to);
        if (!(to > from)) {
            throw new IllegalArgumentException(
                    "A forward rate needs a period, but was asked for " + from + " to " + to
                            + " years. The end must be strictly after the start.");
        }
        return (zeroRate(to) * to - zeroRate(from) * from) / (to - from);
    }

    /**
     * The <b>simple</b> forward rate an index would fix at for the period {@code start} to
     * {@code end}, accrued on {@code dayCount}.
     *
     * <pre>
     *   L = ( DF(start) / DF(end) - 1 ) / tau
     * </pre>
     *
     * <h2>Not the same number as {@link #forwardRate}</h2>
     * That method returns the continuously-compounded forward, which is the curve describing
     * itself. This one returns the rate a floating coupon actually accrues at, and the two
     * differ by the compounding convention and by the day count - for a three-month period at
     * 5% they are about four basis points apart.
     *
     * <p>The distinction is worth stating loudly because using the wrong one breaks a
     * property that is otherwise exact. Discounting a coupon of
     * {@code N x L x tau} paid at {@code end} gives
     *
     * <pre>
     *   N x ( DF(start)/DF(end) - 1 ) x DF(end)  =  N x ( DF(start) - DF(end) )
     * </pre>
     *
     * so the tau and the day count cancel, and a floating leg's present value telescopes to
     * {@code N x (DF(first start) - DF(final end))} - independent of how often it pays. That
     * identity is what makes a par swap worth exactly zero. Project with the continuous
     * forward instead and the cancellation fails, a swap struck at the curve's own par rate
     * comes out worth something, and the error looks like a small modelling imprecision rather
     * than the convention mistake it is.
     *
     * @throws IllegalArgumentException if {@code end} is not after {@code start}
     */
    public double simpleForwardRate(LocalDate start, LocalDate end, DayCountConvention dayCount) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(dayCount, "dayCount");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(
                    "An accrual period needs a length, but was asked for " + start + " to " + end
                            + ". The end must be strictly after the start.");
        }
        double accrualFraction = dayCount.yearFraction(start, end);
        return (discountFactor(start) / discountFactor(end) - 1.0) / accrualFraction;
    }

    /** A new curve with {@code date} added, or replaced if the curve already has that pillar. */
    public YieldCurve withPillar(LocalDate date, double zeroRate) {
        Objects.requireNonNull(date, "date");
        requireFiniteRate(date, zeroRate);
        Map<LocalDate, Double> updated = new LinkedHashMap<>(pillars());
        updated.put(date, zeroRate);
        return new YieldCurve(referenceDate, dayCount, interpolation, updated);
    }

    /** The pillars this curve was built from, in date order. */
    public Map<LocalDate, Double> pillars() {
        Map<LocalDate, Double> view = new LinkedHashMap<>(dates.length);
        for (int i = 0; i < dates.length; i++) {
            view.put(dates[i], rates[i]);
        }
        return Collections.unmodifiableMap(view);
    }

    /** The year fraction this curve measures from its reference date to {@code date}. */
    public double timeTo(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return dayCount.yearFraction(referenceDate, date);
    }

    public LocalDate referenceDate() {
        return referenceDate;
    }

    public Interpolation interpolation() {
        return interpolation;
    }

    public int size() {
        return dates.length;
    }

    /** True if every pillar carries the same rate, so the curve is flat by construction. */
    public boolean isFlat() {
        for (int i = 1; i < rates.length; i++) {
            if (rates[i] != rates[0]) {
                return false;
            }
        }
        return true;
    }

    /** Index of the first pillar strictly beyond {@code years}. Only called when one exists. */
    private int indexAbove(double years) {
        int low = 0;
        int high = times.length - 1;
        while (low < high) {
            int mid = low + (high - low) / 2;
            if (times[mid] <= years) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private static void requireHorizon(double years) {
        if (!Double.isFinite(years) || years < 0) {
            throw new IllegalArgumentException(
                    "A curve horizon must be a finite number of years and not negative, but was "
                            + years + ". Discounting a date in the past is a caller error: a "
                            + "cashflow already paid is not part of an instrument's value.");
        }
    }

    private static void requireFiniteRate(LocalDate date, double zeroRate) {
        if (!Double.isFinite(zeroRate)) {
            throw new IllegalArgumentException(
                    "Zero rate at " + date + " must be finite, but was " + zeroRate);
        }
    }

    @Override
    public String toString() {
        return "YieldCurve(" + referenceDate + ", " + dates.length + " pillars, "
                + interpolation + ")";
    }

    /** Accumulates pillars, then freezes them. */
    public static final class Builder {

        private final LocalDate referenceDate;
        private final Map<LocalDate, Double> pillars = new LinkedHashMap<>();
        private Interpolation interpolation = Interpolation.LOG_LINEAR_DISCOUNT;

        /**
         * Fixed at ACT/365F rather than configurable. Every pricer in the engine measures a
         * year that way, and a curve whose own time axis disagreed with the models reading it
         * would put its pillars in the wrong places by a few days - small, invisible, and
         * wrong. There is no caller wanting anything else; if one appears, this becomes a
         * setter then.
         */
        private final DayCountConvention dayCount = DayCountConvention.ACT_365F;

        private Builder(LocalDate referenceDate) {
            this.referenceDate = Objects.requireNonNull(referenceDate, "referenceDate");
        }

        /**
         * Defaults to {@link Interpolation#LOG_LINEAR_DISCOUNT}, because a curve exists to be
         * asked for forward rates and that is the scheme which keeps them well behaved.
         */
        public Builder interpolation(Interpolation interpolation) {
            this.interpolation = Objects.requireNonNull(interpolation, "interpolation");
            return this;
        }

        public Builder pillar(LocalDate date, double zeroRate) {
            Objects.requireNonNull(date, "date");
            requireFiniteRate(date, zeroRate);
            pillars.put(date, zeroRate);
            return this;
        }

        /**
         * A pillar quoted the way a market quotes one, resolved against the reference date.
         *
         * <p>Unadjusted: the tenor date is taken as it falls, weekend or not. That is right for
         * a curve someone is describing by hand, and wrong for one fitted to instruments -
         * which is why {@link CurveInstrument} supplies its own pillar date rather than a
         * tenor. See the class javadoc for what that distinction cost to learn.
         */
        public Builder pillar(Tenor tenor, double zeroRate) {
            Objects.requireNonNull(tenor, "tenor");
            return pillar(tenor.addTo(referenceDate), zeroRate);
        }

        public YieldCurve build() {
            if (pillars.isEmpty()) {
                throw new IllegalArgumentException(
                        "A yield curve needs at least one pillar. An empty curve would have to "
                                + "invent a rate for every horizon, which is the silent wrong "
                                + "answer this type exists to prevent.");
            }
            return new YieldCurve(referenceDate, dayCount, interpolation, pillars);
        }
    }

    /** Raised when a curve is asked to discount a date it cannot see. */
    public static final class DateBeforeCurveException extends MercuryException {
        DateBeforeCurveException(LocalDate date, LocalDate referenceDate) {
            super("Cannot discount " + date + " on a curve referenced at " + referenceDate
                    + ". A date in the past has no discount factor - it has already happened, "
                    + "and compounding it forward would inflate a cashflow that was settled.");
        }
    }
}
