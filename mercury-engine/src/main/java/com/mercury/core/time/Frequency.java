package com.mercury.core.time;

/**
 * How often an instrument pays: annually, semi-annually, quarterly or monthly.
 *
 * <p>Every constant divides evenly into twelve months, which is what makes a schedule
 * generated backwards from maturity land on the same day of the month each period.
 * Frequencies that do not divide twelve (a four-monthly coupon, say) are not modelled;
 * they are vanishingly rare and would complicate {@link ScheduleGenerator} for no gain.
 */
public enum Frequency {

    ANNUAL(12, 1),
    SEMI_ANNUAL(6, 2),
    QUARTERLY(3, 4),
    MONTHLY(1, 12);

    private final int monthsPerPeriod;
    private final int periodsPerYear;

    Frequency(int monthsPerPeriod, int periodsPerYear) {
        this.monthsPerPeriod = monthsPerPeriod;
        this.periodsPerYear = periodsPerYear;
    }

    /** Months between consecutive payments. */
    public int monthsPerPeriod() {
        return monthsPerPeriod;
    }

    /** Payments per year. The divisor turning an annual coupon rate into a period rate. */
    public int periodsPerYear() {
        return periodsPerYear;
    }

    /**
     * Splits an annual rate into the rate for one period.
     *
     * <p>Simple division, which is the market convention for coupon accrual - a 6%
     * semi-annual bond pays 3% twice, not {@code (1.06)^0.5 - 1}. Compounding is a
     * separate concern belonging to curve construction, not to coupon calculation.
     */
    public double periodRate(double annualRate) {
        return annualRate / periodsPerYear;
    }
}
