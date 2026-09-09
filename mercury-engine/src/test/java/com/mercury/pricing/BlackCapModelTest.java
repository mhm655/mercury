package com.mercury.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.Frequency;
import com.mercury.core.time.HolidayCalendar;
import com.mercury.core.time.SchedulePeriod;
import com.mercury.core.time.Tenor;
import com.mercury.curve.CurveBootstrapper;
import com.mercury.curve.CurveInstrument;
import com.mercury.curve.DepositQuote;
import com.mercury.curve.ParSwapQuote;
import com.mercury.curve.YieldCurve;
import com.mercury.instrument.Bond;
import com.mercury.instrument.CapFloor;
import com.mercury.instrument.EuropeanOption;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.instrument.FloatingRateIndex;
import com.mercury.instrument.FxForward;
import com.mercury.instrument.InterestRateSwap;
import com.mercury.instrument.Stock;
import com.mercury.core.id.InstrumentId;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.model.BlackCapModel;
import com.mercury.pricing.model.BlackScholesModel;
import com.mercury.pricing.model.DiscountedCashflowModel;
import com.mercury.pricing.model.SpotPriceModel;
import com.mercury.pricing.model.SwapModel;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The sixth instrument, and the proof that adding one costs nothing elsewhere.
 *
 * <p>Every file involved in supporting caps and floors is new: the instrument, the model and
 * this test. No existing class, interface, enum or registration was edited to make it work.
 * That is the open-closed claim the design has made since the first page, demonstrated on a
 * real instrument rather than on a fixture invented to be easy - a cap pays a third kind of
 * cashflow the engine had never seen, being neither known like a bond's nor projectable like a
 * swap's, but contingent.
 *
 * <h2>Cap-floor parity is the test that matters</h2>
 * A cap minus a floor at the same strike is exactly a payer swap on the same schedule, because
 * {@code N(d1) + N(-d1) = 1} makes the option terms collapse to {@code F - K}. That identity
 * is checked here against {@link SwapModel} - a model this one knows nothing about - so it
 * cross-validates the new code against the old rather than against itself.
 */
class BlackCapModelTest {

    private static final LocalDate TODAY = LocalDate.of(2024, 6, 28);
    private static final LocalDate IN_FIVE_YEARS = Tenor.years(5).addTo(TODAY);
    private static final Money NOTIONAL = Money.of("10000000", Currency.USD);
    private static final InstrumentId CAP_ID = InstrumentId.of("CAP-5Y-4.5");

    private static final BlackCapModel MODEL = new BlackCapModel();

    /** Cent-rounding across the twenty coupons of the comparison swap. */
    private static final double ROUNDING = 0.50;

    private static List<CurveInstrument> quotes() {
        return List.of(
                DepositQuote.of(Tenor.months(3), 0.0533),
                DepositQuote.of(Tenor.months(6), 0.0524),
                DepositQuote.of(Tenor.years(1), 0.0500),
                ParSwapQuote.of(Tenor.years(2), 0.0460),
                ParSwapQuote.of(Tenor.years(5), 0.0425),
                ParSwapQuote.of(Tenor.years(10), 0.0430));
    }

    private static MarketDataSnapshot market(double volatility) {
        return MarketDataSnapshot.builder(TODAY)
                .curve(Currency.USD, CurveBootstrapper.bootstrap(TODAY, quotes()))
                .volatility(CAP_ID, volatility)
                .volatility(InstrumentId.of("FLOOR"), volatility)
                .build();
    }

    private static CapFloor cap(double strike) {
        return CapFloor.cap()
                .id("CAP-5Y-4.5")
                .notional(NOTIONAL)
                .strike(strike)
                .index(FloatingRateIndex.usdSofr3M())
                .frequency(Frequency.QUARTERLY)
                .effectiveDate(TODAY)
                .maturityDate(IN_FIVE_YEARS)
                .build();
    }

    private static CapFloor floor(double strike) {
        return CapFloor.floor()
                .id("FLOOR")
                .notional(NOTIONAL)
                .strike(strike)
                .index(FloatingRateIndex.usdSofr3M())
                .frequency(Frequency.QUARTERLY)
                .effectiveDate(TODAY)
                .maturityDate(IN_FIVE_YEARS)
                .build();
    }

    /** A payer swap whose fixed leg matches the cap strip exactly, for parity. */
    private static InterestRateSwap matchingPayerSwap(double strike) {
        return InterestRateSwap.builder()
                .id("IRS-MATCHING")
                .notional(NOTIONAL)
                .fixedRate(java.math.BigDecimal.valueOf(strike).toPlainString())
                .payingFixed()
                .fixedFrequency(Frequency.QUARTERLY)
                .fixedDayCount(DayCountConvention.ACT_360)
                .floatingFrequency(Frequency.QUARTERLY)
                .index(FloatingRateIndex.usdSofr3M())
                .effectiveDate(TODAY)
                .maturityDate(IN_FIVE_YEARS)
                .build();
    }

