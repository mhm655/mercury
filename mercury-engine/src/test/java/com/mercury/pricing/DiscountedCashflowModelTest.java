package com.mercury.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.money.Money;
import com.mercury.core.time.Frequency;
import com.mercury.core.time.HolidayCalendar;
import com.mercury.instrument.Bond;
import com.mercury.instrument.FxForward;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.model.DiscountedCashflowModel;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reference-value and invariant tests for discounted-cashflow pricing.
 *
 * <p>The dates are chosen so the year fractions are exact. 15 January 2023 to 15 January 2024
 * is 365 days, which is exactly 1.0 under ACT/365F - so a one-year discount factor is
 * {@code e^-r} with no rounding in the exponent, and the expected value can be written down
 * rather than captured from a run.
 */
class DiscountedCashflowModelTest {

    private static final LocalDate VALUATION = LocalDate.of(2023, 1, 15);
    private static final LocalDate ONE_YEAR = LocalDate.of(2024, 1, 15);
    private static final CurrencyPair EURUSD = CurrencyPair.parse("EUR/USD");

    private static final DiscountedCashflowModel<Bond> BOND_MODEL =
            new DiscountedCashflowModel<>(Bond.class);
    private static final DiscountedCashflowModel<FxForward> FX_MODEL =
            new DiscountedCashflowModel<>(FxForward.class);

    @Test
    @DisplayName("the chosen dates really are exactly one year under ACT/365F")
    void datesAreExactlyOneYear() {
        // 2023 is not a leap year, so this is 365 days and the year fraction is exactly 1.0.
        // If this ever changes the reference values below stop being hand-checkable.
        assertThat(com.mercury.core.time.DayCountConvention.ACT_365F
                .yearFraction(VALUATION, ONE_YEAR)).isCloseTo(1.0, within(1e-15));
    }

    private static Bond zeroCouponBond(String rate) {
        return Bond.builder()
                .id("ZCB-1Y")
                .faceValue(Money.of("1000000", Currency.USD))
                .couponRate("0")
                .couponFrequency(Frequency.ANNUAL)
                .calendar(HolidayCalendar.alwaysOpen())
                .issueDate(VALUATION)
                .maturityDate(ONE_YEAR)
                .build();
    }

    private static MarketDataSnapshot usdAt(double rate) {
        return MarketDataSnapshot.builder(VALUATION).discountRate(Currency.USD, rate).build();
    }

    @Nested
    @DisplayName("bond reference values")
    class BondValues {

        @Test
        @DisplayName("a one-year zero-coupon bond is worth face x e^-r")
        void zeroCouponBondValue() {
            // 1,000,000 x e^-0.05 = 951,229.42. No coupons, one cashflow, one discount factor:
            // the simplest case where the answer can be stated exactly.
            double value = BOND_MODEL.price(zeroCouponBond("0"), usdAt(0.05), VALUATION).value();

            assertThat(value).isCloseTo(1_000_000.0 * Math.exp(-0.05), within(1e-6));
            assertThat(value).isCloseTo(951_229.42, within(0.01));
        }

        @Test
        @DisplayName("at a zero rate a zero-coupon bond is worth its face value")
        void zeroRateGivesFaceValue() {
            assertThat(BOND_MODEL.price(zeroCouponBond("0"), usdAt(0.0), VALUATION).value())
                    .isCloseTo(1_000_000.0, within(1e-6));
        }

        @Test
        @DisplayName("a two-year annual coupon bond discounts each cashflow separately")
        void couponBondValue() {
            // 5% annual on 1,000,000, valued at issue, flat 5% continuously compounded.
            //   50,000 at t = 1.00000 -> 50,000 x e^-0.05
            //   1,050,000 at t = 731/365 = 2.00274 -> 1,050,000 x e^(-0.05 x 2.00274)
            Bond bond = Bond.builder()
                    .id("UST-2Y").faceValue(Money.of("1000000", Currency.USD))
                    .couponRate("0.05").couponFrequency(Frequency.ANNUAL)
                    .calendar(HolidayCalendar.alwaysOpen())
                    .issueDate(VALUATION).maturityDate(LocalDate.of(2025, 1, 15))
                    .build();

            double secondYears = 731.0 / 365.0;
            double expected = 50_000.0 * Math.exp(-0.05)
                    + 1_050_000.0 * Math.exp(-0.05 * secondYears);

            assertThat(BOND_MODEL.price(bond, usdAt(0.05), VALUATION).value())
                    .isCloseTo(expected, within(1e-6));
        }

