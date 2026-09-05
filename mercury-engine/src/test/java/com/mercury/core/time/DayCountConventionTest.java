package com.mercury.core.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reference-value tests for the day count conventions.
 *
 * <p>Every expected number here is derived by hand from the convention's definition and
 * stated in the test, rather than captured from this implementation's own output.
 * Financial code that only checks itself against itself proves nothing: it will happily
 * confirm that a wrong formula is consistently wrong.
 */
class DayCountConventionTest {

    /**
     * 15 Jan 2024 to 15 Apr 2024. Actual elapsed days: 31 (Jan 15 to Feb 15)
     * + 29 (Feb 15 to Mar 15, 2024 being a leap year) + 31 (Mar 15 to Apr 15) = 91.
     */
    private static final LocalDate START = LocalDate.of(2024, 1, 15);
    private static final LocalDate END = LocalDate.of(2024, 4, 15);
    private static final double TOLERANCE = 1e-12;

    @Nested
    @DisplayName("a 91-day period, priced four ways")
    class TheSamePeriodFourWays {

        @Test
        @DisplayName("ACT/360 divides 91 actual days by 360")
        void act360() {
            assertThat(DayCountConvention.ACT_360.yearFraction(START, END))
                    .isCloseTo(91.0 / 360.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("ACT/365F divides 91 actual days by a fixed 365")
        void act365Fixed() {
            assertThat(DayCountConvention.ACT_365F.yearFraction(START, END))
                    .isCloseTo(91.0 / 365.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("30/360 counts three whole 30-day months, giving exactly a quarter")
        void thirty360() {
            // 360*(2024-2024) + 30*(4-1) + (15-15) = 90 days.
            assertThat(DayCountConvention.THIRTY_360_US.yearFraction(START, END))
                    .isCloseTo(0.25, within(TOLERANCE));
        }

        @Test
        @DisplayName("ACT/ACT ISDA divides by 366 because the period lies inside a leap year")
        void actActIsda() {
            assertThat(DayCountConvention.ACT_ACT_ISDA.yearFraction(START, END))
                    .isCloseTo(91.0 / 366.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("the four conventions genuinely disagree, which is why the choice matters")
        void conventionsDisagree() {
            double act360 = DayCountConvention.ACT_360.yearFraction(START, END);
            double act365 = DayCountConvention.ACT_365F.yearFraction(START, END);
            double thirty = DayCountConvention.THIRTY_360_US.yearFraction(START, END);

            // On 100m notional at 5%, ACT/360 vs 30/360 is about 19,000 of difference.
            assertThat(act360).isGreaterThan(act365);
            assertThat(act360).isGreaterThan(thirty);
        }
    }

    @Nested
    @DisplayName("30/360 end-of-month rules")
    class ThirtyThreeSixtyEndOfMonth {

        @Test
        @DisplayName("a start day of 31 is treated as 30")
        void startDayThirtyFirstBecomesThirty() {
            // d1: 31 -> 30. d2: 29, untouched (not 31).
            // 30*(2-1) + (29-30) = 29 days.
            double actual = DayCountConvention.THIRTY_360_US.yearFraction(
                    LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 29));

            assertThat(actual).isCloseTo(29.0 / 360.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("an end day of 31 becomes 30 only when the start day is 30 or 31")
        void endDayThirtyFirstIsConditional() {
            // Start on the 30th: d2 31 -> 30. 30*2 + (30-30) = 60 days.
            double fromThirtieth = DayCountConvention.THIRTY_360_US.yearFraction(
                    LocalDate.of(2024, 1, 30), LocalDate.of(2024, 3, 31));
            assertThat(fromThirtieth).isCloseTo(60.0 / 360.0, within(TOLERANCE));

            // Start on the 29th: the adjustment does not apply, d2 stays 31.
            // 30*2 + (31-29) = 62 days. This asymmetry is the rule, not a bug.
            double fromTwentyNinth = DayCountConvention.THIRTY_360_US.yearFraction(
                    LocalDate.of(2024, 1, 29), LocalDate.of(2024, 3, 31));
            assertThat(fromTwentyNinth).isCloseTo(62.0 / 360.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("a semi-annual period is exactly half a year, which is the point of 30/360")
        void semiAnnualIsExactlyHalf() {
            assertThat(DayCountConvention.THIRTY_360_US.yearFraction(
                    LocalDate.of(2024, 6, 15), LocalDate.of(2024, 12, 15)))
                    .isCloseTo(0.5, within(TOLERANCE));
        }
    }

    @Nested
    @DisplayName("ACT/ACT ISDA across a year boundary")
    class ActActAcrossYears {

        @Test
        @DisplayName("splits the period at the year boundary and weights each part separately")
        void splitsAtYearBoundary() {
            // 1 Dec 2023 to 1 Feb 2024.
            //   31 days fall in 2023, a 365-day year -> 31/365
            //   31 days fall in 2024, a 366-day year -> 31/366
            double expected = 31.0 / 365.0 + 31.0 / 366.0;

            assertThat(DayCountConvention.ACT_ACT_ISDA.yearFraction(
                    LocalDate.of(2023, 12, 1), LocalDate.of(2024, 2, 1)))
                    .isCloseTo(expected, within(TOLERANCE));
        }

        @Test
        @DisplayName("a whole calendar year is exactly 1.0, leap or not")
        void wholeYearIsOne() {
            assertThat(DayCountConvention.ACT_ACT_ISDA.yearFraction(
                    LocalDate.of(2023, 1, 1), LocalDate.of(2024, 1, 1)))
                    .isCloseTo(1.0, within(TOLERANCE));

            assertThat(DayCountConvention.ACT_ACT_ISDA.yearFraction(
                    LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)))
                    .isCloseTo(1.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("spans several years, contributing exactly 1.0 for each whole year between")
        void multipleYears() {
            assertThat(DayCountConvention.ACT_ACT_ISDA.yearFraction(
                    LocalDate.of(2020, 1, 1), LocalDate.of(2025, 1, 1)))
                    .isCloseTo(5.0, within(TOLERANCE));
        }
    }

    @Nested
    @DisplayName("boundary conditions")
    class Boundaries {

        @Test
        @DisplayName("a zero-length period accrues nothing under every convention")
        void zeroLengthPeriod() {
            LocalDate date = LocalDate.of(2024, 6, 30);
            for (DayCountConvention convention : DayCountConvention.values()) {
                assertThat(convention.yearFraction(date, date))
                        .as("%s over a zero-length period", convention)
                        .isZero();
            }
        }

        @Test
        @DisplayName("a backwards period is rejected rather than returning a negative fraction")
        void backwardsPeriodRejected() {
            for (DayCountConvention convention : DayCountConvention.values()) {
                assertThatThrownBy(() -> convention.yearFraction(END, START))
                        .as("%s over a backwards period", convention)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("precedes");
            }
        }

        @Test
        @DisplayName("every convention yields a non-negative fraction on a forward period")
        void alwaysNonNegative() {
            for (DayCountConvention convention : DayCountConvention.values()) {
                assertThat(convention.yearFraction(START, END))
                        .as("%s", convention)
                        .isPositive();
            }
        }
    }
}