    @Nested
    @DisplayName("cap-floor parity")
    class Parity {

        @Test
        @DisplayName("a cap minus a floor is a payer swap at the same strike")
        void parityHolds() {
            // The option terms collapse: N(d1) + N(-d1) = 1 and N(d2) + N(-d2) = 1, so each
            // caplet minus its floorlet is exactly N x tau x DF x (F - K). Checked against a
            // model that knows nothing about caps.
            MarketDataSnapshot market = market(0.30);
            double strike = 0.0425;

            double capValue = MODEL.price(cap(strike), market, TODAY).value();
            double floorValue = MODEL.price(floor(strike), market, TODAY).value();
            double swapValue = new SwapModel()
                    .price(matchingPayerSwap(strike), market, TODAY).value();

            assertThat(capValue - floorValue).isCloseTo(swapValue, within(ROUNDING));
        }

        @Test
        @DisplayName("it holds at any volatility, because volatility cancels")
        void parityIsVolatilityIndependent() {
            // Worth asserting separately: parity is an arbitrage relationship, so it must not
            // depend on the model input that is pure assumption.
            double strike = 0.0425;
            double swapValue = new SwapModel()
                    .price(matchingPayerSwap(strike), market(0.30), TODAY).value();

            for (double volatility : new double[] {0.10, 0.30, 0.80}) {
                MarketDataSnapshot market = market(volatility);
                double difference = MODEL.price(cap(strike), market, TODAY).value()
                        - MODEL.price(floor(strike), market, TODAY).value();

                assertThat(difference)
                        .as("parity at volatility %s", volatility)
                        .isCloseTo(swapValue, within(ROUNDING));
            }
        }
    }

    @Nested
    @DisplayName("how the value moves")
    class Behaviour {

        @Test
        @DisplayName("a cap is worth less the higher its strike, a floor more")
        void monotonicInStrike() {
            MarketDataSnapshot market = market(0.30);

            assertThat(MODEL.price(cap(0.05), market, TODAY).value())
                    .isLessThan(MODEL.price(cap(0.04), market, TODAY).value());
            assertThat(MODEL.price(floor(0.05), market, TODAY).value())
                    .isGreaterThan(MODEL.price(floor(0.04), market, TODAY).value());
        }

        @Test
        @DisplayName("both are worth more when the rate is more uncertain")
        void monotonicInVolatility() {
            assertThat(MODEL.price(cap(0.045), market(0.50), TODAY).value())
                    .isGreaterThan(MODEL.price(cap(0.045), market(0.20), TODAY).value());
            assertThat(MODEL.price(floor(0.035), market(0.50), TODAY).value())
                    .isGreaterThan(MODEL.price(floor(0.035), market(0.20), TODAY).value());
        }

        @Test
        @DisplayName("with no volatility a cap is worth exactly its intrinsic value")
        void zeroVolatilityGivesIntrinsic() {
            // The boundary that must not reach a logarithm. Computed here independently from
            // the curve, so it checks the model rather than restating it.
            MarketDataSnapshot market = market(0.0);
            CapFloor capFloor = cap(0.0425);
            YieldCurve curve = market.yieldCurve(Currency.USD);

            double expected = 0.0;
            for (SchedulePeriod caplet : capFloor.schedule().periods()) {
                double forward = curve.simpleForwardRate(
                        caplet.accrualStart(), caplet.accrualEnd(), capFloor.dayCount());
                expected += NOTIONAL.amount().doubleValue()
                        * caplet.yearFraction(capFloor.dayCount())
                        * curve.discountFactor(caplet.paymentDate())
                        * Math.max(forward - 0.0425, 0.0);
            }

            assertThat(MODEL.price(capFloor, market, TODAY).value())
                    .isCloseTo(expected, within(1e-6));
        }

        @Test
        @DisplayName("a far out-of-the-money cap is worth almost nothing, but not negative")
        void deepOutOfTheMoney() {
            // Stated as ratios rather than an absolute figure. "Almost nothing" on a ten
            // million notional is not a number anyone can pick correctly in advance - the
            // first version of this test guessed one dollar and the answer was 4.75 - so the
            // assertion is against the notional it is a fraction of and against the
            // at-the-money cap it should be dwarfed by.
            MarketDataSnapshot market = market(0.30);
            double notional = NOTIONAL.amount().doubleValue();

            double farOut = MODEL.price(cap(0.50), market, TODAY).value();
            double atTheMoney = MODEL.price(cap(0.0425), market, TODAY).value();

            assertThat(farOut).isPositive();
            assertThat(farOut).isLessThan(notional * 1e-5);
            assertThat(farOut).isLessThan(atTheMoney / 10_000.0);
        }
    }

