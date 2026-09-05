package com.mercury.core.time;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A period of time as the market quotes it: {@code 3M}, {@code 10Y}, {@code 2W}.
 *
 * <h2>Why not java.time.Period</h2>
 * {@link java.time.Period} carries years, months <em>and</em> days at once, so it can
 * represent "1 year 2 months 3 days" - which no market instrument is ever quoted in. A
 * tenor is always a single count of a single unit, and market data is keyed by it: the
 * 3M point on a curve, the 10Y swap rate. Restricting the type to one unit means
 * {@code Tenor} can be a map key with an obvious equality, and that {@code "3M"} parses
 * and prints round-trip.
 *
 * <h2>Ordering</h2>
 * Tenors sort by approximate length in days, which is what curve pillars need. The
 * approximation only affects tenors that are close in length but expressed in different
 * units - 4W versus 1M, say - and no curve is built with both. Exact calendar arithmetic
 * still happens in {@link #addTo(LocalDate)}, which uses real date maths, not the
 * approximation.
 *
 * <p>Immutable and thread-safe.
 */
public record Tenor(int amount, TenorUnit unit) implements Comparable<Tenor> {

    public Tenor {
        Objects.requireNonNull(unit, "unit");
        if (amount <= 0) {
            throw new IllegalArgumentException(
                    "Tenor amount must be positive, but was " + amount
                            + ". A zero or negative tenor has no market meaning.");
        }
    }

    public static Tenor of(int amount, TenorUnit unit) {
        return new Tenor(amount, unit);
    }

    public static Tenor days(int amount) {
        return new Tenor(amount, TenorUnit.DAY);
    }

    public static Tenor weeks(int amount) {
        return new Tenor(amount, TenorUnit.WEEK);
    }

    public static Tenor months(int amount) {
        return new Tenor(amount, TenorUnit.MONTH);
    }

    public static Tenor years(int amount) {
        return new Tenor(amount, TenorUnit.YEAR);
    }

    /**
     * Parses the market shorthand: {@code "1D"}, {@code "2W"}, {@code "3M"}, {@code "10Y"}.
     * Case-insensitive and tolerant of surrounding whitespace.
     */
    public static Tenor parse(String text) {
        Objects.requireNonNull(text, "text");
        String trimmed = text.trim().toUpperCase();
        if (trimmed.length() < 2) {
            throw new IllegalArgumentException(
                    "Tenor must be a number followed by D, W, M or Y, e.g. \"3M\", but was: " + text);
        }
        char suffix = trimmed.charAt(trimmed.length() - 1);
        String digits = trimmed.substring(0, trimmed.length() - 1);
        int amount;
        try {
            amount = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Tenor \"" + text + "\" does not start with a whole number", e);
        }
        return new Tenor(amount, TenorUnit.fromSuffix(suffix));
    }

    /** Advances {@code date} by this tenor using exact calendar arithmetic. */
    public LocalDate addTo(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return switch (unit) {
            case DAY -> date.plusDays(amount);
            case WEEK -> date.plusWeeks(amount);
            case MONTH -> date.plusMonths(amount);
            case YEAR -> date.plusYears(amount);
        };
    }

    /** Moves {@code date} backwards by this tenor. */
    public LocalDate subtractFrom(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return switch (unit) {
            case DAY -> date.minusDays(amount);
            case WEEK -> date.minusWeeks(amount);
            case MONTH -> date.minusMonths(amount);
            case YEAR -> date.minusYears(amount);
        };
    }

    /**
     * Nominal length in days, for ordering and rough comparison only.
     * Never use this for accrual - that is {@link DayCountConvention}'s job.
     */
    public int approximateDays() {
        return amount * unit.approximateDays();
    }

    @Override
    public int compareTo(Tenor other) {
        return Integer.compare(approximateDays(), other.approximateDays());
    }

    @Override
    public String toString() {
        return amount + unit.suffix();
    }

    /** The unit a tenor is quoted in. */
    public enum TenorUnit {
        DAY('D', 1),
        WEEK('W', 7),
        MONTH('M', 30),
        YEAR('Y', 365);

        private final char suffix;
        private final int approximateDays;

        TenorUnit(char suffix, int approximateDays) {
            this.suffix = suffix;
            this.approximateDays = approximateDays;
        }

        public String suffix() {
            return String.valueOf(suffix);
        }

        int approximateDays() {
            return approximateDays;
        }

        static TenorUnit fromSuffix(char c) {
            for (TenorUnit u : values()) {
                if (u.suffix == c) {
                    return u;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown tenor unit '" + c + "'. Expected one of D, W, M, Y.");
        }
    }
}
