package com.mercury.core.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The engine's only source of "now".
 *
 * <h2>Why nothing may call LocalDate.now()</h2>
 * Three of this project's headline properties depend on it:
 *
 * <ul>
 *   <li><b>Reproducibility.</b> The golden-master test replays a whole simulation and
 *       asserts identical output. A hidden clock read makes that impossible - the same
 *       run produces different results tomorrow.</li>
 *   <li><b>Testability.</b> Settling a T+2 trade, or ageing a bond to its coupon date,
 *       means moving time forward. With a real clock those tests either cannot be written
 *       or must sleep.</li>
 *   <li><b>Correct valuation.</b> Pricing is a pure function of instrument, market
 *       snapshot and valuation date. A pricer that reads the system clock is not pure, and
 *       revaluing the same position twice could give two answers.</li>
 * </ul>
 *
 * An ArchUnit rule enforces this: no production class outside this one may reference
 * {@code LocalDate.now()}, {@code Instant.now()} or {@code System.currentTimeMillis()}.
 *
 * <p>Implementations must be thread-safe. Risk workers read the valuation date
 * concurrently, so a clock that could be observed mid-update would let two workers value
 * the same portfolio on different dates.
 */
public interface SimulationClock {

    /** The current business date - the valuation date every pricer works from. */
    LocalDate today();

    /** The current instant, for event timestamps and lifecycle audit entries. */
    Instant now();

    /**
     * A clock frozen at {@code date}, with instants at that date's UTC midnight.
     *
     * <p>The default for unit tests: nothing moves unless the test moves it.
     */
    static SimulationClock fixedAt(LocalDate date) {
        Objects.requireNonNull(date, "date");
        Instant instant = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        return new SimulationClock() {
            @Override
            public LocalDate today() {
                return date;
            }

            @Override
            public Instant now() {
                return instant;
            }

            @Override
            public String toString() {
                return "FixedClock(" + date + ")";
            }
        };
    }

    /**
     * A clock the simulation harness advances explicitly.
     *
     * <p>Time moves when the simulation says so, not when the wall clock does - so a
     * thirty-year swap can be aged to maturity in microseconds, and settlement is
     * triggered by {@link Advancing#advanceTo} rather than by a timer.
     */
    static Advancing advancing(LocalDate startDate) {
        return new Advancing(startDate);
    }

    /**
     * A manually advanced clock.
     *
     * <p>The date is held in an {@link AtomicReference} so readers never observe a
     * partially written value: a worker either sees the old date or the new one, never
     * something in between.
     */
    final class Advancing implements SimulationClock {

        private final AtomicReference<LocalDate> current;

        private Advancing(LocalDate startDate) {
            this.current = new AtomicReference<>(Objects.requireNonNull(startDate, "startDate"));
        }

        @Override
        public LocalDate today() {
            return current.get();
        }

        @Override
        public Instant now() {
            return current.get().atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        /** Moves the clock forward by {@code days} calendar days. */
        public LocalDate advanceDays(int days) {
            if (days < 0) {
                throw new IllegalArgumentException(
                        "Simulation time does not run backwards; asked to advance " + days + " days");
            }
            return current.updateAndGet(d -> d.plusDays(days));
        }

        /** Moves the clock forward to the next open day on {@code calendar}. */
        public LocalDate advanceOneBusinessDay(HolidayCalendar calendar) {
            Objects.requireNonNull(calendar, "calendar");
            return current.updateAndGet(calendar::nextBusinessDay);
        }

        /**
         * Moves the clock forward to {@code date}.
         *
         * @throws IllegalArgumentException if {@code date} is before the current date
         */
        public LocalDate advanceTo(LocalDate date) {
            Objects.requireNonNull(date, "date");
            return current.updateAndGet(currentDate -> {
                if (date.isBefore(currentDate)) {
                    throw new IllegalArgumentException(
                            "Simulation time does not run backwards: asked to move from "
                                    + currentDate + " to " + date);
                }
                return date;
            });
        }

        @Override
        public String toString() {
            return "AdvancingClock(" + current.get() + ")";
        }
    }
}
