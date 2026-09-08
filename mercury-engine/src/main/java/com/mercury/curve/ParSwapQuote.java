package com.mercury.curve;

import com.mercury.core.time.BusinessDayConvention;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.Frequency;
import com.mercury.core.time.HolidayCalendar;
import com.mercury.core.time.Schedule;
import com.mercury.core.time.SchedulePeriod;
import com.mercury.core.time.ScheduleGenerator;
import com.mercury.core.time.Tenor;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A par interest-rate swap rate: the fixed rate at which a swap of this tenor is worth zero.
 *
 * <h2>What builds the long end of a curve</h2>
 * Beyond a year the market quotes swaps rather than deposits, and a swap rate is a quote the
 * bootstrapper has to solve rather than invert. A par swap exchanges a fixed leg for a
 * floating one and, at inception, the two are worth the same:
 *
 * <pre>
 *   S x sum( tau_i x DF(t_i) )  =  1 - DF(T_n)
 * </pre>
 *
 * The left side is the fixed leg: the par rate times the annuity. The right side is the
 * floating leg, which under single-curve discounting telescopes to exactly that - every
 * projected coupon is the forward rate implied by the same discount factors, so the sum
 * collapses and only the first and last factors survive. That identity is worth knowing
 * because it is why a curve can be built from swaps without ever projecting a coupon.
 *
 * <p>The repricing error is therefore the fixed leg minus the floating leg, which is the
 * present value of entering the swap - zero exactly when the curve agrees with the quote.
 *
 * <h2>Why this one needs root-finding and a deposit does not</h2>
 * A deposit has a single payment, so its discount factor inverts in one line. A swap pays
 * throughout its life. The coupons before the final pillar discount on parts of the curve
 * already built - but the ones falling <em>between</em> the previous pillar and this one are
 * interpolated, and the interpolation depends on the pillar being solved for. The unknown
 * appears on both sides through the interpolator, so there is nothing to rearrange, and the
 * honest answer is to solve numerically. That is the whole reason
 * {@link com.mercury.core.math.RootFinder} exists in this project.
 *
 * <h2>Single-curve, and named as such</h2>
 * The telescoping identity above assumes the curve used to project the floating leg is the
 * same one used to discount it. Real desks have discounted on OIS and projected on a separate
 * index curve since 2008, and the difference - the basis - is itself a quoted market. Mercury
 * is single-curve, which is the pre-2008 convention, and says so in {@code KNOWN_GAPS.md}
 * rather than quietly assuming the basis is zero.
 *
 * <p>Immutable and thread-safe.
 */
public record ParSwapQuote(
        Tenor tenor,
        double parRate,
        Frequency fixedFrequency,
        DayCountConvention fixedDayCount,
        BusinessDayConvention businessDayConvention,
        HolidayCalendar calendar) implements CurveInstrument {

    public ParSwapQuote {
        Objects.requireNonNull(tenor, "tenor");
        Objects.requireNonNull(fixedFrequency, "fixedFrequency");
        Objects.requireNonNull(fixedDayCount, "fixedDayCount");
        Objects.requireNonNull(businessDayConvention, "businessDayConvention");
        Objects.requireNonNull(calendar, "calendar");
        if (!Double.isFinite(parRate)) {
            throw new IllegalArgumentException(
                    "Par swap rate at " + tenor + " must be finite, but was " + parRate);
        }
    }

    /**
     * A swap on the USD market convention: semi-annual fixed leg, 30/360, modified following
     * over weekends.
     */
    public static ParSwapQuote of(Tenor tenor, double parRate) {
        return new ParSwapQuote(tenor, parRate, Frequency.SEMI_ANNUAL,
                DayCountConvention.THIRTY_360_US, BusinessDayConvention.MODIFIED_FOLLOWING,
                HolidayCalendar.weekendsOnly());
    }

    /**
     * The date the last fixed coupon and the floating leg both settle.
     *
     * <p>Taken from the generated schedule rather than computed as {@code reference + tenor},
     * because those are not the same date whenever the nominal one falls on a weekend - the
     * distinction that broke the first bootstrapper. See {@link CurveInstrument}.
     */
    @Override
    public LocalDate pillarDate(LocalDate referenceDate) {
        return schedule(referenceDate).last().paymentDate();
    }

    @Override
    public double repricingError(YieldCurve curve) {
        Objects.requireNonNull(curve, "curve");
        Schedule schedule = schedule(curve.referenceDate());

        double annuity = 0.0;
        for (SchedulePeriod period : schedule.periods()) {
            annuity += period.yearFraction(fixedDayCount)
                    * curve.discountFactor(period.paymentDate());
        }
        double fixedLeg = parRate * annuity;
        double floatingLeg = 1.0 - curve.discountFactor(schedule.last().paymentDate());

        return fixedLeg - floatingLeg;
    }

    private Schedule schedule(LocalDate referenceDate) {
        Objects.requireNonNull(referenceDate, "referenceDate");
        return ScheduleGenerator.generate(referenceDate, tenor.addTo(referenceDate),
                fixedFrequency, businessDayConvention, calendar);
    }

    @Override
    public String describe() {
        return "par swap " + tenor + " @ " + parRate;
    }
}
