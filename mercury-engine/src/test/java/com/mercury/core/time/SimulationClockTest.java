package com.mercury.core.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SimulationClockTest {

    private static final LocalDate START = LocalDate.of(2024, 6, 3); // a Monday

    @Nested
    @DisplayName("fixed clock")
    class Fixed {

        @Test
        @DisplayName("never moves, so repeated reads agree")
        void neverMoves() {
            SimulationClock clock = SimulationClock.fixedAt(START);

            assertThat(clock.today()).isEqualTo(START);
            assertThat(clock.today()).isEqualTo(START);
            assertThat(clock.now()).isEqualTo(clock.now());
        }

        @Test
        @DisplayName("its instant is the date's UTC midnight")
        void instantIsUtcMidnight() {
            SimulationClock clock = SimulationClock.fixedAt(START);

            assertThat(clock.now()).isEqualTo(START.atStartOfDay(ZoneOffset.UTC).toInstant());
        }
    }

    @Nested
    @DisplayName("advancing clock")
    class Advancing {

        @Test
        @DisplayName("starts at the given date")
        void startsAtGivenDate() {
            assertThat(SimulationClock.advancing(START).today()).isEqualTo(START);
        }

        @Test
        @DisplayName("advances by calendar days")
        void advancesByDays() {
            SimulationClock.Advancing clock = SimulationClock.advancing(START);

            assertThat(clock.advanceDays(1)).isEqualTo(LocalDate.of(2024, 6, 4));
            assertThat(clock.advanceDays(10)).isEqualTo(LocalDate.of(2024, 6, 14));
            assertThat(clock.today()).isEqualTo(LocalDate.of(2024, 6, 14));
        }

        @Test
        @DisplayName("advances to an explicit date")
        void advancesToDate() {
            SimulationClock.Advancing clock = SimulationClock.advancing(START);

            clock.advanceTo(LocalDate.of(2025, 1, 1));

            assertThat(clock.today()).isEqualTo(LocalDate.of(2025, 1, 1));
        }

        @Test
        @DisplayName("advances one business day at a time, skipping weekends")
        void advancesBusinessDays() {
            // Friday 7 June 2024 -> Monday 10 June.
            SimulationClock.Advancing clock = SimulationClock.advancing(LocalDate.of(2024, 6, 7));

            assertThat(clock.advanceOneBusinessDay(HolidayCalendar.weekendsOnly()))
                    .isEqualTo(LocalDate.of(2024, 6, 10));
        }

        @Test
        @DisplayName("ages a thirty-year instrument instantly, because time is simulated")
        void agesInstantly() {
            SimulationClock.Advancing clock = SimulationClock.advancing(START);

            clock.advanceTo(START.plusYears(30));

            assertThat(clock.today()).isEqualTo(LocalDate.of(2054, 6, 3));
        }

        @Test
        @DisplayName("refuses to run backwards")
        void refusesToGoBackwards() {
            SimulationClock.Advancing clock = SimulationClock.advancing(START);

            assertThatThrownBy(() -> clock.advanceTo(START.minusDays(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not run backwards");

            assertThatThrownBy(() -> clock.advanceDays(-1))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(clock.today()).as("a rejected advance leaves the clock untouched")
                    .isEqualTo(START);
        }

        @Test
        @DisplayName("advancing to the current date is allowed and is a no-op")
        void advancingToSameDateIsFine() {
            SimulationClock.Advancing clock = SimulationClock.advancing(START);

            assertThat(clock.advanceTo(START)).isEqualTo(START);
        }
    }
}
