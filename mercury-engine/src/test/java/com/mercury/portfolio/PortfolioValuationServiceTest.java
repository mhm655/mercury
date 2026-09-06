package com.mercury.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.instrument.EuropeanOption;
import com.mercury.instrument.Stock;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.marketdata.MarketShock;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.model.BlackScholesModel;
import com.mercury.pricing.model.SpotPriceModel;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PortfolioValuationServiceTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final InstrumentId MSFT = InstrumentId.of("MSFT");
    private static final InstrumentId CALL = InstrumentId.of("AAPL-C-200");
    private static final LocalDate VALUATION = LocalDate.of(2024, 1, 15);
    private static final PortfolioId BOOK = PortfolioId.of("EQUITY-BOOK");

    private static final Stock AAPL_STOCK = Stock.of("AAPL", Currency.USD);
    private static final Stock MSFT_STOCK = Stock.of("MSFT", Currency.USD);
    private static final EuropeanOption AAPL_CALL = EuropeanOption.call(
            "AAPL-C-200", AAPL, Price.of("200"), LocalDate.of(2025, 1, 15), Currency.USD);

    private static PortfolioValuationService service() {
        return new PortfolioValuationService(
                PricingService.builder()
                        .register(new SpotPriceModel())
                        .register(new BlackScholesModel())
                        .build(),
                InstrumentCatalog.of(AAPL_STOCK, MSFT_STOCK, AAPL_CALL));
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
    @DisplayName("valuation")
    class Valuation {

        @Test
        @DisplayName("a stock-only portfolio values to quantity times spot")
        void stockOnlyPortfolio() {
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 100)
                    .position(MSFT, 50)
                    .build();

            PortfolioValuation valuation = service().value(portfolio, market(), VALUATION);

            // 100 x 200 + 50 x 400 = 20,000 + 20,000 = 40,000.
            assertThat(valuation.totalValue()).isEqualTo(Money.of("40000.00", Currency.USD));
            assertThat(valuation.lines()).hasSize(2);
            assertThat(valuation.valuationDate()).isEqualTo(VALUATION);
        }

        @Test
        @DisplayName("stocks and options value through the same call, with no type check")
        void mixedPortfolio() {
            // The vertical slice working end to end: two unrelated instrument types, two
            // unrelated pricing models, one uniform valuation.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 100)
                    .position(CALL, 10)
                    .build();

            PortfolioValuation valuation = service().value(portfolio, market(), VALUATION);

            assertThat(valuation.lines()).hasSize(2);
            assertThat(valuation.lines().get(0).unitValue().model()).isEqualTo(SpotPriceModel.NAME);
            assertThat(valuation.lines().get(1).unitValue().model())
                    .isEqualTo(BlackScholesModel.NAME);
            // The stock leg alone is 20,000; the option leg adds a positive amount.
            assertThat(valuation.totalValue()).isGreaterThan(Money.of("20000.00", Currency.USD));
        }

        @Test
        @DisplayName("a short position contributes negative value")
        void shortPosition() {
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 100)
                    .position(MSFT, -50)
                    .build();

            PortfolioValuation valuation = service().value(portfolio, market(), VALUATION);

            // 100 x 200 - 50 x 400 = 20,000 - 20,000 = 0.
            assertThat(valuation.totalValue()).isEqualTo(Money.of("0.00", Currency.USD));
            assertThat(valuation.lines().get(1).marketValue().isNegative()).isTrue();
        }

        @Test
        @DisplayName("an empty portfolio values to zero, not an error")
        void emptyPortfolio() {
            Portfolio empty = Portfolio.builder(BOOK, Currency.USD).build();

            PortfolioValuation valuation = service().value(empty, market(), VALUATION);

            assertThat(valuation.totalValue()).isEqualTo(Money.zero(Currency.USD));
            assertThat(valuation.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the total always equals the sum of its lines")
        void totalReconcilesToLines() {
            // Enforced in the result's constructor, so a valuation cannot exist whose headline
            // number disagrees with the detail behind it.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 137).position(MSFT, -89).position(CALL, 23).build();

            PortfolioValuation valuation = service().value(portfolio, market(), VALUATION);

            Money summed = valuation.lines().stream()
                    .map(PortfolioValuation.PositionValuation::marketValue)
                    .reduce(Money.zero(Currency.USD), Money::plus);
            assertThat(summed).isEqualTo(valuation.totalValue());
        }
    }

    @Nested
    @DisplayName("portfolio invariants")
    class Invariants {

        @Test
        @DisplayName("two holdings of one instrument combine into a single position")
        void holdingsCombine() {
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 60)
                    .position(AAPL, 40)
                    .build();

            assertThat(portfolio.size()).isEqualTo(1);
            assertThat(service().value(portfolio, market(), VALUATION).totalValue())
                    .isEqualTo(Money.of("20000.00", Currency.USD));
        }

        @Test
        @DisplayName("positions that net to flat are dropped")
        void flatPositionsDropped() {
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 100)
                    .position(AAPL, -100)
                    .build();

            assertThat(portfolio.isEmpty()).isTrue();
            assertThat(portfolio.positionIn(AAPL)).isEmpty();
        }
    }

    @Nested
    @DisplayName("failure modes are loud")
    class Failures {

        @Test
        @DisplayName("a position in an unknown instrument fails rather than being skipped")
        void unknownInstrumentThrows() {
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(InstrumentId.of("GOOG"), 100).build();

            assertThatThrownBy(() -> service().value(portfolio, market(), VALUATION))
                    .isInstanceOf(InstrumentCatalog.UnknownInstrumentException.class)
                    .hasMessageContaining("looks complete but is not");
        }

        @Test
        @DisplayName("a foreign-currency position is rejected rather than assumed")
        void foreignCurrencyRejected() {
            // Converting at an assumed rate would produce a plausible wrong total, which is
            // worse than refusing. FX conversion arrives at M7.
            Stock european = Stock.of("SAP", Currency.EUR);
            PortfolioValuationService withEuro = new PortfolioValuationService(
                    PricingService.builder().register(new SpotPriceModel()).build(),
                    InstrumentCatalog.of(AAPL_STOCK, european));
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(InstrumentId.of("SAP"), 100).build();
            MarketDataSnapshot withSap = MarketDataSnapshot.builder()
                    .spot(InstrumentId.of("SAP"), 150.0).build();

            assertThatThrownBy(() -> withEuro.value(portfolio, withSap, VALUATION))
                    .isInstanceOf(PortfolioValuationService.CurrencyNotSupportedException.class)
                    .hasMessageContaining("EUR")
                    .hasMessageContaining("M7");
        }

        @Test
        @DisplayName("a catalog with duplicate ids is rejected at construction")
        void duplicateInstrumentIdsRejected() {
            assertThatThrownBy(() -> InstrumentCatalog.of(
                    Stock.of("AAPL", Currency.USD), Stock.of("AAPL", Currency.EUR)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("share the id");
        }
    }

    @Nested
    @DisplayName("revaluation under a shocked market")
    class Revaluation {

        @Test
        @DisplayName("the same portfolio values differently under a shock")
        void valuesChangeUnderShock() {
            // The pattern every Greek and every stress scenario uses: value the base case,
            // shock the market, value again, compare. Nothing else is needed.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 100).build();
            MarketDataSnapshot base = market();
            MarketDataSnapshot crashed = base.withShock(MarketShock.scaleAllSpots(0.70));

            Money baseValue = service().value(portfolio, base, VALUATION).totalValue();
            Money crashedValue = service().value(portfolio, crashed, VALUATION).totalValue();

            assertThat(baseValue).isEqualTo(Money.of("20000.00", Currency.USD));
            assertThat(crashedValue).isEqualTo(Money.of("14000.00", Currency.USD));
        }

        @Test
        @DisplayName("valuing the base market twice gives the same answer")
        void valuationIsDeterministic() {
            // Purity, asserted. Bump-and-revalue Greeks depend on an unchanged market giving
            // an unchanged answer; without it a numerical derivative measures noise.
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 100).position(CALL, 10).build();

            assertThat(service().value(portfolio, market(), VALUATION).totalValue())
                    .isEqualTo(service().value(portfolio, market(), VALUATION).totalValue());
        }

        @Test
        @DisplayName("shocking the market leaves the original snapshot untouched")
        void baseMarketSurvives() {
            Portfolio portfolio = Portfolio.builder(BOOK, Currency.USD)
                    .position(AAPL, 100).build();
            MarketDataSnapshot base = market();

            service().value(portfolio, base.withShock(MarketShock.scaleAllSpots(0.70)), VALUATION);

            assertThat(service().value(portfolio, base, VALUATION).totalValue())
                    .isEqualTo(Money.of("20000.00", Currency.USD));
        }
    }
}
