package com.mercury.core.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HolidayCalendarTest {

    @Nested
    @DisplayName("weekends")
    class Weekends {

        @Test
        @DisplayName("Saturday and Sunday are closed, weekdays are open")
        void weekendsAreClosed() {
            HolidayCalendar calendar = HolidayCalendar.weekendsOnly();

            assertThat(calendar.isHoliday(LocalDate.of(2024, 8, 17))).isTrue();  // Saturday
            assertThat(calendar.isHoliday(LocalDate.of(2024, 8, 18))).isTrue();  // Sunday
            assertThat(calendar.isBusinessDay(LocalDate.of(2024, 8, 16))).isTrue();  // Friday
            assertThat(calendar.isBusinessDay(LocalDate.of(2024, 8, 19))).isTrue();  // Monday
        }

        @Test
        @DisplayName("alwaysOpen has no closed days at all")
        void alwaysOpenIsNeverClosed() {
            HolidayCalendar calendar = HolidayCalendar.alwaysOpen();

            assertThat(calendar.isHoliday(LocalDate.of(2024, 8, 17))).isFalse();
            assertThat(calendar.isBusinessDay(LocalDate.of(2024, 8, 18))).isTrue();
        }
    }

    @Nested
    @DisplayName("composition - the reason this is an interface")
    class Composition {

        @Test
        @DisplayName("combining two calendars closes on a holiday in either centre")
        void takesTheUnionOfClosedDays() {
            // A EUR/USD trade settles only when both centres are open, so a holiday
            // anywhere blocks the date. This is a union, not an intersection.
            LocalDate julyFourth = LocalDate.of(2024, 7, 4);      // US only
            LocalDate germanUnityDay = LocalDate.of(2024, 10, 3); // Germany only

            HolidayCalendar us = HolidayCalendar.of(julyFourth);
            HolidayCalendar germany = HolidayCalendar.of(germanUnityDay);
            HolidayCalendar both = us.and(germany);

            assertThat(us.isHoliday(germanUnityDay)).isFalse();
            assertThat(germany.isHoliday(julyFourth)).isFalse();

            assertThat(both.isHoliday(julyFourth)).isTrue();
            assertThat(both.isHoliday(germanUnityDay)).isTrue();
        }

        @Test
        @DisplayName("composition nests, so a leaf and a tree behave identically to callers")
        void compositionNests() {
            HolidayCalendar threeCentres = HolidayCalendar.of(LocalDate.of(2024, 7, 4))
                    .and(HolidayCalendar.of(LocalDate.of(2024, 10, 3)))
                    .and(HolidayCalendar.of(LocalDate.of(2024, 12, 26)));

            assertThat(threeCentres.isHoliday(LocalDate.of(2024, 7, 4))).isTrue();
            assertThat(threeCentres.isHoliday(LocalDate.of(2024, 10, 3))).isTrue();
            assertThat(threeCentres.isHoliday(LocalDate.of(2024, 12, 26))).isTrue();
            assertThat(threeCentres.isBusinessDay(LocalDate.of(2024, 7, 5))).isTrue();
        }
    }

    @Nested
    @DisplayName("navigation")
    class Navigation {

        private final HolidayCalendar calendar = HolidayCalendar.weekendsOnly();

        @Test
        @DisplayName("next business day from Friday is Monday")
        void nextBusinessDaySkipsWeekend() {
            assertThat(calendar.nextBusinessDay(LocalDate.of(2024, 8, 16)))
                    .isEqualTo(LocalDate.of(2024, 8, 19));
        }

        @Test
        @DisplayName("previous business day from Monday is Friday")
        void previousBusinessDaySkipsWeekend() {
            assertThat(calendar.previousBusinessDay(LocalDate.of(2024, 8, 19)))
                    .isEqualTo(LocalDate.of(2024, 8, 16));
        }

        @Test
        @DisplayName("navigation is strict, so it always moves off the given date")
        void navigationIsStrict() {
            LocalDate wednesday = LocalDate.of(2024, 8, 14);

            assertThat(calendar.nextBusinessDay(wednesday)).isEqualTo(LocalDate.of(2024, 8, 15));
            assertThat(calendar.previousBusinessDay(wednesday)).isEqualTo(LocalDate.of(2024, 8, 13));
        }
    }

    @Nested
    @DisplayName("T+2 settlement")
    class Settlement {

        private final HolidayCalendar calendar = HolidayCalendar.weekendsOnly();

        @Test
        @DisplayName("a Thursday trade settles on Monday, not on Saturday")
        void thursdayTradeSettlesMonday() {
            LocalDate thursday = LocalDate.of(2024, 8, 29);
            assertThat(thursday.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);

            // Naive date arithmetic would give Saturday 31 August. Money does not move
            // on a Saturday, so settlement counts business days: Friday, then Monday.
            assertThat(thursday.plusDays(2)).isEqualTo(LocalDate.of(2024, 8, 31));
            assertThat(calendar.addBusinessDays(thursday, 2)).isEqualTo(LocalDate.of(2024, 9, 2));
        }

        @Test
        @DisplayName("a Monday trade settles on Wednesday, with no weekend in the way")
        void mondayTradeSettlesWednesday() {
            assertThat(calendar.addBusinessDays(LocalDate.of(2024, 8, 19), 2))
                    .isEqualTo(LocalDate.of(2024, 8, 21));
        }

        @Test
        @DisplayName("a public holiday delays settlement by a further day")
        void holidayDelaysSettlement() {
            // Trading Tuesday 2 July 2024 with Thursday 4 July closed: Wednesday counts,
            // Thursday does not, so the second business day is Friday 5 July.
            HolidayCalendar withHoliday = HolidayCalendar.of(LocalDate.of(2024, 7, 4));

            assertThat(withHoliday.addBusinessDays(LocalDate.of(2024, 7, 2), 2))
                    .isEqualTo(LocalDate.of(2024, 7, 5));
        }

        @Test
        @DisplayName("zero business days leaves the date untouched")
        void zeroIsIdentity() {
            LocalDate saturday = LocalDate.of(2024, 8, 17);

            assertThat(calendar.addBusinessDays(saturday, 0)).isEqualTo(saturday);
        }

        @Test
        @DisplayName("a negative count walks backwards")
        void negativeCountMovesBack() {
            assertThat(calendar.addBusinessDays(LocalDate.of(2024, 9, 2), -2))
                    .isEqualTo(LocalDate.of(2024, 8, 29));
        }
    }
}
