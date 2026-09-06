package com.mercury.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.instrument.EuropeanOption;
import com.mercury.instrument.Stock;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.marketdata.MarketShock;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioValuationService;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.model.BlackScholesModel;
import com.mercury.pricing.model.NormalDistribution;
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
        return MarketDataSnapshot.builder()
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
    }

    @Nested
    @DisplayName("cross-validation against the closed form")
    class CrossValidation {

        @Test
        @DisplayName("an option's numerical delta matches N(d1)")
        void optionDeltaMatchesAnalytic() {
            // The analytic delta of a call is N(d1). Two independent routes to the same
            // number - one by revaluation, one by formula - agreeing is far stronger evidence
            // than either on its own, and it is the check the design schedules for M10.
            double spot = 200.0;
            double strike = 200.0;
            double volatility = 0.25;
            double rate = 0.04;
            double years = AAPL_CALL.yearsToExpiry(VALUATION);

            double d1 = (Math.log(spot / strike) + (rate + 0.5 * volatility * volatility) * years)
                    / (volatility * Math.sqrt(years));
            double analyticDelta = NormalDistribution.cumulative(d1);

            // 100 contracts of 100 shares each, so the portfolio delta is 10,000 x N(d1).
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(CALL, 100).build();
            double numericalDelta = calculator().delta(portfolio, AAPL, market(), VALUATION);

            assertThat(numericalDelta).isCloseTo(10_000.0 * analyticDelta, within(1.0));
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
}
