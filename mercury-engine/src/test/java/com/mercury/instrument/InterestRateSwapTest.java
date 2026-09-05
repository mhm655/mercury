package com.mercury.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.Frequency;
import com.mercury.core.time.HolidayCalendar;
import com.mercury.core.time.Schedule;
import com.mercury.core.time.ScheduleGenerator;
import com.mercury.core.time.SchedulePeriod;
import com.mercury.core.time.BusinessDayConvention;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InterestRateSwapTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2024, 1, 15);
    private static final LocalDate MATURITY = LocalDate.of(2029, 1, 15);
    private static final Money NOTIONAL = Money.of("10000000", Currency.USD);

    /**
     * A 5-year 4.25% payer swap on 10,000,000 USD: pay annual 30/360 fixed, receive
     * quarterly ACT/360 SOFR.
     *
     * <p>The always-open calendar keeps accrual periods unadjusted, so 30/360 annual periods
     * are exactly 1.0 and the fixed coupon is exactly 425,000 - checkable by hand.
     */
    private static InterestRateSwap payerSwap() {
        return InterestRateSwap.builder()
                .id("IRS-USD-5Y")
                .notional(NOTIONAL)
                .fixedRate("0.0425")
                .payingFixed()
                .fixedFrequency(Frequency.ANNUAL)
                .fixedDayCount(DayCountConvention.THIRTY_360_US)
                .floatingFrequency(Frequency.QUARTERLY)
                .index(FloatingRateIndex.usdSofr3M())
                .calendar(HolidayCalendar.alwaysOpen())
                .effectiveDate(EFFECTIVE)
                .maturityDate(MATURITY)
                .build();
    }

    @Nested
    @DisplayName("structure")
    class Structure {

        @Test
        @DisplayName("is an OTC rates instrument")
        void classification() {
            InterestRateSwap swap = payerSwap();

            assertThat(swap.assetClass()).isEqualTo(AssetClass.RATES);
            assertThat(swap.tradability()).isEqualTo(TradabilityProfile.OVER_THE_COUNTER);
            assertThat(swap.currency()).isEqualTo(Currency.USD);
            assertThat(swap.maturityDate()).isEqualTo(MATURITY);
        }

        @Test
        @DisplayName("the two legs run in opposite directions")
        void legsAreOpposite() {
            InterestRateSwap swap = payerSwap();

            assertThat(swap.isPayerSwap()).isTrue();
            assertThat(swap.fixedLeg().payReceive()).isEqualTo(PayReceive.PAY);
            assertThat(swap.floatingLeg().payReceive()).isEqualTo(PayReceive.RECEIVE);
        }

        @Test
        @DisplayName("the legs pay at different frequencies, as the market convention has it")
        void legsHaveIndependentFrequencies() {
            InterestRateSwap swap = payerSwap();

            assertThat(swap.fixedLeg().schedule().size()).isEqualTo(5);    // annual
            assertThat(swap.floatingLeg().schedule().size()).isEqualTo(20); // quarterly
        }

        @Test
        @DisplayName("receivingFixed flips both legs")
        void receiverSwap() {
            InterestRateSwap receiver = InterestRateSwap.builder()
                    .id("IRS-RCV").notional(NOTIONAL).fixedRate("0.0425").receivingFixed()
                    .index(FloatingRateIndex.usdSofr3M())
                    .calendar(HolidayCalendar.alwaysOpen())
                    .effectiveDate(EFFECTIVE).maturityDate(MATURITY).build();

            assertThat(receiver.isPayerSwap()).isFalse();
            assertThat(receiver.fixedLeg().payReceive()).isEqualTo(PayReceive.RECEIVE);
            assertThat(receiver.floatingLeg().payReceive()).isEqualTo(PayReceive.PAY);
        }
    }

    @Nested
    @DisplayName("the swap itself does not generate cashflows")
    class NotCashflowGenerating {

        @Test
        @DisplayName("the swap is deliberately not CashflowGenerating")
        void swapIsNotCashflowGenerating() {
            // Half its cashflows depend on forward rates that need a curve, so it cannot
            // honestly promise contractual amounts. Implementing the interface with
            // estimates would hand callers real-looking Money that is not the contract's.
            assertThat(payerSwap()).isNotInstanceOf(CashflowGenerating.class);
        }

        @Test
        @DisplayName("but its fixed leg is, and can be discounted like a bond")
        void fixedLegIsCashflowGenerating() {
            assertThat(payerSwap().fixedLeg()).isInstanceOf(CashflowGenerating.class);
        }

        @Test
        @DisplayName("and its floating leg is not")
        void floatingLegIsNot() {
            assertThat(payerSwap().floatingLeg()).isNotInstanceOf(CashflowGenerating.class);
        }
    }

    @Nested
    @DisplayName("fixed leg")
    class FixedLeg {

        @Test
        @DisplayName("pays exactly 425,000 a year, negative because the leg is paid")
        void fixedCoupons() {
            List<Cashflow> cashflows = payerSwap().fixedLeg().cashflows(EFFECTIVE);

            // 10,000,000 x 4.25% x 1.0 = 425,000, paid so negative.
            assertThat(cashflows).hasSize(5);
            assertThat(cashflows).allSatisfy(cf ->
                    assertThat(cf.amount()).isEqualTo(Money.of("-425000.00", Currency.USD)));
        }

        @Test
        @DisplayName("a received fixed leg is the same amounts with the opposite sign")
        void receivedLegIsPositive() {
            InterestRateSwap receiver = InterestRateSwap.builder()
                    .id("IRS-RCV").notional(NOTIONAL).fixedRate("0.0425").receivingFixed()
                    .fixedFrequency(Frequency.ANNUAL)
                    .index(FloatingRateIndex.usdSofr3M())
                    .calendar(HolidayCalendar.alwaysOpen())
                    .effectiveDate(EFFECTIVE).maturityDate(MATURITY).build();

            assertThat(receiver.fixedLeg().cashflows(EFFECTIVE)).allSatisfy(cf ->
                    assertThat(cf.amount()).isEqualTo(Money.of("425000.00", Currency.USD)));
        }

        @Test
        @DisplayName("no principal is exchanged, unlike a bond")
        void noPrincipalExchange() {
            List<Cashflow> cashflows = payerSwap().fixedLeg().cashflows(EFFECTIVE);
            Cashflow last = cashflows.get(cashflows.size() - 1);

            // A bond's final cashflow carries the principal. A single-currency swap's
            // does not - the notionals are identical and would simply cancel.
            assertThat(last.amount()).isEqualTo(Money.of("-425000.00", Currency.USD));
            assertThat(last.amount().abs()).isLessThan(NOTIONAL);
        }
    }

    @Nested
    @DisplayName("floating leg projection")
    class FloatingLegProjection {

        @Test
        @DisplayName("a coupon is computed once a pricer supplies the projected rate")
        void couponFromProjectedRate() {
            FloatingRateLeg leg = payerSwap().floatingLeg();
            SchedulePeriod firstPeriod = leg.schedule().first();

            // 15 Jan to 15 Apr 2024 is 91 actual days; ACT/360 gives 91/360.
            // 10,000,000 x 4% x 91/360 = 101,111.11 (to the cent).
            Money coupon = leg.couponFor(firstPeriod, 0.04);

            assertThat(coupon).isEqualTo(Money.of("101111.11", Currency.USD));
        }

        @Test
        @DisplayName("a spread is added to the projected index rate")
        void spreadIsAdded() {
            Schedule schedule = ScheduleGenerator.generate(
                    EFFECTIVE, MATURITY, Frequency.QUARTERLY,
                    BusinessDayConvention.UNADJUSTED, HolidayCalendar.alwaysOpen());
            FloatingRateLeg withSpread = new FloatingRateLeg(
                    NOTIONAL, FloatingRateIndex.usdSofr3M(), BasisPoints.of(50),
                    schedule, PayReceive.RECEIVE, DayCountConvention.ACT_360);
            FloatingRateLeg withoutSpread = new FloatingRateLeg(
                    NOTIONAL, FloatingRateIndex.usdSofr3M(), BasisPoints.ZERO,
                    schedule, PayReceive.RECEIVE, DayCountConvention.ACT_360);

            SchedulePeriod period = schedule.first();
            Money difference = withSpread.couponFor(period, 0.04)
                    .minus(withoutSpread.couponFor(period, 0.04));

            // 50bp on 10,000,000 for 91/360 of a year = 12,638.89.
            assertThat(difference).isEqualTo(Money.of("12638.89", Currency.USD));
        }

        @Test
        @DisplayName("direction flips the sign")
        void directionFlipsSign() {
            FloatingRateLeg received = payerSwap().floatingLeg();
            SchedulePeriod period = received.schedule().first();

            assertThat(received.couponFor(period, 0.04).isPositive()).isTrue();
        }

        @Test
        @DisplayName("rejects a non-finite projected rate")
        void rejectsNonFiniteRate() {
            FloatingRateLeg leg = payerSwap().floatingLeg();

            assertThatThrownBy(() -> leg.couponFor(leg.schedule().first(), Double.NaN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("finite");
        }

        @Test
        @DisplayName("exposes its unpaid periods for a pricer to iterate")
        void exposesUnpaidPeriods() {
            FloatingRateLeg leg = payerSwap().floatingLeg();

            assertThat(leg.unpaidPeriods(EFFECTIVE)).hasSize(20);
            assertThat(leg.unpaidPeriods(LocalDate.of(2026, 1, 16))).hasSize(12);
        }
    }

    @Nested
    @DisplayName("leg consistency is enforced")
    class LegConsistency {

        private static Schedule annualSchedule() {
            return ScheduleGenerator.generate(EFFECTIVE, MATURITY, Frequency.ANNUAL,
                    BusinessDayConvention.UNADJUSTED, HolidayCalendar.alwaysOpen());
        }

        @Test
        @DisplayName("rejects legs in different currencies")
        void rejectsCurrencyMismatch() {
            FixedRateLeg usdLeg = new FixedRateLeg(
                    NOTIONAL, new java.math.BigDecimal("0.0425"), annualSchedule(),
                    PayReceive.PAY, DayCountConvention.THIRTY_360_US);
            FloatingRateLeg eurLeg = new FloatingRateLeg(
                    Money.of("10000000", Currency.EUR), FloatingRateIndex.euribor6M(),
                    BasisPoints.ZERO, annualSchedule(), PayReceive.RECEIVE,
                    DayCountConvention.ACT_360);

            assertThatThrownBy(() -> InterestRateSwap.of(InstrumentId.of("X"), usdLeg, eurLeg))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cross-currency swap is a different instrument");
        }

        @Test
        @DisplayName("rejects legs running in the same direction")
        void rejectsSameDirection() {
            FixedRateLeg fixed = new FixedRateLeg(
                    NOTIONAL, new java.math.BigDecimal("0.0425"), annualSchedule(),
                    PayReceive.PAY, DayCountConvention.THIRTY_360_US);
            FloatingRateLeg floating = new FloatingRateLeg(
                    NOTIONAL, FloatingRateIndex.usdSofr3M(), BasisPoints.ZERO,
                    annualSchedule(), PayReceive.PAY, DayCountConvention.ACT_360);

            assertThatThrownBy(() -> InterestRateSwap.of(InstrumentId.of("X"), fixed, floating))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("opposite directions");
        }

        @Test
        @DisplayName("rejects mismatched notionals")
        void rejectsNotionalMismatch() {
            FixedRateLeg fixed = new FixedRateLeg(
                    NOTIONAL, new java.math.BigDecimal("0.0425"), annualSchedule(),
                    PayReceive.PAY, DayCountConvention.THIRTY_360_US);
            FloatingRateLeg floating = new FloatingRateLeg(
                    Money.of("5000000", Currency.USD), FloatingRateIndex.usdSofr3M(),
                    BasisPoints.ZERO, annualSchedule(), PayReceive.RECEIVE,
                    DayCountConvention.ACT_360);

            assertThatThrownBy(() -> InterestRateSwap.of(InstrumentId.of("X"), fixed, floating))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("share a notional");
        }

        @Test
        @DisplayName("a floating leg cannot accrue an index from another currency")
        void rejectsIndexCurrencyMismatch() {
            assertThatThrownBy(() -> new FloatingRateLeg(
                    Money.of("10000000", Currency.USD), FloatingRateIndex.euribor6M(),
                    BasisPoints.ZERO, annualSchedule(), PayReceive.RECEIVE,
                    DayCountConvention.ACT_360))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("published for another currency");
        }

        @Test
        @DisplayName("a leg notional must be positive, since direction lives in PayReceive")
        void rejectsNegativeNotional() {
            assertThatThrownBy(() -> new FixedRateLeg(
                    Money.of("-1000", Currency.USD), new java.math.BigDecimal("0.04"),
                    annualSchedule(), PayReceive.PAY, DayCountConvention.THIRTY_360_US))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
        }
    }

    @Nested
    @DisplayName("index")
    class Index {

        @Test
        @DisplayName("carries the day count the coupon accrues on")
        void indexCarriesDayCount() {
            assertThat(FloatingRateIndex.usdSofr3M().dayCount())
                    .isEqualTo(DayCountConvention.ACT_360);
            assertThat(FloatingRateIndex.gbpSonia6M().dayCount())
                    .isEqualTo(DayCountConvention.ACT_365F);
        }

        @Test
        @DisplayName("tenor is part of identity - 3M SOFR is not 6M SOFR")
        void tenorIsPartOfIdentity() {
            FloatingRateIndex threeMonth = FloatingRateIndex.usdSofr3M();
            FloatingRateIndex sixMonth = new FloatingRateIndex(
                    "USD-SOFR", Currency.USD, com.mercury.core.time.Tenor.months(6),
                    DayCountConvention.ACT_360);

            assertThat(threeMonth).isNotEqualTo(sixMonth);
        }
    }

    @Nested
    @DisplayName("net cashflow profile")
    class NetProfile {

        @Test
        @DisplayName("signed legs sum without branching on direction")
        void legsSumDirectly() {
            InterestRateSwap swap = payerSwap();
            FloatingRateLeg floating = swap.floatingLeg();

            // Paid fixed is negative, received floating positive, so the net is a plain sum.
            Money fixedFlow = swap.fixedLeg().cashflows(EFFECTIVE).get(0).amount();
            Money floatingFlow = floating.couponFor(floating.schedule().first(), 0.05);

            assertThat(fixedFlow.isNegative()).isTrue();
            assertThat(floatingFlow.isPositive()).isTrue();
            assertThat(fixedFlow.plus(floatingFlow).amount().doubleValue())
                    .isCloseTo(-425000.0 + 126388.89, within(0.01));
        }
    }
}
