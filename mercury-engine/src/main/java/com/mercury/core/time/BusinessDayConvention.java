package com.mercury.core.time;

import java.time.LocalDate;
import java.util.Objects;

/**
 * How a scheduled date that falls on a non-business day is rolled to a business day.
 *
 * <h2>Why this exists</h2>
 * A bond paying semi-annual coupons on the 15th will sooner or later land on a Saturday.
 * Money does not move on a Saturday, so the payment date rolls - and the direction it
 * rolls is agreed in the instrument's terms, because it changes both the payment date and
 * the accrual period, and therefore the cashflow.
 *
 * <p>Like {@link DayCountConvention}, this is a small closed set differing only in one
 * calculation, so it is an enum with per-constant behaviour rather than an interface with
 * five classes.
 *
 * <p>All constants are stateless and thread-safe.
 */
public enum BusinessDayConvention {

    /** No adjustment. The date is used as-is even if markets are closed. */
    UNADJUSTED("Unadjusted") {
        @Override
        public LocalDate adjust(LocalDate date, HolidayCalendar calendar) {
            return date;
        }
    },

    /** Roll forward to the next business day. */
    FOLLOWING("Following") {
        @Override
        public LocalDate adjust(LocalDate date, HolidayCalendar calendar) {
            return calendar.isBusinessDay(date) ? date : calendar.nextBusinessDay(date);
        }
    },

    /**
     * Roll forward, unless that would cross into the next calendar month, in which case
     * roll backward instead.
     *
     * <p>By far the most common convention in practice. The month-end guard is the whole
     * point of it: without it, a payment due on Sunday 31 August would move to 1
     * September, pushing a cashflow into the next accrual period and the next month's
     * accounting. Rolling back to Friday 29 August keeps the period intact.
     */
    MODIFIED_FOLLOWING("Modified Following") {
        @Override
        public LocalDate adjust(LocalDate date, HolidayCalendar calendar) {
            if (calendar.isBusinessDay(date)) {
                return date;
            }
            LocalDate rolled = calendar.nextBusinessDay(date);
            return rolled.getMonth() == date.getMonth()
                    ? rolled
                    : calendar.previousBusinessDay(date);
        }
    },

    /** Roll backward to the previous business day. */
    PRECEDING("Preceding") {
        @Override
        public LocalDate adjust(LocalDate date, HolidayCalendar calendar) {
            return calendar.isBusinessDay(date) ? date : calendar.previousBusinessDay(date);
        }
    },

    /**
     * Roll backward, unless that would cross into the previous calendar month, in which
     * case roll forward instead. The mirror of {@link #MODIFIED_FOLLOWING}.
     */
    MODIFIED_PRECEDING("Modified Preceding") {
        @Override
        public LocalDate adjust(LocalDate date, HolidayCalendar calendar) {
            if (calendar.isBusinessDay(date)) {
                return date;
            }
            LocalDate rolled = calendar.previousBusinessDay(date);
            return rolled.getMonth() == date.getMonth()
                    ? rolled
                    : calendar.nextBusinessDay(date);
        }
    };

    private final String displayName;

    BusinessDayConvention(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns {@code date} if it is a business day, otherwise the adjusted date this
     * convention rolls to.
     */
    public abstract LocalDate adjust(LocalDate date, HolidayCalendar calendar);

    /** Null-checking entry point; the abstract overrides assume non-null arguments. */
    public final LocalDate adjustChecked(LocalDate date, HolidayCalendar calendar) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(calendar, "calendar");
        return adjust(date, calendar);
    }

    public String displayName() {
        return displayName;
    }
}
