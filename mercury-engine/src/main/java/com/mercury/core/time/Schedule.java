package com.mercury.core.time;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An ordered, contiguous sequence of accrual periods - the payment timetable of a bond or
 * one leg of a swap.
 *
 * <p>Construction enforces contiguity: each period's accrual start is the previous
 * period's accrual end, with no gaps and no overlaps. A schedule with a gap would silently
 * lose a coupon, and one with an overlap would double-count interest. Both are far easier
 * to catch here than in a pricing result that is merely a bit wrong.
 *
 * <p>Immutable and thread-safe; the period list is defensively copied and unmodifiable.
 */
public final class Schedule {

    private final List<SchedulePeriod> periods;

    private Schedule(List<SchedulePeriod> periods) {
        this.periods = Collections.unmodifiableList(new ArrayList<>(periods));
    }

    /**
     * @throws IllegalArgumentException if empty or not contiguous
     */
    public static Schedule of(List<SchedulePeriod> periods) {
        Objects.requireNonNull(periods, "periods");
        if (periods.isEmpty()) {
            throw new IllegalArgumentException("A schedule needs at least one period");
        }
        for (int i = 1; i < periods.size(); i++) {
            LocalDate previousEnd = periods.get(i - 1).accrualEnd();
            LocalDate thisStart = periods.get(i).accrualStart();
            if (!previousEnd.equals(thisStart)) {
                throw new IllegalArgumentException(
                        "Schedule periods must be contiguous, but period " + (i - 1) + " ends "
                                + previousEnd + " while period " + i + " starts " + thisStart
                                + ". A gap loses a coupon; an overlap double-counts interest.");
            }
        }
        return new Schedule(periods);
    }

    /** The periods, in chronological order. Unmodifiable. */
    public List<SchedulePeriod> periods() {
        return periods;
    }

    public int size() {
        return periods.size();
    }

    public SchedulePeriod first() {
        return periods.get(0);
    }

    public SchedulePeriod last() {
        return periods.get(periods.size() - 1);
    }

    /** Accrual start of the first period. */
    public LocalDate effectiveDate() {
        return first().accrualStart();
    }

    /** Accrual end of the final period. */
    public LocalDate maturityDate() {
        return last().accrualEnd();
    }

    /**
     * Periods whose payment date is still in the future as of {@code valuationDate}.
     *
     * <p>What a discounted-cashflow pricer iterates: a coupon already paid is not part of
     * the instrument's value.
     */
    public List<SchedulePeriod> unpaidPeriodsAsOf(LocalDate valuationDate) {
        Objects.requireNonNull(valuationDate, "valuationDate");
        return periods.stream().filter(p -> p.isUnpaidAsOf(valuationDate)).toList();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Schedule other && periods.equals(other.periods);
    }

    @Override
    public int hashCode() {
        return periods.hashCode();
    }

    @Override
    public String toString() {
        return "Schedule(" + periods.size() + " periods, " + effectiveDate()
                + " -> " + maturityDate() + ")";
    }
}