        @Test
        @DisplayName("value falls as the discount rate rises")
        void monotonicInRate() {
            // The defining property of a bond price, and independent of any implementation.
            double previous = Double.MAX_VALUE;
            for (double rate = 0.0; rate <= 0.20; rate += 0.01) {
                double value = BOND_MODEL.price(zeroCouponBond("0"), usdAt(rate), VALUATION).value();

                assertThat(value).as("rate %s", rate).isLessThan(previous);
                previous = value;
            }
        }

        @Test
        @DisplayName("a matured bond is worth nothing")
        void maturedBondIsWorthless() {
            assertThat(BOND_MODEL.price(zeroCouponBond("0"), usdAt(0.05), ONE_YEAR).value())
                    .isZero();
        }

        @Test
        @DisplayName("a negative rate makes a bond worth more than face")
        void negativeRatesRaiseValue() {
            assertThat(BOND_MODEL.price(zeroCouponBond("0"), usdAt(-0.01), VALUATION).value())
                    .isGreaterThan(1_000_000.0);
        }
    }

    @Nested
    @DisplayName("FX forward reference values")
    class FxForwardValues {

        /** Spot 1.10, EUR at 3%, USD at 5%. */
        private static MarketDataSnapshot fxMarket() {
            return MarketDataSnapshot.builder(VALUATION)
                    .fxRate(EURUSD, 1.10)
                    .discountRate(Currency.EUR, 0.03)
                    .discountRate(Currency.USD, 0.05)
                    .build();
        }

        @Test
        @DisplayName("buying 1,000,000 EUR one year forward at 1.08 is worth 40,162 USD")
        void forwardValue() {
            // Value in USD = e^-0.03 x 1,000,000 x 1.10   (the EUR leg, discounted on the EUR
            //                                              curve, then converted at spot)
            //              - e^-0.05 x 1,080,000          (the USD leg it pays)
            //              = 1,067,490.09 - 1,027,327.78 = 40,162.31
            FxForward forward = FxForward.buy("FWD", EURUSD, "1000000", "1.08", ONE_YEAR);

            double expected = Math.exp(-0.03) * 1_000_000.0 * 1.10
                    - Math.exp(-0.05) * 1_080_000.0;

            assertThat(FX_MODEL.price(forward, fxMarket(), VALUATION).value())
                    .isCloseTo(expected, within(1e-6))
                    .isCloseTo(40_162.31, within(0.01));
        }

        @Test
        @DisplayName("a forward struck at the fair rate is worth nothing")
        void fairForwardIsWorthless() {
            // Covered interest parity: F = S x e^((r_quote - r_base) T). A contract struck
            // there has no value at inception. This holds regardless of implementation, which
            // makes it the strongest available check on the two-currency discounting.
            double fairRate = 1.10 * Math.exp((0.05 - 0.03) * 1.0);
            FxForward atFair = FxForward.buy(
                    "FWD-FAIR", EURUSD, "1000000", String.valueOf(fairRate), ONE_YEAR);

            // Not exactly zero, and the residual is explainable rather than noise. The USD leg
            // is a settlement amount, so it is rounded to the cent like any ledger figure -
            // 1,000,000 x 1.12222... cannot be paid to fractions of a cent. Parity holds for
            // the unrounded amount; the contract settles the rounded one, leaving at most half
            // a cent, discounted: 0.005 x e^-0.05 = 0.0048. Measured residual is 0.0038.
            //
            // A tolerance of 1e-6 failed here, and tightening the model would have been the
            // wrong response - the rounding is correct, and the test's expectation was what
            // ignored it.
            assertThat(FX_MODEL.price(atFair, fxMarket(), VALUATION).value())
                    .isCloseTo(0.0, within(0.005 * Math.exp(-0.05) + 1e-9));
        }

        @Test
        @DisplayName("buying and selling the same forward are exact opposites")
        void buyAndSellAreOpposite() {
            FxForward bought = FxForward.buy("B", EURUSD, "1000000", "1.08", ONE_YEAR);
            FxForward sold = FxForward.sell("S", EURUSD, "1000000", "1.08", ONE_YEAR);

            assertThat(FX_MODEL.price(bought, fxMarket(), VALUATION).value())
                    .isCloseTo(-FX_MODEL.price(sold, fxMarket(), VALUATION).value(), within(1e-9));
        }

        @Test
        @DisplayName("a stronger euro raises the value of a long EUR forward")
        void spotMovesTheValue() {
            FxForward bought = FxForward.buy("B", EURUSD, "1000000", "1.08", ONE_YEAR);
            MarketDataSnapshot stronger = fxMarket()
                    .withShock(com.mercury.marketdata.MarketShock.scaleAllFxRates(1.05));

            assertThat(FX_MODEL.price(bought, stronger, VALUATION).value())
                    .isGreaterThan(FX_MODEL.price(bought, fxMarket(), VALUATION).value());
        }

