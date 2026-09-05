package com.mercury.core.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ScheduleGeneratorTest {

    private static final HolidayCalendar OPEN = HolidayCalendar.alwaysOpen();
    private static final HolidayCalendar WEEKENDS = HolidayCalendar.weekendsOnly();

    private static List<LocalDate> accrualEnds(Schedule schedule) {
        return schedule.periods().stream().map(SchedulePeriod::accrualEnd).toList();
    }

    @Nested
    @DisplayName("regular schedules")
    class Regular {

        @Test
        @DisplayName("a two-year semi-annual bond has four equal periods")
        void twoYearSemiAnnual() {
            Schedule schedule = ScheduleGenerator.generate(
                    LocalDate.of(2024, 6, 15), LocalDate.of(2026, 6, 15),
                    Frequency.SEMI_ANNUAL, BusinessDayConvention.UNADJUSTED, OPEN);

            assertThat(schedule.size()).isEqualTo(4);
            assertThat(accrualEnds(schedule)).containsExactly(
                    LocalDate.of(2024, 12, 15),
                    LocalDate.of(2025, 6, 15),
                    LocalDate.of(2025, 12, 15),
                    LocalDate.of(2026, 6, 15));
        }

        @Test
        @DisplayName("the schedule spans exactly the effective date to maturity")
        void spansTheFullTerm() {
            LocalDate effective = LocalDate.of(2024, 1, 10);
            LocalDate maturity = LocalDate.of(2027, 1, 10);

            Schedule schedule = ScheduleGenerator.generate(
                    effective, maturity, Frequency.QUARTERLY, BusinessDayConvention.UNADJUSTED, OPEN);

            assertThat(schedule.effectiveDate()).isEqualTo(effective);
            assertThat(schedule.maturityDate()).isEqualTo(maturity);
            assertThat(schedule.size()).isEqualTo(12);
        }

        @Test
        @DisplayName("each frequency produces the expected number of periods over one year")
        void periodCountsMatchFrequency() {
            LocalDate effective = LocalDate.of(2024, 3, 20);
            LocalDate maturity = LocalDate.of(2025, 3, 20);

            for (Frequency frequency : Frequency.values()) {
                Schedule schedule = ScheduleGenerator.generate(
                        effective, maturity, frequency, BusinessDayConvention.UNADJUSTED, OPEN);

                assertThat(schedule.size())
                        .as("%s over one year", frequency)
                        .isEqualTo(frequency.periodsPerYear());
            }
        }
    }

    @Nested
    @DisplayName("backward generation puts the stub at the front")
    class BackwardGeneration {

        @Test
        @DisplayName("an odd term leaves a short first period, not a short last one")
        void stubIsAtTheFront() {
            // The worked example from ScheduleGenerator's documentation: issued 15 February
            // 2024, maturing 30 June 2029, paying semi-annually. Rolling backwards from
            // maturity anchors every coupon to the 30th, and leaves the odd period at the
            // front as a short first coupon.
            Schedule schedule = ScheduleGenerator.generate(
                    LocalDate.of(2024, 2, 15), LocalDate.of(2029, 6, 30),
                    Frequency.SEMI_ANNUAL, BusinessDayConvention.UNADJUSTED, OPEN);

            SchedulePeriod first = schedule.first();
            assertThat(first.accrualStart()).isEqualTo(LocalDate.of(2024, 2, 15));
            assertThat(first.accrualEnd()).isEqualTo(LocalDate.of(2024, 6, 30));

            // Short: about four and a half months against a regular six.
            double stub = first.yearFraction(DayCountConvention.ACT_365F);
            double regular = schedule.periods().get(1).yearFraction(DayCountConvention.ACT_365F);
            assertThat(stub).isLessThan(regular);

            // Every coupon after the stub falls on the 30th, matching maturity - which is
            // what forward generation would have failed to do.
            assertThat(schedule.periods().stream().skip(1).map(SchedulePeriod::accrualEnd))
                    .allSatisfy(date -> assertThat(date.getDayOfMonth()).isEqualTo(30));

            // The final coupon coincides with maturity, so principal and the last coupon
            // settle together.
            assertThat(schedule.last().accrualEnd()).isEqualTo(LocalDate.of(2029, 6, 30));
        }

        @Test
        @DisplayName("month-end dates do not drift, because each date is anchored to maturity")
        void monthEndDoesNotDrift() {
            // Generating by repeatedly subtracting a month from the previous boundary would
            // ratchet 31 -> 30 at the first short month and never recover: from 31 December
            // you would get 30 November, then 30 October, losing the 31st permanently.
            // Anchoring every boundary to maturity keeps the 31sts.
            Schedule schedule = ScheduleGenerator.generate(
                    LocalDate.of(2024, 8, 31), LocalDate.of(2024, 12, 31),
                    Frequency.MONTHLY, BusinessDayConvention.UNADJUSTED, OPEN);

            assertThat(accrualEnds(schedule)).containsExactly(
                    LocalDate.of(2024, 9, 30),
                    LocalDate.of(2024, 10, 31),
                    LocalDate.of(2024, 11, 30),
                    LocalDate.of(2024, 12, 31));
        }
    }

    @Nested
    @DisplayName("business day adjustment")
    class Adjustment {

        @Test
        @DisplayName("adjusted dates always land on business days")
        void adjustedDatesAreBusinessDays() {
            Schedule schedule = ScheduleGenerator.generate(
                    LocalDate.of(2024, 1, 15), LocalDate.of(2027, 1, 15),
                    Frequency.MONTHLY, BusinessDayConvention.MODIFIED_FOLLOWING, WEEKENDS);

            assertThat(schedule.periods()).allSatisfy(period -> {
                assertThat(WEEKENDS.isBusinessDay(period.accrualStart())).isTrue();
                assertThat(WEEKENDS.isBusinessDay(period.accrualEnd())).isTrue();
                assertThat(WEEKENDS.isBusinessDay(period.paymentDate())).isTrue();
            });
        }

        @Test
        @DisplayName("adjustment does not accumulate across a long schedule")
        void adjustmentDoesNotAccumulate() {
            // Each boundary is rolled from its own unadjusted date. If adjustments chained,
            // one early weekend would shift every later date and the schedule would drift
            // away from the 15th over thirty years.
            Schedule schedule = ScheduleGenerator.generate(
                    LocalDate.of(2024, 1, 15), LocalDate.of(2054, 1, 15),
                    Frequency.SEMI_ANNUAL, BusinessDayConvention.MODIFIED_FOLLOWING, WEEKENDS);

            assertThat(schedule.size()).isEqualTo(60);
            // Every payment stays within a weekend's reach of the 15th.
            assertThat(schedule.periods()).allSatisfy(period ->
                    assertThat(period.accrualEnd().getDayOfMonth()).isBetween(13, 17));
        }

        @Test
        @DisplayName("payment date equals the adjusted accrual end")
        void paymentFollowsAccrualEnd() {
            Schedule schedule = ScheduleGenerator.generate(
                    LocalDate.of(2024, 1, 31), LocalDate.of(2025, 1, 31),
                    Frequency.QUARTERLY, BusinessDayConvention.MODIFIED_FOLLOWING, WEEKENDS);

            assertThat(schedule.periods()).allSatisfy(period ->
                    assertThat(period.paymentDate()).isEqualTo(period.accrualEnd()));
        }
    }

    @Nested
    @DisplayName("structural invariants")
    class Invariants {

        @Test
        @DisplayName("periods are contiguous - no gaps, no overlaps")
        void periodsAreContiguous() {
            Schedule schedule = ScheduleGenerator.generate(
                    LocalDate.of(2024, 2, 29), LocalDate.of(2034, 2, 28),
                    Frequency.QUARTERLY, BusinessDayConvention.MODIFIED_FOLLOWING, WEEKENDS);

            List<SchedulePeriod> periods = schedule.periods();
            for (int i = 1; i < periods.size(); i++) {
                assertThat(periods.get(i).accrualStart())
                        .as("period %d starts where period %d ended", i, i - 1)
                        .isEqualTo(periods.get(i - 1).accrualEnd());
            }
        }

        @Test
        @DisplayName("Schedule rejects a non-contiguous period list outright")
        void scheduleRejectsGaps() {
            List<SchedulePeriod> withGap = List.of(
                    new SchedulePeriod(
                            LocalDate.of(2024, 1, 1), LocalDate.of(2024, 4, 1), LocalDate.of(2024, 4, 1)),
                    new SchedulePeriod(
                            LocalDate.of(2024, 5, 1), LocalDate.of(2024, 8, 1), LocalDate.of(2024, 8, 1)));

            assertThatThrownBy(() -> Schedule.of(withGap))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("contiguous");
        }

        @Test
        @DisplayName("a term shorter than one period still yields a single period")
        void shortTermYieldsOnePeriod() {
            Schedule schedule = ScheduleGenerator.generate(
                    LocalDate.of(2024, 1, 15), LocalDate.of(2024, 3, 15),
                    Frequency.SEMI_ANNUAL, BusinessDayConvention.UNADJUSTED, OPEN);

            assertThat(schedule.size()).isEqualTo(1);
            assertThat(schedule.first().accrualStart()).isEqualTo(LocalDate.of(2024, 1, 15));
            assertThat(schedule.first().accrualEnd()).isEqualTo(LocalDate.of(2024, 3, 15));
        }

        @Test
        @DisplayName("maturity must follow the effective date")
        void rejectsInvertedTerm() {
            assertThatThrownBy(() -> ScheduleGenerator.generate(
                    LocalDate.of(2026, 1, 1), LocalDate.of(2024, 1, 1),
                    Frequency.ANNUAL, BusinessDayConvention.UNADJUSTED, OPEN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be after");
        }
    }

    @Nested
    @DisplayName("unpaid period filtering")
    class UnpaidPeriods {

        @Test
        @DisplayName("only periods paying after the valuation date remain")
        void filtersPaidPeriods() {
            Schedule schedule = ScheduleGenerator.generate(
                    LocalDate.of(2024, 1, 15), LocalDate.of(2027, 1, 15),
                    Frequency.ANNUAL, BusinessDayConvention.UNADJUSTED, OPEN);

            // After the first coupon has been paid, two remain.
            assertThat(schedule.unpaidPeriodsAsOf(LocalDate.of(2025, 6, 1))).hasSize(2);
            // Before anything is paid, all three remain.
            assertThat(schedule.unpaidPeriodsAsOf(LocalDate.of(2024, 1, 15))).hasSize(3);
            // After maturity, nothing remains.
            assertThat(schedule.unpaidPeriodsAsOf(LocalDate.of(2027, 6, 1))).isEmpty();
        }
    }
}
