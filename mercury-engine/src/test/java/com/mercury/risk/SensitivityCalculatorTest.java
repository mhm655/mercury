package com.mercury.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.Frequency;
import com.mercury.core.time.HolidayCalendar;
import com.mercury.instrument.Bond;
import com.mercury.instrument.EuropeanOption;
import com.mercury.instrument.FxForward;
import com.mercury.instrument.OptionType;
import com.mercury.instrument.Stock;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.marketdata.MarketShock;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioValuationService;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.model.BlackScholesModel;
import com.mercury.pricing.model.DiscountedCashflowModel;
import com.mercury.pricing.model.SpotPriceModel;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests numerical delta against answers known independently of the implementation.
 *
 * <p>A stock's delta is exactly its holding, which needs no model to know. An option's delta
 * is {@code N(d1)} in closed form - so the numerical result can be checked against the
 * analytic one, which is the cross-validation the design calls for at M10, available here
 * already because both are cheap.
 */
class SensitivityCalculatorTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final InstrumentId MSFT = InstrumentId.of("MSFT");
    private static final InstrumentId CALL = InstrumentId.of("AAPL-C-200");
    private static final LocalDate VALUATION = LocalDate.of(2024, 1, 15);
    private static final LocalDate EXPIRY = LocalDate.of(2025, 1, 15);
    private static final PortfolioId BOOK = PortfolioId.of("BOOK");

    private static final Stock AAPL_STOCK = Stock.of("AAPL", Currency.USD);
    private static final Stock MSFT_STOCK = Stock.of("MSFT", Currency.USD);
    private static final EuropeanOption AAPL_CALL = EuropeanOption.call(
            "AAPL-C-200", AAPL, Price.of("200"), EXPIRY, Currency.USD);

    private static SensitivityCalculator calculator() {
        return new SensitivityCalculator(new PortfolioValuationService(
                PricingService.builder()
                        .register(new SpotPriceModel())
                        .register(new BlackScholesModel())
                        .build(),
                InstrumentCatalog.of(AAPL_STOCK, MSFT_STOCK, AAPL_CALL)));
    }

    private static MarketDataSnapshot market() {
        return MarketDataSnapshot.builder(VALUATION)
                .spot(AAPL, 200.0)
                .spot(MSFT, 400.0)
                .volatility(AAPL, 0.25)
                .discountRate(Currency.USD, 0.04)
                .build();
    }

    @Nested
    @DisplayName("deltas with a known answer")
    class KnownAnswers {

        @Test
        @DisplayName("a stock position has a delta equal to its holding")
        void stockDeltaIsQuantity() {
            // No model needed to know this: 100 shares gain 100 for every unit the price rises.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 100).build();

            assertThat(calculator().delta(portfolio, AAPL, market(), VALUATION))
                    .isCloseTo(100.0, within(1e-6));
        }

        @Test
        @DisplayName("a short position has a negative delta")
        void shortDeltaIsNegative() {
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, -250).build();

            assertThat(calculator().delta(portfolio, AAPL, market(), VALUATION))
                    .isCloseTo(-250.0, within(1e-6));
        }

        @Test
        @DisplayName("delta to an unrelated underlying is zero")
        void unrelatedUnderlyingIsZero() {
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 100).build();

            assertThat(calculator().delta(portfolio, MSFT, market(), VALUATION))
                    .isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("deltas add across positions")
        void deltasAreAdditive() {
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 100).position(MSFT, 40).build();

            assertThat(calculator().delta(portfolio, AAPL, market(), VALUATION))
                    .isCloseTo(100.0, within(1e-6));
            assertThat(calculator().delta(portfolio, MSFT, market(), VALUATION))
                    .isCloseTo(40.0, within(1e-6));
        }

        @Test
        @DisplayName("a stock position has no gamma or vega")
        void stockHasNoGammaOrVega() {
            // A share price is linear in itself (no curvature) and carries no volatility
            // sensitivity at all - the negative control, mirroring equityHasNoDv01 below.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 100).build();

            assertThat(calculator().gamma(portfolio, AAPL, market(), VALUATION))
                    .isCloseTo(0.0, within(1e-6));
            assertThat(calculator().vega(portfolio, AAPL, market(), VALUATION))
                    .isCloseTo(0.0, within(1e-6));
        }
    }

    @Nested
    @DisplayName("cross-validation against the closed form")
    class CrossValidation {

        @Test
        @DisplayName("an option's numerical delta matches the closed form")
        void optionDeltaMatchesAnalytic() {
            // Two independent routes to the same number - one by revaluation, one by the
            // closed form - agreeing is far stronger evidence than either on its own, and it
            // is the check the design schedules for M10.
            double spot = 200.0;
            double strike = 200.0;
            double volatility = 0.25;
            double rate = 0.04;
            double years = AAPL_CALL.yearsToExpiry(VALUATION);

            double analyticDelta = BlackScholesModel.delta(
                    OptionType.CALL, spot, strike, years, rate, volatility);

            // 100 contracts of 100 shares each, so the portfolio delta is 10,000 x N(d1).
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(CALL, 100).build();
            double numericalDelta = calculator().delta(portfolio, AAPL, market(), VALUATION);

            assertThat(numericalDelta).isCloseTo(10_000.0 * analyticDelta, within(1.0));
        }

        @Test
        @DisplayName("an option's numerical gamma matches the closed form")
        void optionGammaMatchesAnalytic() {
            // The validation docs/DESIGN_PROPOSAL.md section 5.3.1 calls for "written early
            // (M10)" - Gamma is the numerically delicate one (a second-order finite
            // difference), so this is the check that the mitigations in the class javadoc
            // actually hold rather than merely being stated.
            double spot = 200.0;
            double strike = 200.0;
            double volatility = 0.25;
            double rate = 0.04;
            double years = AAPL_CALL.yearsToExpiry(VALUATION);

            double analyticGamma = BlackScholesModel.gamma(spot, strike, years, rate, volatility);

            // 100 contracts of 100 shares each, so the portfolio gamma is 10,000 x the
            // per-share figure.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(CALL, 100).build();
            double numericalGamma = calculator().gamma(portfolio, AAPL, market(), VALUATION);

            assertThat(numericalGamma).isCloseTo(10_000.0 * analyticGamma, within(0.05));
        }

        @Test
        @DisplayName("an option's numerical vega matches the closed form")
        void optionVegaMatchesAnalytic() {
            // BlackScholesModel.vega is per unit (100%) volatility change; the numerical
            // vega is per one vol point (1%), matching the size of the shock it actually
            // applies - see SensitivityCalculator's class javadoc. Scaling explicitly here,
            // rather than silently inside either method, is what keeps the mismatch visible.
            double spot = 200.0;
            double strike = 200.0;
            double volatility = 0.25;
            double rate = 0.04;
            double years = AAPL_CALL.yearsToExpiry(VALUATION);

            double analyticVegaPerPoint = BlackScholesModel.vega(spot, strike, years, rate, volatility)
                    * SensitivityCalculator.DEFAULT_VOLATILITY_BUMP;

            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(CALL, 100).build();
            double numericalVega = calculator().vega(portfolio, AAPL, market(), VALUATION);

            // A full vol point is not an infinitesimal, so the central-difference estimate
            // and the linear analytic slope differ by a term proportional to Vega's own
            // curvature (volga) over that point - the same tolerance scale optionDeltaMatchesAnalytic
            // already uses for a comparably sized number, not evidence of a smaller error.
            assertThat(numericalVega).isCloseTo(10_000.0 * analyticVegaPerPoint, within(1.0));
        }

        @Test
        @DisplayName("an at-the-money call has a delta near, and above, one half")
        void atTheMoneyDelta() {
            // N(d1) exceeds 0.5 at the money because d1 carries the positive drift term.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(CALL, 1).build();

            double delta = calculator().delta(portfolio, AAPL, market(), VALUATION);

            // One contract of 100 shares, so 100 x N(d1).
            assertThat(delta).isBetween(50.0, 70.0);
        }

        @Test
        @DisplayName("a hedged position has almost no delta")
        void hedgedPositionIsNeutral() {
            // 100 calls at delta ~0.6 hedged with 60 short shares should be close to flat -
            // the calculation a delta exists to support.
            Portfolio unhedged = Portfolio.builder(BOOK, Currency.USD)
                    .position(CALL, 100).build();
            double optionDelta = calculator().delta(unhedged, AAPL, market(), VALUATION);

            Portfolio hedged = Portfolio.builder(BOOK, Currency.USD)
                    .position(CALL, 100)
                    .position(AAPL, -Math.round(optionDelta))
                    .build();

            assertThat(calculator().delta(hedged, AAPL, market(), VALUATION))
                    .isCloseTo(0.0, within(1.0));
        }
    }

    @Nested
    @DisplayName("numerical behaviour")
    class NumericalBehaviour {

        @Test
        @DisplayName("the result is stable across a range of sensible bump sizes")
        void stableAcrossBumpSizes() {
            // If delta moved materially with the bump, the choice of bump would be doing the
            // work rather than the model. This is the check that the default sits in the flat
            // region between truncation error and floating-point noise.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(CALL, 100).build();
            PortfolioValuationService valuation = new PortfolioValuationService(
                    PricingService.builder()
                            .register(new SpotPriceModel())
                            .register(new BlackScholesModel()).build(),
                    InstrumentCatalog.of(AAPL_STOCK, MSFT_STOCK, AAPL_CALL));

            double coarse = new SensitivityCalculator(valuation, 1e-3)
                    .delta(portfolio, AAPL, market(), VALUATION);
            double middle = new SensitivityCalculator(valuation, 1e-4)
                    .delta(portfolio, AAPL, market(), VALUATION);
            double fine = new SensitivityCalculator(valuation, 1e-5)
                    .delta(portfolio, AAPL, market(), VALUATION);

            assertThat(middle).isCloseTo(coarse, within(0.05));
            assertThat(fine).isCloseTo(middle, within(0.05));
        }

        @Test
        @DisplayName("gamma stays stable across a range of sensible bump sizes")
        void gammaStableAcrossBumpSizes() {
            // The check docs/DESIGN_PROPOSAL.md section 5.3.1 asks be written "early": Gamma
            // is a second-order difference, so a badly chosen bump shows up as instability
            // here far more readily than it does for Delta. If this test were flaky, the
            // whole "every Greek for free" story would be weaker than advertised.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(CALL, 100).build();
            PortfolioValuationService valuation = new PortfolioValuationService(
                    PricingService.builder()
                            .register(new SpotPriceModel())
                            .register(new BlackScholesModel()).build(),
                    InstrumentCatalog.of(AAPL_STOCK, MSFT_STOCK, AAPL_CALL));

            double coarse = new SensitivityCalculator(valuation, 1e-3)
                    .gamma(portfolio, AAPL, market(), VALUATION);
            double middle = new SensitivityCalculator(valuation, 1e-4)
                    .gamma(portfolio, AAPL, market(), VALUATION);
            double fine = new SensitivityCalculator(valuation, 1e-5)
                    .gamma(portfolio, AAPL, market(), VALUATION);

            assertThat(middle).isCloseTo(coarse, within(0.1));
            assertThat(fine).isCloseTo(middle, within(0.1));
        }

        @Test
        @DisplayName("the base market is never modified by computing a sensitivity")
        void baseMarketUnchanged() {
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 100).build();
            MarketDataSnapshot base = market();

            calculator().delta(portfolio, AAPL, base, VALUATION);

            assertThat(base.spot(AAPL)).isCloseTo(200.0, within(1e-12));
        }

        @Test
        @DisplayName("computing the same delta twice gives the same number")
        void deterministic() {
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(CALL, 100).build();

            assertThat(calculator().delta(portfolio, AAPL, market(), VALUATION))
                    .isEqualTo(calculator().delta(portfolio, AAPL, market(), VALUATION));
        }

        @Test
        @DisplayName("an unknown underlying fails rather than reporting zero delta")
        void unknownUnderlyingThrows() {
            // Without the explicit spot read, both shocked markets would equal the base and
            // the answer would be a confident, wrong zero.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 100).build();

            assertThatThrownBy(() -> calculator()
                    .delta(portfolio, InstrumentId.of("GOOG"), market(), VALUATION))
                    .isInstanceOf(MarketDataSnapshot.MissingMarketDataException.class);
        }

        @Test
        @DisplayName("rejects a non-positive bump size")
        void rejectsBadBump() {
            PortfolioValuationService valuation = new PortfolioValuationService(
                    PricingService.builder().register(new SpotPriceModel()).build(),
                    InstrumentCatalog.of(AAPL_STOCK));

            assertThatThrownBy(() -> new SensitivityCalculator(valuation, 0.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("vega refuses a volatility too close to bumpVolatility's zero floor")
        void vegaRejectsVolatilityBelowTheBump() {
            // MarketShock.bumpVolatility floors the shocked value at zero. A quoted volatility
            // below one vol point means the down-shock lands at exactly 0.0 instead of
            // volatility - 0.01, so the two shocks are no longer symmetric around the base and
            // a central difference over them would be a silently biased one-sided estimate.
            // Caught here rather than producing a plausible, wrong number.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(CALL, 1).build();
            MarketDataSnapshot lowVolMarket = MarketDataSnapshot.builder(VALUATION)
                    .spot(AAPL, 200.0)
                    .volatility(AAPL, 0.005)
                    .discountRate(Currency.USD, 0.04)
                    .build();

            assertThatThrownBy(() -> calculator().vega(portfolio, AAPL, lowVolMarket, VALUATION))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("zero floor");
        }

        @Test
        @DisplayName("vega is fine at a volatility exactly on the boundary")
        void vegaAcceptsVolatilityExactlyAtTheBump() {
            // At exactly DEFAULT_VOLATILITY_BUMP, the down-shock lands at exactly 0.0 too - the
            // floor triggers, but that IS volatility - bump, so it is symmetric and correct
            // rather than biased. The guard must not reject this boundary case along with the
            // genuinely broken one below it.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(CALL, 1).build();
            MarketDataSnapshot boundaryMarket = MarketDataSnapshot.builder(VALUATION)
                    .spot(AAPL, 200.0)
                    .volatility(AAPL, SensitivityCalculator.DEFAULT_VOLATILITY_BUMP)
                    .discountRate(Currency.USD, 0.04)
                    .build();

            assertThat(calculator().vega(portfolio, AAPL, boundaryMarket, VALUATION))
                    .isNotNaN();
        }
    }

    @Nested
    @DisplayName("scenario impact")
    class ScenarioImpact {

        @Test
        @DisplayName("a 30% equity crash costs 30% of a stock portfolio")
        void equityCrash() {
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 100).build();

            Money change = calculator().valueChangeUnder(
                    portfolio, MarketShock.scaleAllSpots(0.70), market(), VALUATION);

            // 20,000 falls to 14,000.
            assertThat(change).isEqualTo(Money.of("-6000.00", Currency.USD));
        }

        @Test
        @DisplayName("rising volatility raises the value of a long option position")
        void volatilityHelpsLongOptions() {
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(CALL, 100).build();

            Money change = calculator().valueChangeUnder(
                    portfolio, MarketShock.scaleAllVolatilities(1.50), market(), VALUATION);

            assertThat(change.isPositive()).isTrue();
        }
    }

    @Nested
    @DisplayName("rate and FX sensitivities")
    class RatesAndFx {

        // 2024 is a leap year, so a calendar year from the valuation date is 366 days. The
        // year fraction is therefore computed rather than assumed to be 1.0 - hard-coding it
        // would make these reference values quietly wrong by a quarter of a percent.
        private static final LocalDate MATURITY = LocalDate.of(2025, 1, 15);
        private static final double YEARS =
                DayCountConvention.ACT_365F.yearFraction(VALUATION, MATURITY);
        private static final CurrencyPair EURUSD = CurrencyPair.parse("EUR/USD");

        private static final Bond ZERO_COUPON = Bond.builder()
                .id("ZCB")
                .faceValue(Money.of("1000000", Currency.USD))
                .couponRate("0")
                .couponFrequency(Frequency.ANNUAL)
                .calendar(HolidayCalendar.alwaysOpen())
                .issueDate(VALUATION)
                .maturityDate(MATURITY)
                .build();

        private static final FxForward FORWARD =
                FxForward.buy("FWD", EURUSD, "1000000", "1.08", MATURITY);

        /** A second forward on the same pair, struck somewhere else entirely. */
        private static final FxForward OFF_MARKET_FORWARD =
                FxForward.buy("FWD-OFF", EURUSD, "1000000", "1.50", MATURITY);

        private static SensitivityCalculator rateCalculator() {
            return new SensitivityCalculator(new PortfolioValuationService(
                    PricingService.builder()
                            .register(new SpotPriceModel())
                            .register(new DiscountedCashflowModel<>(Bond.class))
                            .register(new DiscountedCashflowModel<>(FxForward.class))
                            .build(),
                    InstrumentCatalog.of(AAPL_STOCK, ZERO_COUPON, FORWARD, OFF_MARKET_FORWARD)));
        }

        /** USD 5%, EUR 3%, EUR/USD 1.10. */
        private static MarketDataSnapshot rateMarket() {
            return MarketDataSnapshot.builder(VALUATION)
                    .spot(AAPL, 200.0)
                    .discountRate(Currency.USD, 0.05)
                    .discountRate(Currency.EUR, 0.03)
                    .fxRate(EURUSD, 1.10)
                    .build();
        }

        @Test
        @DisplayName("a zero-coupon bond's DV01 is minus its maturity times its value")
        void zeroCouponDv01() {
            // PV = F e^-rT, so dPV/dr = -T x PV, and one basis point of that is -T x PV x 1e-4.
            // Known in closed form, which is the point: the numerical machinery is checked
            // against arithmetic that owes it nothing.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(ZERO_COUPON.id(), 1).build();

            double presentValue = 1_000_000.0 * Math.exp(-0.05 * YEARS);
            double expected = -YEARS * presentValue * 1e-4;

            assertThat(rateCalculator().dv01(portfolio, Currency.USD, rateMarket(), VALUATION))
                    .isCloseTo(expected, within(1e-6));
        }

        @Test
        @DisplayName("an equity book has no interest-rate sensitivity")
        void equityHasNoDv01() {
            // The negative control. A risk number that is non-zero for everything is not
            // measuring anything.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 1_000).build();

            assertThat(rateCalculator().dv01(portfolio, Currency.USD, rateMarket(), VALUATION))
                    .isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("an unknown currency fails rather than reporting no risk")
        void unknownCurrencyFails() {
            // Without the read-first guard this returns exactly 0.0, because neither shocked
            // market differs from the base - a missing curve would look like a hedged book.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(ZERO_COUPON.id(), 1).build();

            assertThatThrownBy(() -> rateCalculator()
                    .dv01(portfolio, Currency.JPY, rateMarket(), VALUATION))
                    .isInstanceOf(MarketDataSnapshot.MissingMarketDataException.class);
        }

        @Test
        @DisplayName("an FX forward's delta is its discounted base notional")
        void forwardFxDelta() {
            // Value in USD = N e^-r_eur T x S - K e^-r_usd T, and only the first term contains
            // S. So d/dS is N e^-r_eur T: the euro amount the book is exposed to, discounted.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(FORWARD.id(), 1).build();

            double expected = 1_000_000.0 * Math.exp(-0.03 * YEARS);

            assertThat(rateCalculator().fxDelta(portfolio, EURUSD, rateMarket(), VALUATION))
                    .isCloseTo(expected, within(0.01));
        }

        @Test
        @DisplayName("FX delta does not depend on the rate the forward was struck at")
        void fxDeltaIgnoresTheStrike() {
            // Falls out of the algebra above - the strike sits in a term with no S in it - and
            // is worth asserting because it is the kind of independence a numerical method can
            // lose without any test noticing.
            SensitivityCalculator calculator = rateCalculator();
            MarketDataSnapshot market = rateMarket();

            double atMarket = calculator.fxDelta(Portfolio.builder(BOOK, Currency.USD)
                    .position(FORWARD.id(), 1).build(), EURUSD, market, VALUATION);
            double offMarket = calculator.fxDelta(Portfolio.builder(BOOK, Currency.USD)
                    .position(OFF_MARKET_FORWARD.id(), 1).build(), EURUSD, market, VALUATION);

            assertThat(offMarket).isCloseTo(atMarket, within(0.01));
        }

        @Test
        @DisplayName("FX delta is the same whichever direction the snapshot stores")
        void fxDeltaIsDirectionAgnostic() {
            // A snapshot holds EUR/USD or USD/EUR, never both. Matching only the exact key
            // would have made this case report a delta of exactly zero for a book fully
            // exposed to the euro, which is the worst answer a risk number can give.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(FORWARD.id(), 1).build();

            MarketDataSnapshot inverted = MarketDataSnapshot.builder(VALUATION)
                    .spot(AAPL, 200.0)
                    .discountRate(Currency.USD, 0.05)
                    .discountRate(Currency.EUR, 0.03)
                    .fxRate(EURUSD.inverse(), 1.0 / 1.10)
                    .build();

            SensitivityCalculator calculator = rateCalculator();

            assertThat(calculator.fxDelta(portfolio, EURUSD, inverted, VALUATION))
                    .isCloseTo(calculator.fxDelta(portfolio, EURUSD, rateMarket(), VALUATION),
                            within(0.01));
        }

        @Test
        @DisplayName("a domestic book has no FX delta")
        void domesticBookHasNoFxDelta() {
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 1_000).build();

            assertThat(rateCalculator().fxDelta(portfolio, EURUSD, rateMarket(), VALUATION))
                    .isCloseTo(0.0, within(1e-9));
        }
    }
}