    @Nested
    @DisplayName("what it refuses")
    class Refusals {

        @Test
        @DisplayName("a negative forward rate, because Black's model does not extend there")
        void negativeForward() {
            // Rates do go negative and this engine prices swaps through zero without
            // complaint. A lognormal variable cannot be, so this is the wrong model rather
            // than a numerical difficulty - and saying so beats returning an intrinsic value
            // that would report a floor as worthless exactly when it is worth the most.
            MarketDataSnapshot belowZero = MarketDataSnapshot.builder(TODAY)
                    .curve(Currency.USD, CurveBootstrapper.bootstrap(TODAY, List.of(
                            DepositQuote.of(Tenor.months(6), -0.0050),
                            ParSwapQuote.of(Tenor.years(5), -0.0030))))
                    .volatility(CAP_ID, 0.30)
                    .build();

            assertThatThrownBy(() -> MODEL.price(cap(0.0425), belowZero, TODAY))
                    .isInstanceOf(BlackCapModel.NegativeForwardException.class)
                    .hasMessageContaining("Bachelier");
        }

        @Test
        @DisplayName("a caplet that has already fixed")
        void alreadyFixed() {
            CapFloor started = CapFloor.cap()
                    .id("CAP-5Y-4.5")
                    .notional(NOTIONAL)
                    .strike(0.0425)
                    .index(FloatingRateIndex.usdSofr3M())
                    .effectiveDate(LocalDate.of(2024, 5, 15))
                    .maturityDate(LocalDate.of(2029, 5, 15))
                    .build();

            assertThatThrownBy(() -> MODEL.price(started, market(0.30), TODAY))
                    .isInstanceOf(BlackCapModel.CapletAlreadyFixedException.class)
                    .hasMessageContaining("fixing history");
        }

        @Test
        @DisplayName("a notional in a currency the index does not publish in")
        void currencyMismatch() {
            assertThatThrownBy(() -> CapFloor.cap()
                    .id("CAP-EUR")
                    .notional(Money.of("1000000", Currency.EUR))
                    .strike(0.0425)
                    .index(FloatingRateIndex.usdSofr3M())
                    .effectiveDate(TODAY)
                    .maturityDate(IN_FIVE_YEARS)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("another currency");
        }
    }

    @Nested
    @DisplayName("the open-closed claim, demonstrated")
    class OpenClosed {

        @Test
        @DisplayName("six instrument types price side by side through one registry")
        void sixInstrumentsOneRegistry() {
            // The point of the whole exercise. The cap is registered exactly as the five
            // instruments before it were - one line - and dispatches with no branch anywhere
            // in the pricing layer that knows a cap exists.
            MarketDataSnapshot market = MarketDataSnapshot.builder(TODAY)
                    .curve(Currency.USD, CurveBootstrapper.bootstrap(TODAY, quotes()))
                    .curve(Currency.EUR, com.mercury.curve.YieldCurve.flat(TODAY, 0.03))
                    .spot(InstrumentId.of("AAPL"), 195.50)
                    .volatility(InstrumentId.of("AAPL"), 0.28)
                    .volatility(CAP_ID, 0.30)
                    .fxRate(CurrencyPair.parse("EUR/USD"), 1.0725)
                    .build();

            PricingService pricing = PricingService.builder()
                    .register(new SpotPriceModel())
                    .register(new BlackScholesModel())
                    .register(new DiscountedCashflowModel<>(Bond.class))
                    .register(new DiscountedCashflowModel<>(FxForward.class))
                    .register(new SwapModel())
                    .register(new BlackCapModel())
                    .build();

            List<FinancialInstrument> book = List.of(
                    Stock.of("AAPL", Currency.USD),
                    Bond.builder()
                            .id("CORP-5Y")
                            .faceValue(Money.of("1000", Currency.USD))
                            .couponRate("0.045")
                            .couponFrequency(Frequency.SEMI_ANNUAL)
                            .calendar(HolidayCalendar.weekendsOnly())
                            .issueDate(TODAY)
                            .maturityDate(IN_FIVE_YEARS)
                            .build(),
                    EuropeanOption.call("AAPL-C-200", InstrumentId.of("AAPL"), Price.of("200"),
                            TODAY.plusYears(1), Currency.USD),
                    FxForward.buy("FWD", CurrencyPair.parse("EUR/USD"), "500000", "1.09",
                            TODAY.plusYears(1)),
                    matchingPayerSwap(0.0425),
                    cap(0.0425));

            for (FinancialInstrument instrument : book) {
                ValuationResult result = pricing.price(instrument, market, TODAY);
                assertThat(Double.isFinite(result.value()))
                        .as("%s priced by %s", instrument.id(), result.model())
                        .isTrue();
            }

            assertThat(pricing.price(cap(0.0425), market, TODAY).model())
                    .isEqualTo(BlackCapModel.NAME);
        }
    }
}
