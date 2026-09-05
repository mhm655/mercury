package com.mercury.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.Frequency;
import com.mercury.core.time.HolidayCalendar;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BondTest {

    private static final LocalDate ISSUE = LocalDate.of(2024, 1, 15);
    private static final LocalDate MATURITY = LocalDate.of(2029, 1, 15);

    /**
     * A 5-year, 5% semi-annual bond on 1,000,000 USD, 30/360.
     *
     * <p>Under 30/360 every semi-annual period is exactly half a year, so each coupon is
     * exactly {@code 1,000,000 x 0.05 x 0.5 = 25,000}. That exactness is the point of the
     * convention and makes the expected values checkable by hand.
     */
    private static Bond fivePercentBond() {
        return Bond.builder()
                .id("UST-5Y")
                .name("US Treasury 5%")
                .faceValue(Money.of("1000000", Currency.USD))
                .couponRate("0.05")
                .couponFrequency(Frequency.SEMI_ANNUAL)
                .dayCount(DayCountConvention.THIRTY_360_US)
                .calendar(HolidayCalendar.alwaysOpen())
                .issueDate(ISSUE)
                .maturityDate(MATURITY)
                .build();
    }

    @Nested
    @DisplayName("identity and classification")
    class Classification {

        @Test
        @DisplayName("is a rates instrument that trades on an exchange")
        void classification() {
            Bond bond = fivePercentBond();

            assertThat(bond.assetClass()).isEqualTo(AssetClass.RATES);
            assertThat(bond.tradability()).isEqualTo(TradabilityProfile.EXCHANGE_TRADED);
            assertThat(bond.currency()).isEqualTo(Currency.USD);
            assertThat(bond.maturityDate()).isEqualTo(MATURITY);
        }

        @Test
        @DisplayName("implements the capabilities it actually has")
        void implementsRightCapabilities() {
            Bond bond = fivePercentBond();

            assertThat(bond).isInstanceOf(CashflowGenerating.class);
            assertThat(bond).isInstanceOf(Maturing.class);
            // A bond has no underlying and no option terms.
            assertThat(bond).isNotInstanceOf(HasUnderlying.class);
            assertThat(bond).isNotInstanceOf(OptionTerms.class);
        }
    }

    @Nested
    @DisplayName("cashflows")
    class Cashflows {

        @Test
        @DisplayName("ten semi-annual coupons of exactly 25,000 over five years")
        void couponAmounts() {
            List<Cashflow> cashflows = fivePercentBond().cashflows(ISSUE);

            assertThat(cashflows).hasSize(10);
            // Every coupon except the last is exactly 1,000,000 x 5% x 0.5.
            assertThat(cashflows.subList(0, 9))
                    .allSatisfy(cf -> assertThat(cf.amount())
                            .isEqualTo(Money.of("25000.00", Currency.USD)));
        }

        @Test
        @DisplayName("principal is repaid with the final coupon, as one payment")
        void principalRidesOnTheFinalCoupon() {
            List<Cashflow> cashflows = fivePercentBond().cashflows(ISSUE);
            Cashflow last = cashflows.get(cashflows.size() - 1);

            // 25,000 coupon + 1,000,000 principal, on one date - which is what settles.
            assertThat(last.amount()).isEqualTo(Money.of("1025000.00", Currency.USD));
            assertThat(last.paymentDate()).isEqualTo(MATURITY);
        }

        @Test
        @DisplayName("cashflows already paid are excluded")
        void excludesPaidCashflows() {
            Bond bond = fivePercentBond();

            assertThat(bond.cashflows(ISSUE)).hasSize(10);
            // After two coupons have been paid, eight remain.
            assertThat(bond.cashflows(LocalDate.of(2025, 2, 1))).hasSize(8);
            // After maturity, nothing remains.
            assertThat(bond.cashflows(LocalDate.of(2029, 6, 1))).isEmpty();
        }

        @Test
        @DisplayName("all cashflows are receipts in the bond's currency")
        void cashflowsArePositiveAndInCurrency() {
            assertThat(fivePercentBond().cashflows(ISSUE)).allSatisfy(cf -> {
                assertThat(cf.isReceipt()).isTrue();
                assertThat(cf.amount().currency()).isEqualTo(Currency.USD);
            });
        }

        @Test
        @DisplayName("the returned list is immutable")
        void cashflowListIsImmutable() {
            List<Cashflow> cashflows = fivePercentBond().cashflows(ISSUE);

            assertThatThrownBy(() -> cashflows.add(
                    Cashflow.of(MATURITY, Money.of("1", Currency.USD))))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a zero-coupon bond pays only its principal, at maturity")
        void zeroCouponBond() {
            Bond zero = Bond.builder()
                    .id("ZCB-5Y")
                    .faceValue(Money.of("1000000", Currency.USD))
                    .couponRate("0")
                    .couponFrequency(Frequency.ANNUAL)
                    .calendar(HolidayCalendar.alwaysOpen())
                    .issueDate(ISSUE)
                    .maturityDate(MATURITY)
                    .build();

            assertThat(zero.isZeroCoupon()).isTrue();
            List<Cashflow> cashflows = zero.cashflows(ISSUE);

            // Five annual periods, four paying nothing and the last paying the principal.
            assertThat(cashflows).hasSize(5);
            assertThat(cashflows.subList(0, 4))
                    .allSatisfy(cf -> assertThat(cf.amount().isZero()).isTrue());
            assertThat(cashflows.get(4).amount()).isEqualTo(Money.of("1000000.00", Currency.USD));
        }

        @Test
        @DisplayName("day count convention changes the coupon, as it must")
        void dayCountAffectsCoupons() {
            // ACT/360 over a half-year accrues more than 30/360's exact 0.5, because roughly
            // 182 actual days divided by 360 exceeds one half.
            Bond act360 = Bond.builder()
                    .id("B-ACT360")
                    .faceValue(Money.of("1000000", Currency.USD))
                    .couponRate("0.05")
                    .couponFrequency(Frequency.SEMI_ANNUAL)
                    .dayCount(DayCountConvention.ACT_360)
                    .calendar(HolidayCalendar.alwaysOpen())
                    .issueDate(ISSUE)
                    .maturityDate(MATURITY)
                    .build();

            Money thirty360Coupon = fivePercentBond().cashflows(ISSUE).get(0).amount();
            Money act360Coupon = act360.cashflows(ISSUE).get(0).amount();

            assertThat(act360Coupon).isGreaterThan(thirty360Coupon);
        }
    }

    @Nested
    @DisplayName("accrued interest")
    class AccruedInterest {

        @Test
        @DisplayName("is zero on a coupon date, when the period has just reset")
        void zeroAtPeriodStart() {
            assertThat(fivePercentBond().accruedInterest(ISSUE).isZero()).isTrue();
        }

        @Test
        @DisplayName("is half a coupon a quarter of the way through a semi-annual period")
        void halfwayThroughPeriod() {
            // 15 January to 15 April is exactly three 30/360 months of a six-month period,
            // so half the 25,000 coupon has accrued: 12,500.
            assertThat(fivePercentBond().accruedInterest(LocalDate.of(2024, 4, 15)))
                    .isEqualTo(Money.of("12500.00", Currency.USD));
        }

        @Test
        @DisplayName("never reaches a full coupon within a period")
        void staysBelowFullCoupon() {
            Money accrued = fivePercentBond().accruedInterest(LocalDate.of(2024, 7, 14));

            assertThat(accrued).isLessThan(Money.of("25000.00", Currency.USD));
            assertThat(accrued.isPositive()).isTrue();
        }

        @Test
        @DisplayName("is zero outside the bond's life")
        void zeroOutsideLife() {
            Bond bond = fivePercentBond();

            assertThat(bond.accruedInterest(LocalDate.of(2023, 1, 1)).isZero()).isTrue();
            assertThat(bond.accruedInterest(LocalDate.of(2030, 1, 1)).isZero()).isTrue();
        }
    }

    @Nested
    @DisplayName("builder validation")
    class BuilderValidation {

        @Test
        @DisplayName("rejects maturity before issue")
        void rejectsInvertedDates() {
            assertThatThrownBy(() -> Bond.builder()
                    .id("BAD").faceValue(Money.of("100", Currency.USD)).couponRate("0.05")
                    .issueDate(MATURITY).maturityDate(ISSUE).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be after issue date");
        }

        @Test
        @DisplayName("rejects a non-positive face value")
        void rejectsNonPositiveFace() {
            assertThatThrownBy(() -> Bond.builder()
                    .id("BAD").faceValue(Money.of("0", Currency.USD)).couponRate("0.05")
                    .issueDate(ISSUE).maturityDate(MATURITY).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("face value must be positive");
        }

        @Test
        @DisplayName("rejects a negative coupon rate")
        void rejectsNegativeCoupon() {
            assertThatThrownBy(() -> Bond.builder()
                    .id("BAD").faceValue(Money.of("100", Currency.USD)).couponRate("-0.01")
                    .issueDate(ISSUE).maturityDate(MATURITY).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be negative");
        }

        @Test
        @DisplayName("requires the mandatory terms")
        void requiresMandatoryTerms() {
            assertThatThrownBy(() -> Bond.builder().id("X").build())
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("applies market-convention defaults")
        void appliesDefaults() {
            Bond bond = Bond.builder()
                    .id("DEFAULTS").faceValue(Money.of("1000", Currency.USD)).couponRate("0.05")
                    .issueDate(ISSUE).maturityDate(MATURITY).build();

            assertThat(bond.couponFrequency()).isEqualTo(Frequency.SEMI_ANNUAL);
            assertThat(bond.dayCount()).isEqualTo(DayCountConvention.THIRTY_360_US);
            assertThat(bond.name()).isEqualTo("DEFAULTS");
        }
    }
}
