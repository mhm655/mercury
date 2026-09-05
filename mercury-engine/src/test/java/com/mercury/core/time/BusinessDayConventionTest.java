package com.mercury.core.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BusinessDayConventionTest {

    /** Saturday 31 August 2024 - the last day of the month, and a weekend. */
    private static final LocalDate SATURDAY_MONTH_END = LocalDate.of(2024, 8, 31);

    /** Saturday 17 August 2024 - a weekend comfortably mid-month. */
    private static final LocalDate SATURDAY_MID_MONTH = LocalDate.of(2024, 8, 17);

    private HolidayCalendar calendar;

    @BeforeEach
    void setUp() {
        calendar = HolidayCalendar.weekendsOnly();
    }

    @Test
    @DisplayName("the fixture dates really are weekends, so the rest of the suite means something")
    void fixtureDatesAreWeekends() {
        assertThat(SATURDAY_MONTH_END.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(SATURDAY_MID_MONTH.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
    }

    @Nested
    @DisplayName("business days are never moved")
    class BusinessDaysUnchanged {

        @Test
        @DisplayName("every convention leaves an open day alone")
        void openDayUntouched() {
            LocalDate wednesday = LocalDate.of(2024, 8, 14);

            for (BusinessDayConvention convention : BusinessDayConvention.values()) {
                assertThat(convention.adjust(wednesday, calendar))
                        .as("%s", convention)
                        .isEqualTo(wednesday);
            }
        }
    }

    @Nested
    @DisplayName("mid-month rolling")
    class MidMonth {

        @Test
        @DisplayName("FOLLOWING moves Saturday to Monday")
        void followingRollsForward() {
            assertThat(BusinessDayConvention.FOLLOWING.adjust(SATURDAY_MID_MONTH, calendar))
                    .isEqualTo(LocalDate.of(2024, 8, 19));
        }

        @Test
        @DisplayName("PRECEDING moves Saturday to Friday")
        void precedingRollsBack() {
            assertThat(BusinessDayConvention.PRECEDING.adjust(SATURDAY_MID_MONTH, calendar))
                    .isEqualTo(LocalDate.of(2024, 8, 16));
        }

        @Test
        @DisplayName("MODIFIED_FOLLOWING behaves like FOLLOWING when the month does not change")
        void modifiedFollowingMatchesFollowingMidMonth() {
            assertThat(BusinessDayConvention.MODIFIED_FOLLOWING.adjust(SATURDAY_MID_MONTH, calendar))
                    .isEqualTo(BusinessDayConvention.FOLLOWING.adjust(SATURDAY_MID_MONTH, calendar));
        }

        @Test
        @DisplayName("UNADJUSTED leaves even a closed day alone")
        void unadjustedDoesNothing() {
            assertThat(BusinessDayConvention.UNADJUSTED.adjust(SATURDAY_MID_MONTH, calendar))
                    .isEqualTo(SATURDAY_MID_MONTH);
        }
    }

    @Nested
    @DisplayName("month-end rolling - the reason MODIFIED_FOLLOWING exists")
    class MonthEnd {

        @Test
        @DisplayName("FOLLOWING pushes Saturday 31 August into September")
        void followingCrossesTheMonth() {
            assertThat(BusinessDayConvention.FOLLOWING.adjust(SATURDAY_MONTH_END, calendar))
                    .isEqualTo(LocalDate.of(2024, 9, 2));
        }

        @Test
        @DisplayName("MODIFIED_FOLLOWING rolls back to Friday rather than cross the month")
        void modifiedFollowingStaysInMonth() {
            // Rolling forward would land on 2 September, pushing the cashflow into the next
            // accrual period and the next month's books. Rolling back to Friday 30 August
            // keeps the period intact - which is the entire purpose of the convention.
            LocalDate adjusted =
                    BusinessDayConvention.MODIFIED_FOLLOWING.adjust(SATURDAY_MONTH_END, calendar);

            assertThat(adjusted).isEqualTo(LocalDate.of(2024, 8, 30));
            assertThat(adjusted.getMonth()).isEqualTo(SATURDAY_MONTH_END.getMonth());
        }

        @Test
        @DisplayName("MODIFIED_PRECEDING rolls forward rather than cross back a month")
        void modifiedPrecedingStaysInMonth() {
            // Sunday 1 September 2024. Rolling back reaches 30 August, the previous month,
            // so the convention rolls forward to Monday 2 September instead.
            LocalDate sundayMonthStart = LocalDate.of(2024, 9, 1);
            assertThat(sundayMonthStart.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);

            LocalDate adjusted =
                    BusinessDayConvention.MODIFIED_PRECEDING.adjust(sundayMonthStart, calendar);

            assertThat(adjusted).isEqualTo(LocalDate.of(2024, 9, 2));
            assertThat(adjusted.getMonth()).isEqualTo(sundayMonthStart.getMonth());
        }
    }

    @Nested
    @DisplayName("public holidays, not just weekends")
    class WithPublicHolidays {

        @Test
        @DisplayName("rolls past a holiday that falls on a weekday")
        void rollsPastHoliday() {
            // Thursday 4 July 2024 as a holiday: Independence Day.
            LocalDate independenceDay = LocalDate.of(2024, 7, 4);
            HolidayCalendar withHoliday = HolidayCalendar.of(independenceDay);

            assertThat(independenceDay.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
            assertThat(BusinessDayConvention.FOLLOWING.adjust(independenceDay, withHoliday))
                    .isEqualTo(LocalDate.of(2024, 7, 5));
        }

        @Test
        @DisplayName("skips a run of consecutive closed days")
        void skipsConsecutiveHolidays() {
            HolidayCalendar closedAllWeek = HolidayCalendar.of(
                    LocalDate.of(2024, 7, 1),
                    LocalDate.of(2024, 7, 2),
                    LocalDate.of(2024, 7, 3));

            assertThat(BusinessDayConvention.FOLLOWING.adjust(LocalDate.of(2024, 7, 1), closedAllWeek))
                    .isEqualTo(LocalDate.of(2024, 7, 4));
        }
    }
}
