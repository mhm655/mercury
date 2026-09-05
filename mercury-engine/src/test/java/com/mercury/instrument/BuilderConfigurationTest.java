package com.mercury.instrument;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.time.BusinessDayConvention;
import com.mercury.core.time.Frequency;
import com.mercury.core.time.HolidayCalendar;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers builder options that no other test exercised.
 *
 * <p>The orphaned-API check found that {@code businessDayConvention(..)} and
 * {@code spread(..)} were public, documented configuration with no caller anywhere - meaning
 * the non-default rolling and spread paths through instrument construction were reachable
 * from the API and never executed.
 *
 * <p>These are not dead code to delete; they are real options that were simply untested. The
 * distinction matters: the rule's purpose is to force that choice to be made explicitly
 * rather than left unnoticed.
 */
class BuilderConfigurationTest {

    private static final LocalDate ISSUE = LocalDate.of(2024, 1, 15);

    /** Saturday, so a rolling convention has something to do. */
    private static final LocalDate SATURDAY_MATURITY = LocalDate.of(2027, 5, 15);

    @Test
    @DisplayName("a bond's business-day convention changes where its coupons land")
    void bondBusinessDayConventionIsApplied() {
        Bond following = bondWith(BusinessDayConvention.FOLLOWING);
        Bond preceding = bondWith(BusinessDayConvention.PRECEDING);

        assertThat(SATURDAY_MATURITY.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        // FOLLOWING rolls the Saturday forward to Monday; PRECEDING rolls it back to Friday.
        assertThat(following.maturityDate()).isEqualTo(LocalDate.of(2027, 5, 17));
        assertThat(preceding.maturityDate()).isEqualTo(LocalDate.of(2027, 5, 14));
        assertThat(following.maturityDate()).isNotEqualTo(preceding.maturityDate());
    }

    @Test
    @DisplayName("UNADJUSTED leaves coupon dates on non-business days")
    void unadjustedLeavesWeekendDates() {
        Bond unadjusted = bondWith(BusinessDayConvention.UNADJUSTED);

        assertThat(unadjusted.maturityDate()).isEqualTo(SATURDAY_MATURITY);
        assertThat(HolidayCalendar.weekendsOnly().isBusinessDay(unadjusted.maturityDate()))
                .isFalse();
    }

    @Test
    @DisplayName("every rolled coupon date is a business day under a rolling convention")
    void rolledCouponsAreBusinessDays() {
        HolidayCalendar weekends = HolidayCalendar.weekendsOnly();

        assertThat(bondWith(BusinessDayConvention.MODIFIED_FOLLOWING).schedule().periods())
                .allSatisfy(period ->
                        assertThat(weekends.isBusinessDay(period.paymentDate())).isTrue());
    }

    @Test
    @DisplayName("a swap's business-day convention reaches both legs")
    void swapBusinessDayConventionReachesBothLegs() {
        HolidayCalendar weekends = HolidayCalendar.weekendsOnly();
        InterestRateSwap swap = swapWith(BusinessDayConvention.MODIFIED_FOLLOWING, BasisPoints.ZERO);

        assertThat(swap.fixedLeg().schedule().periods()).allSatisfy(period ->
                assertThat(weekends.isBusinessDay(period.paymentDate())).isTrue());
        assertThat(swap.floatingLeg().schedule().periods()).allSatisfy(period ->
                assertThat(weekends.isBusinessDay(period.paymentDate())).isTrue());
    }

    @Test
    @DisplayName("a spread configured on the builder reaches the floating leg's coupons")
    void spreadReachesTheFloatingLeg() {
        InterestRateSwap without = swapWith(BusinessDayConvention.UNADJUSTED, BasisPoints.ZERO);
        InterestRateSwap with = swapWith(BusinessDayConvention.UNADJUSTED, BasisPoints.of(50));

        assertThat(with.floatingLeg().spread()).isEqualTo(BasisPoints.of(50));

        var period = with.floatingLeg().schedule().first();
        Money difference = with.floatingLeg().couponFor(period, 0.04)
                .minus(without.floatingLeg().couponFor(period, 0.04));

        // 50bp on 10,000,000 over 91 actual days at ACT/360 = 12,638.89.
        assertThat(difference).isEqualTo(Money.of("12638.89", Currency.USD));
    }

    @Test
    @DisplayName("every business-day convention builds a valid bond")
    void allConventionsProduceValidBonds() {
        for (BusinessDayConvention convention : BusinessDayConvention.values()) {
            Bond bond = bondWith(convention);

            assertThat(bond.schedule().size()).as("%s", convention).isEqualTo(3);
            assertThat(bond.cashflows(ISSUE)).as("%s", convention).hasSize(3);
        }
    }

    private static Bond bondWith(BusinessDayConvention convention) {
        return Bond.builder()
                .id("B-" + convention.name())
                .faceValue(Money.of("1000000", Currency.USD))
                .couponRate("0.05")
                .couponFrequency(Frequency.ANNUAL)
                .businessDayConvention(convention)
                .calendar(HolidayCalendar.weekendsOnly())
                .issueDate(LocalDate.of(2024, 5, 15))
                .maturityDate(SATURDAY_MATURITY)
                .build();
    }

    private static InterestRateSwap swapWith(BusinessDayConvention convention, BasisPoints spread) {
        return InterestRateSwap.builder()
                .id("IRS-" + convention.name() + "-" + spread.value())
                .notional(Money.of("10000000", Currency.USD))
                .fixedRate("0.0425")
                .index(FloatingRateIndex.usdSofr3M())
                .spread(spread)
                .businessDayConvention(convention)
                .calendar(HolidayCalendar.weekendsOnly())
                .effectiveDate(ISSUE)
                .maturityDate(LocalDate.of(2029, 1, 15))
                .build();
    }

    @Test
    @DisplayName("conventions are genuinely distinct, not silently collapsing to one")
    void conventionsAreDistinct() {
        List<LocalDate> maturities = List.of(
                bondWith(BusinessDayConvention.FOLLOWING).maturityDate(),
                bondWith(BusinessDayConvention.PRECEDING).maturityDate(),
                bondWith(BusinessDayConvention.UNADJUSTED).maturityDate());

        assertThat(maturities).doesNotHaveDuplicates();
    }
}
