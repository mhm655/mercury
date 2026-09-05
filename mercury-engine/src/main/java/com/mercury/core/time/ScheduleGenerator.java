package com.mercury.core.time;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Builds a payment {@link Schedule} from an instrument's terms.
 *
 * <h2>Why this is written once</h2>
 * Bonds, both legs of an interest-rate swap and the cashflows of an FX forward all need
 * the same timetable logic. Writing it three times would be three chances to get the
 * month-end and stub handling subtly different, which produces instruments that disagree
 * about when they pay. This is the single implementation they all consume.
 *
 * <h2>Why the schedule is generated backwards from maturity</h2>
 * This is the market convention, and it is not arbitrary. Generating forwards from the
 * effective date would leave any odd remaining period - the "stub" - at the <em>end</em>,
 * next to maturity. But maturity is the date that is contractually fixed and around which
 * the final principal payment settles, so the regular periods must line up with it. Any
 * irregularity therefore belongs at the <em>front</em>, where it is a short first coupon.
 *
 * <p>A concrete case: a bond issued 15 February 2024 maturing 30 June 2029 paying
 * semi-annually. Rolling backwards from 30 June gives coupons on 30 June and 30 December
 * of each year - all landing on the 30th, matching maturity - and a short first period
 * from 15 February to 30 June 2024. Rolling forwards would have produced coupons on the
 * 15th of each month with a stub at the end, so the final coupon and the principal
 * repayment would fall on different dates. That is wrong.
 *
 * <h2>Adjusted and unadjusted dates</h2>
 * Dates are generated unadjusted, then rolled. Crucially, each period's <em>schedule</em>
 * dates are rolled independently from the unadjusted sequence rather than by chaining off
 * the previous adjusted date - otherwise a single weekend early in a thirty-year swap
 * would shift every subsequent date, and the schedule would drift away from the 30th of
 * the month.
 *
 * <p>Stateless and thread-safe.
 */
public final class ScheduleGenerator {

    private ScheduleGenerator() {
    }

    /**
     * Generates a schedule for {@code [effectiveDate, maturityDate]}.
     *
     * <p>Accrual boundaries are rolled with {@code convention} against {@code calendar};
     * payment dates equal the rolled accrual end. The first period may be short (a front
     * stub) when the tenor does not divide evenly into the term.
     *
     * @throws IllegalArgumentException if maturity does not follow the effective date
     */
    public static Schedule generate(LocalDate effectiveDate,
                                    LocalDate maturityDate,
                                    Frequency frequency,
                                    BusinessDayConvention convention,
                                    HolidayCalendar calendar) {
        Objects.requireNonNull(effectiveDate, "effectiveDate");
        Objects.requireNonNull(maturityDate, "maturityDate");
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(convention, "convention");
        Objects.requireNonNull(calendar, "calendar");
        if (!maturityDate.isAfter(effectiveDate)) {
            throw new IllegalArgumentException(
                    "Maturity " + maturityDate + " must be after effective date " + effectiveDate);
        }

        List<LocalDate> unadjusted = unadjustedBoundariesBackwardFrom(
                effectiveDate, maturityDate, frequency);

        List<SchedulePeriod> periods = new ArrayList<>(unadjusted.size() - 1);
        for (int i = 0; i < unadjusted.size() - 1; i++) {
            // Each boundary is rolled from its own unadjusted date, never from the
            // previously adjusted one, so adjustments cannot accumulate.
            LocalDate start = convention.adjust(unadjusted.get(i), calendar);
            LocalDate end = convention.adjust(unadjusted.get(i + 1), calendar);
            periods.add(new SchedulePeriod(start, end, end));
        }
        return Schedule.of(periods);
    }

    /**
     * Period boundaries in chronological order, from the effective date to maturity,
     * stepping backwards from maturity so that any stub lands at the front.
     */
    private static List<LocalDate> unadjustedBoundariesBackwardFrom(LocalDate effectiveDate,
                                                                    LocalDate maturityDate,
                                                                    Frequency frequency) {
        List<LocalDate> boundaries = new ArrayList<>();
        boundaries.add(maturityDate);

        int months = frequency.monthsPerPeriod();
        int step = 1;
        // Always step from maturity rather than from the previous boundary: repeatedly
        // subtracting one month from the 31st would ratchet down to the 28th and stay
        // there, whereas multiplying the offset keeps every date anchored to maturity's
        // day of month.
        LocalDate boundary = maturityDate.minusMonths((long) months * step);
        while (boundary.isAfter(effectiveDate)) {
            boundaries.add(boundary);
            step++;
            boundary = maturityDate.minusMonths((long) months * step);
        }
        boundaries.add(effectiveDate);

        Collections.reverse(boundaries);
        return boundaries;
    }
}
