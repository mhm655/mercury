package com.mercury.core.time;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    ACT_360 {
        @Override
        public Accrual accrual(LocalDate start, LocalDate end) {
            return new Accrual(actualDays(start, end), 360);
        }
    },

    /**
     * Actual/365 Fixed. Actual days elapsed over a fixed 365-day year, leap years
     * included.
     *
     * <p>Standard for GBP and several other markets. "Fixed" means the denominator is
     * always 365 and never 366.
     */
    ACT_365F {
        @Override
        public Accrual accrual(LocalDate start, LocalDate end) {
            return new Accrual(actualDays(start, end), 365);
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
    THIRTY_360_US {
        @Override
        public Accrual accrual(LocalDate start, LocalDate end) {
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
            return new Accrual(days, 360);
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
    ACT_ACT_ISDA {
        @Override
        public Accrual accrual(LocalDate start, LocalDate end) {
            requireOrdered(start, end);
            if (start.equals(end)) {
                return new Accrual(0, 1);
            }
            int startYear = start.getYear();
            int endYear = end.getYear();
            if (startYear == endYear) {
                return new Accrual(actualDays(start, end), daysInYear(startYear));
            }
            long leadingBasis = daysInYear(startYear);
            long trailingBasis = daysInYear(endYear);
            // Leading stub: start -> 1 January of the following year.
            long leadingDays = actualDays(start, LocalDate.of(startYear + 1, 1, 1));
            // Trailing stub: 1 January of the end year -> end.
            long trailingDays = actualDays(LocalDate.of(endYear, 1, 1), end);
            // Whole years in between contribute exactly 1.0 each, by definition.
            long wholeYears = endYear - startYear - 1L;

            // Put the three terms over one denominator rather than summing three quotients,
            // so the result stays an exact fraction. This is the only convention whose answer
            // is a sum, and it is the one where evaluating each part first would round thrice.
            long basis = leadingBasis * trailingBasis;
            long days = leadingDays * trailingBasis
                    + wholeYears * basis
                    + trailingDays * leadingBasis;
            return new Accrual(days, basis);
        }
    };


    /**
     * The accrual factor for {@code [start, end)} as an exact fraction of a year.
     *
     * <p>Every convention here is a count of days over a basis, so the factor is always
     * rational and this hands it back without evaluating the division. See {@link Accrual}
     * for why that matters to a cashflow.
     *
     * @param start inclusive period start
     * @param end   exclusive period end; must not precede {@code start}
     * @throws IllegalArgumentException if {@code end} precedes {@code start}
     */
    public abstract Accrual accrual(LocalDate start, LocalDate end);

    /**
     * The accrual factor as a {@code double} - the model-domain form, for discounting and
     * everything else that is approximate anyway.
     *
     * <p>Derived from {@link #accrual} rather than computed alongside it, so the exact and
     * the approximate answer cannot drift apart. Each convention is defined once.
     *
     * @return a non-negative year fraction, {@code 0.0} when the dates are equal
     */
    public final double yearFraction(LocalDate start, LocalDate end) {
        return accrual(start, end).toDouble();
    }

    /**
     * A year fraction as the exact ratio it is: {@code days} over {@code basis}.
     *
     * <h2>Why a fraction and not a number</h2>
     * ADR 0001 splits the engine into exact ledger amounts and approximate model output, and a
     * coupon sits on the exact side - it is money that changes hands. But a year fraction is
     * usually not representable: 11/360 is 0.0305555... in binary or decimal alike, so
     * evaluating it first and multiplying afterwards puts a rounding error underneath the
     * whole cashflow.
     *
     * <p>That is not theoretical. Mercury's own demo bond accrues 1000 x 4.5% x 11/360, which
     * is exactly 1.375 and rounds half-even to <b>1.38</b>. Routed through a {@code double}
     * year fraction the product came to 1.37499999999999997500 and the report printed
     * <b>1.37</b> - a cent low, decided entirely by which way the binary approximation of
     * 11/360 happened to fall.
     *
     * <p>Keeping the numerator and the denominator apart lets a caller divide <em>last</em>,
     * at the currency's own scale, so the only rounding in a coupon is the one that belongs
     * there. {@link #toDouble} serves everyone whose answer was approximate anyway.
     */
    public record Accrual(long days, long basis) {

        public Accrual {
            if (basis <= 0) {
                throw new IllegalArgumentException(
                        "An accrual basis is a count of days in a year and must be positive, "
                                + "but was " + basis);
            }
        }

        /** The model-domain value: approximate, and right for anything being discounted. */
        public double toDouble() {
            return (double) days / basis;
        }

        /**
         * {@code amount x days / basis}, rounded once to {@code scale}.
         *
         * <p>The division happens here, after the multiplication - which is the entire reason
         * the fraction is carried this far instead of being evaluated at the source.
         */
        public BigDecimal applyTo(BigDecimal amount, int scale) {
            return amount.multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(basis), scale, RoundingMode.HALF_EVEN);
        }
    }


    static long actualDays(LocalDate start, LocalDate end) {
        requireOrdered(start, end);
        return ChronoUnit.DAYS.between(start, end);
    }

    private static long daysInYear(int year) {
        return java.time.Year.isLeap(year) ? 366L : 365L;
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
