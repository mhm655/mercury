package com.mercury.core.time;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * How a period between two dates is converted into a year fraction for interest accrual.
 *
 * <h2>Why this exists</h2>
 * "Three months of interest" is not a single number. A 5% annual coupon accrued from
 * 15 January to 15 April is 1.2329% under ACT/365F, 1.2500% under ACT/360 and 1.2500%
 * under 30/360 - and on a 100 million notional those differences are real money. The
 * convention is a property of the instrument, agreed in its terms, not a global setting.
 *
 * <h2>Why an enum with behaviour</h2>
 * These are a small, closed, well-known set that will not grow at runtime, and each
 * differs only in one calculation. An enum whose constants override a single method
 * gives polymorphic dispatch with no class hierarchy, no factory and no registration -
 * and lets {@code switch} be checked for exhaustiveness. A {@code DayCountConvention}
 * interface with five implementation classes would be the same behaviour with more
 * ceremony and less compiler help.
 *
 * <p>All implementations are stateless and thread-safe.
 */
public enum DayCountConvention {

    /**
     * Actual/360. Actual days elapsed over a 360-day year.
     *
     * <p>The money-market convention: USD and EUR deposits, FRAs, and the floating leg of
     * most swaps. Note it yields slightly more than a "true" year fraction, since a real
     * year has 365 days but is divided by 360.
     */
    ACT_360("Actual/360") {
        @Override
        public double yearFraction(LocalDate start, LocalDate end) {
            return actualDays(start, end) / 360.0;
        }
    },

    /**
     * Actual/365 Fixed. Actual days elapsed over a fixed 365-day year, leap years
     * included.
     *
     * <p>Standard for GBP and several other markets. "Fixed" means the denominator is
     * always 365 and never 366.
     */
    ACT_365F("Actual/365 Fixed") {
        @Override
        public double yearFraction(LocalDate start, LocalDate end) {
            return actualDays(start, end) / 365.0;
        }
    },

    /**
     * 30/360 US (Bond Basis). Every month counts as 30 days, every year as 360.
     *
     * <p>The US corporate and municipal bond convention. Because each period is exactly
     * 30 days, a semi-annual coupon is always precisely half the annual coupon, which is
     * the property it was invented for. The two end-of-month adjustments below are part
     * of the definition, not an approximation:
     *
     * <ol>
     *   <li>If the start day is 31, treat it as 30.</li>
     *   <li>If the end day is 31 <em>and</em> the start day is 30 or 31, treat the end
     *       day as 30. The condition matters: from the 30th to a 31st is 30 days, but
     *       from the 29th to a 31st is 32.</li>
     * </ol>
     */
    THIRTY_360_US("30/360 US (Bond Basis)") {
        @Override
        public double yearFraction(LocalDate start, LocalDate end) {
            requireOrdered(start, end);
            int d1 = start.getDayOfMonth();
            int d2 = end.getDayOfMonth();
            if (d1 == 31) {
                d1 = 30;
            }
            if (d2 == 31 && d1 == 30) {
                d2 = 30;
            }
            int days = 360 * (end.getYear() - start.getYear())
                    + 30 * (end.getMonthValue() - start.getMonthValue())
                    + (d2 - d1);
            return days / 360.0;
        }
    },

    /**
     * Actual/Actual ISDA. Days falling in a leap year are divided by 366, days in a
     * non-leap year by 365, and the two parts are summed.
     *
     * <p>The most accurate of the four and the ISDA standard for many swaps. It is the
     * only one here whose result depends on <em>which</em> years a period spans rather
     * than just its length, so a period is split at each year boundary.
     */
    ACT_ACT_ISDA("Actual/Actual ISDA") {
        @Override
        public double yearFraction(LocalDate start, LocalDate end) {
            requireOrdered(start, end);
            if (start.equals(end)) {
                return 0.0;
            }
            int startYear = start.getYear();
            int endYear = end.getYear();
            if (startYear == endYear) {
                return actualDays(start, end) / daysInYear(startYear);
            }
            // Leading stub: start -> 1 January of the following year.
            double total = actualDays(start, LocalDate.of(startYear + 1, 1, 1)) / daysInYear(startYear);
            // Whole years in between contribute exactly 1.0 each, by definition.
            total += endYear - startYear - 1;
            // Trailing stub: 1 January of the end year -> end.
            total += actualDays(LocalDate.of(endYear, 1, 1), end) / daysInYear(endYear);
            return total;
        }
    };

    private final String displayName;

    DayCountConvention(String displayName) {
        this.displayName = displayName;
    }

    /**
     * The accrual factor for {@code [start, end)} as a fraction of a year.
     *
     * @param start inclusive period start
     * @param end   exclusive period end; must not precede {@code start}
     * @return a non-negative year fraction, {@code 0.0} when the dates are equal
     * @throws IllegalArgumentException if {@code end} precedes {@code start}
     */
    public abstract double yearFraction(LocalDate start, LocalDate end);

    public String displayName() {
        return displayName;
    }

    static long actualDays(LocalDate start, LocalDate end) {
        requireOrdered(start, end);
        return ChronoUnit.DAYS.between(start, end);
    }

    private static double daysInYear(int year) {
        return java.time.Year.isLeap(year) ? 366.0 : 365.0;
    }

    static void requireOrdered(LocalDate start, LocalDate end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException(
                    "Period end " + end + " precedes start " + start
                            + ". Day count conventions are defined on forward periods only.");
        }
    }
}
