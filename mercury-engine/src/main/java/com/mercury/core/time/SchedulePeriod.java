package com.mercury.core.time;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One accrual period of a payment schedule.
 *
 * <h2>Why three dates and not two</h2>
 * The period a cashflow <em>accrues</em> over and the date it is <em>paid</em> are not
 * the same thing, and conflating them is a real source of pricing error. Interest accrues
 * over {@code [accrualStart, accrualEnd)}; the money moves on {@code paymentDate}, which
 * is the accrual end rolled to a business day, and which for some instruments is
 * deliberately days later.
 *
 * <p>This matters twice over: the accrual dates drive the day-count fraction and so the
 * cashflow's size, while the payment date drives which discount factor applies and so its
 * present value. A schedule that carried only one date would have to choose which of
 * those to get wrong.
 *
 * <p>Immutable and thread-safe.
 */
public record SchedulePeriod(LocalDate accrualStart, LocalDate accrualEnd, LocalDate paymentDate) {

    public SchedulePeriod {
        Objects.requireNonNull(accrualStart, "accrualStart");
        Objects.requireNonNull(accrualEnd, "accrualEnd");
        Objects.requireNonNull(paymentDate, "paymentDate");
        if (!accrualEnd.isAfter(accrualStart)) {
            throw new IllegalArgumentException(
                    "Accrual end " + accrualEnd + " must be after accrual start " + accrualStart);
        }
        if (paymentDate.isBefore(accrualEnd)) {
            throw new IllegalArgumentException(
                    "Payment date " + paymentDate + " precedes accrual end " + accrualEnd
                            + ". Interest is not paid before it has accrued.");
        }
    }

    /** The accrual factor for this period under {@code convention}. */
    public double yearFraction(DayCountConvention convention) {
        return convention.yearFraction(accrualStart, accrualEnd);
    }

    /** True if this period is still to be paid as of {@code valuationDate}. */
    public boolean isUnpaidAsOf(LocalDate valuationDate) {
        return paymentDate.isAfter(valuationDate);
    }

    @Override
    public String toString() {
        return "[" + accrualStart + " -> " + accrualEnd + ", pay " + paymentDate + "]";
    }
}
