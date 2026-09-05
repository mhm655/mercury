package com.mercury.core.time;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Decides whether a given date is a business day in some financial centre.
 *
 * <h2>Why this is an interface and not a class</h2>
 * Calendars compose. A cross-currency trade settles only on a day that is a business day
 * in <em>both</em> centres - a EUR/USD forward maturing on US Independence Day does not
 * settle, even though Frankfurt is open. That union is naturally expressed by combining
 * two calendars ({@link #and}), which needs an interface and a composite implementation
 * rather than a single class with a flag.
 *
 * <p>This is the Composite pattern used because the domain genuinely has a tree, not to
 * demonstrate the pattern: callers treat a single centre and a union of five centres
 * identically, and neither knows which it holds.
 *
 * <p>Implementations must be immutable and thread-safe; the risk engine queries calendars
 * from many worker threads at once.
 */
@FunctionalInterface
public interface HolidayCalendar {

    /** True if markets in this centre are closed on {@code date}. */
    boolean isHoliday(LocalDate date);

    /** True if markets are open: not a holiday. */
    default boolean isBusinessDay(LocalDate date) {
        return !isHoliday(date);
    }

    /**
     * A calendar closed whenever either this or {@code other} is closed.
     *
     * <p>The union, not the intersection: settlement requires <em>all</em> relevant
     * centres to be open, so a holiday anywhere blocks the date.
     */
    default HolidayCalendar and(HolidayCalendar other) {
        Objects.requireNonNull(other, "other");
        HolidayCalendar self = this;
        return date -> self.isHoliday(date) || other.isHoliday(date);
    }

    /** The next business day strictly after {@code date}. */
    default LocalDate nextBusinessDay(LocalDate date) {
        LocalDate d = date.plusDays(1);
        while (isHoliday(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** The last business day strictly before {@code date}. */
    default LocalDate previousBusinessDay(LocalDate date) {
        LocalDate d = date.minusDays(1);
        while (isHoliday(d)) {
            d = d.minusDays(1);
        }
        return d;
    }

    /**
     * Moves {@code count} business days from {@code date}, skipping closed days.
     *
     * <p>This is how settlement dates are derived: T+2 is
     * {@code addBusinessDays(tradeDate, 2)}, not {@code tradeDate.plusDays(2)}. A trade
     * on Thursday settles Monday, not Saturday.
     *
     * @param count business days to move; negative moves backwards, zero returns
     *              {@code date} unchanged even if it is a holiday
     */
    default LocalDate addBusinessDays(LocalDate date, int count) {
        LocalDate d = date;
        if (count > 0) {
            for (int i = 0; i < count; i++) {
                d = nextBusinessDay(d);
            }
        } else if (count < 0) {
            for (int i = 0; i < -count; i++) {
                d = previousBusinessDay(d);
            }
        }
        return d;
    }

    /**
     * Weekends only, no public holidays.
     *
     * <p>The sensible default for a simulation, and honest about its limits: it will
     * happily settle a USD trade on Thanksgiving. Where a test or scenario needs real
     * holidays, {@link #of} supplies them.
     */
    static HolidayCalendar weekendsOnly() {
        return date -> {
            DayOfWeek day = date.getDayOfWeek();
            return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
        };
    }

    /** A calendar with no closed days at all. Useful in tests that isolate other behaviour. */
    static HolidayCalendar alwaysOpen() {
        return date -> false;
    }

    /** Weekends plus an explicit set of public holidays. */
    static HolidayCalendar of(Collection<LocalDate> publicHolidays) {
        Set<LocalDate> holidays = Set.copyOf(publicHolidays);
        HolidayCalendar weekends = weekendsOnly();
        return date -> weekends.isHoliday(date) || holidays.contains(date);
    }

    /** Convenience overload of {@link #of(Collection)}. */
    static HolidayCalendar of(LocalDate... publicHolidays) {
        return of(List.of(publicHolidays));
    }
}