        @Test
        @DisplayName("the result is reported in the pair's quote currency")
        void valuedInQuoteCurrency() {
            FxForward forward = FxForward.buy("FWD", EURUSD, "1000000", "1.08", ONE_YEAR);

            assertThat(FX_MODEL.price(forward, fxMarket(), VALUATION).currency())
                    .isEqualTo(Currency.USD);
        }

        @Test
        @DisplayName("a settled forward is worth nothing")
        void settledForwardIsWorthless() {
            FxForward forward = FxForward.buy("FWD", EURUSD, "1000000", "1.08", ONE_YEAR);

            assertThat(FX_MODEL.price(forward, fxMarket(), ONE_YEAR).value()).isZero();
        }
    }

    @Nested
    @DisplayName("FX rate resolution")
    class FxRates {

        @Test
        @DisplayName("a currency against itself is one, without consulting the snapshot")
        void sameCurrencyIsOne() {
            assertThat(MarketDataSnapshot.empty(VALUATION).fxRate(Currency.USD, Currency.USD))
                    .isCloseTo(1.0, within(1e-15));
        }

        @Test
        @DisplayName("the inverse direction is derived, not stored")
        void inverseIsDerived() {
            // Storing both directions would let them drift apart, and a snapshot whose EUR/USD
            // and USD/EUR were not exact reciprocals would imply a risk-free round trip.
            MarketDataSnapshot market = MarketDataSnapshot.builder(VALUATION).fxRate(EURUSD, 1.10).build();

            assertThat(market.fxRate(Currency.EUR, Currency.USD)).isCloseTo(1.10, within(1e-15));
            assertThat(market.fxRate(Currency.USD, Currency.EUR))
                    .isCloseTo(1.0 / 1.10, within(1e-15));
            assertThat(market.fxRate(Currency.EUR, Currency.USD)
                    * market.fxRate(Currency.USD, Currency.EUR))
                    .as("a round trip must return exactly what it started with")
                    .isCloseTo(1.0, within(1e-15));
        }

        @Test
        @DisplayName("a missing pair fails in both directions")
        void missingPairThrows() {
            assertThatThrownBy(() -> MarketDataSnapshot.empty(VALUATION)
                    .fxRate(Currency.EUR, Currency.JPY))
                    .isInstanceOf(MarketDataSnapshot.MissingMarketDataException.class)
                    .hasMessageContaining("fx:EUR/JPY");
        }

        @Test
        @DisplayName("a non-positive rate is rejected")
        void rejectsNonPositiveRate() {
            assertThatThrownBy(() -> MarketDataSnapshot.builder(VALUATION).fxRate(EURUSD, 0.0))
                    .isInstanceOf(
                            com.mercury.marketdata.MarketDataKey.InvalidMarketDataException.class)
                    .hasMessageContaining("must be positive");
        }
    }

    @Nested
    @DisplayName("through the registry")
    class ThroughTheRegistry {

        @Test
        @DisplayName("bonds and FX forwards dispatch to the same model, registered twice")
        void oneModelRegisteredTwice() {
            // The generic model in place of a Template Method hierarchy: one class, two
            // registrations, no subclasses whose only content is a type token.
            PricingService service = PricingService.builder()
                    .register(new DiscountedCashflowModel<>(Bond.class))
                    .register(new DiscountedCashflowModel<>(FxForward.class))
                    .build();
            MarketDataSnapshot market = MarketDataSnapshot.builder(VALUATION)
                    .discountRate(Currency.USD, 0.05)
                    .discountRate(Currency.EUR, 0.03)
                    .fxRate(EURUSD, 1.10)
                    .build();

            assertThat(service.canPrice(Bond.class)).isTrue();
            assertThat(service.canPrice(FxForward.class)).isTrue();
            assertThat(service.price(zeroCouponBond("0"), market, VALUATION).value())
                    .isCloseTo(951_229.42, within(0.01));
            assertThat(service.price(
                    FxForward.buy("F", EURUSD, "1000000", "1.08", ONE_YEAR), market, VALUATION)
                    .value()).isCloseTo(40_162.31, within(0.01));
        }

        @Test
        @DisplayName("a missing discount curve fails loudly rather than discounting at zero")
        void missingRateThrows() {
            assertThatThrownBy(() -> BOND_MODEL.price(
                    zeroCouponBond("0"), MarketDataSnapshot.empty(VALUATION), VALUATION))
                    .isInstanceOf(MarketDataSnapshot.MissingMarketDataException.class)
                    .hasMessageContaining("zero:USD");
        }
    }
}
