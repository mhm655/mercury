package com.mercury.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.instrument.Stock;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioValuationService;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.model.SpotPriceModel;
import com.mercury.risk.QuantileConfidenceInterval;
import com.mercury.risk.SensitivityCalculator;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MonteCarloVaRCalculatorTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final LocalDate VALUATION = LocalDate.of(2024, 1, 15);
    private static final PortfolioId BOOK = PortfolioId.of("BOOK");
    private static final Stock AAPL_STOCK = Stock.of("AAPL", Currency.USD);

    private static SensitivityCalculator sensitivities() {
        return new SensitivityCalculator(new PortfolioValuationService(
                PricingService.builder().register(new SpotPriceModel()).build(),
                InstrumentCatalog.of(AAPL_STOCK)));
    }

    private static MonteCarloVaRCalculator calculator(long seed) {
        return new MonteCarloVaRCalculator(sensitivities(), seed);
    }

    private static MarketDataSnapshot market() {
        return MarketDataSnapshot.builder(VALUATION).spot(AAPL, 100.0).build();
    }

    private static Portfolio book(long quantity) {
        return Portfolio.builder(BOOK, Currency.USD).position(AAPL, quantity).build();
    }

    @Test
    void expectedShortfallIsNeverSmallerThanValueAtRisk() {
        MonteCarloRiskResult result = calculator(1).simulate(
                book(1_000), AAPL, 0.0, 0.25, 1.0 / 365, 50_000, market(), VALUATION, 0.99);

        assertThat(result.expectedShortfall().isGreaterThan(result.valueAtRisk())
                || result.expectedShortfall().equals(result.valueAtRisk())).isTrue();
    }

    @Test
    void reportsThePathCountItRan() {
        MonteCarloRiskResult result = calculator(1).simulate(
                book(1_000), AAPL, 0.0, 0.25, 1.0 / 365, 10_000, market(), VALUATION, 0.95);

        assertThat(result.pathCount()).isEqualTo(10_000);
    }

    @Test
    void reportsTheSeedItRan() {
        // The point of an injected seed is that a specific figure can be defended and
        // reproduced later - which only works if the result says which seed produced it,
        // rather than depending on whoever called simulate() to remember.
        MonteCarloRiskResult result = calculator(123).simulate(
                book(1_000), AAPL, 0.0, 0.25, 1.0 / 365, 1_000, market(), VALUATION, 0.95);

        assertThat(result.seed()).isEqualTo(123);
    }

    @Test
    void theConfidenceIntervalBracketsTheValueAtRisk() {
        MonteCarloRiskResult result = calculator(1).simulate(
                book(1_000), AAPL, 0.0, 0.25, 1.0 / 365, 50_000, market(), VALUATION, 0.99);

        assertThat(result.valueAtRiskConfidenceInterval().lowerBound()
                .isGreaterThan(result.valueAtRisk())).isFalse();
        assertThat(result.valueAtRiskConfidenceInterval().upperBound()
                .isLessThan(result.valueAtRisk())).isFalse();
    }

    @Test
    void theConfidenceIntervalNarrowsWithMorePaths() {
        QuantileConfidenceInterval coarse = calculator(1).simulate(
                book(1_000), AAPL, 0.0, 0.25, 1.0 / 365, 1_000, market(), VALUATION, 0.99)
                .valueAtRiskConfidenceInterval();
        QuantileConfidenceInterval fine = calculator(1).simulate(
                book(1_000), AAPL, 0.0, 0.25, 1.0 / 365, 200_000, market(), VALUATION, 0.99)
                .valueAtRiskConfidenceInterval();

        Money coarseWidth = coarse.upperBound().minus(coarse.lowerBound());
        Money fineWidth = fine.upperBound().minus(fine.lowerBound());
        assertThat(fineWidth.isLessThan(coarseWidth)).isTrue();
    }

    @Test
    void sameSeedGivesTheSameResult() {
        MonteCarloRiskResult first = calculator(42).simulate(
                book(1_000), AAPL, 0.0, 0.25, 1.0 / 365, 10_000, market(), VALUATION, 0.95);
        MonteCarloRiskResult second = calculator(42).simulate(
                book(1_000), AAPL, 0.0, 0.25, 1.0 / 365, 10_000, market(), VALUATION, 0.95);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void sameSeedGivesTheSameResultOnOneWorkerOrEight() {
        // The reproducibility test docs/DESIGN_PROPOSAL.md section 8 names: "same seed ->
        // identical VaR across 1 and 8 workers". Exact equality, not a tolerance - a path
        // count that is not a multiple of the block size, so the short last block is covered.
        int pathCount = 3 * PathBlocks.BLOCK_SIZE + 17;
        SensitivityCalculator sensitivities = sensitivities();

        MonteCarloRiskResult sequential = new MonteCarloVaRCalculator(sensitivities, 42)
                .simulate(book(1_000), AAPL, 0.0, 0.25, 1.0 / 365, pathCount, market(), VALUATION, 0.99);
        try (SimulationWorkers one = SimulationWorkers.parallel(1);
             SimulationWorkers eight = SimulationWorkers.parallel(8)) {
            MonteCarloRiskResult onOne = new MonteCarloVaRCalculator(sensitivities, 42, one)
                    .simulate(book(1_000), AAPL, 0.0, 0.25, 1.0 / 365, pathCount, market(), VALUATION, 0.99);
            MonteCarloRiskResult onEight = new MonteCarloVaRCalculator(sensitivities, 42, eight)
                    .simulate(book(1_000), AAPL, 0.0, 0.25, 1.0 / 365, pathCount, market(), VALUATION, 0.99);

            assertThat(onOne).isEqualTo(sequential);
            assertThat(onEight).isEqualTo(sequential);
        }
    }

    @Test
    void aMissingSpotRaisedOnAWorkerReachesTheCallerAsItself() {
        // The spot is read up front, so this book holds a second instrument the market does
        // not price - the failure then happens during revaluation, on a worker thread.
        Stock msft = Stock.of("MSFT", Currency.USD);
        SensitivityCalculator sensitivities = new SensitivityCalculator(new PortfolioValuationService(
                PricingService.builder().register(new SpotPriceModel()).build(),
                InstrumentCatalog.of(AAPL_STOCK, msft)));
        Portfolio twoStocks = Portfolio.builder(BOOK, Currency.USD)
                .position(AAPL, 1_000).position(msft.id(), 10).build();

        try (SimulationWorkers workers = SimulationWorkers.parallel(4)) {
            assertThatThrownBy(() -> new MonteCarloVaRCalculator(sensitivities, 1, workers).simulate(
                    twoStocks, AAPL, 0.0, 0.25, 1.0 / 365, 10_000, market(), VALUATION, 0.95))
                    .isInstanceOf(MarketDataSnapshot.MissingMarketDataException.class);
        }
    }

    @Test
    void aFlatBookHasNoValueAtRiskOrExpectedShortfall() {
        Portfolio empty = Portfolio.builder(BOOK, Currency.USD).build();

        MonteCarloRiskResult result = calculator(1).simulate(
                empty, AAPL, 0.0, 0.25, 1.0 / 365, 1_000, market(), VALUATION, 0.95);

        assertThat(result.valueAtRisk()).isEqualTo(Money.zero(Currency.USD));
        assertThat(result.expectedShortfall()).isEqualTo(Money.zero(Currency.USD));
    }

    @Test
    void higherVolatilityProducesALargerValueAtRisk() {
        // A wider terminal distribution must widen the loss tail - a sanity check that the
        // simulated scenarios actually respond to the volatility they were given, not a
        // fixed or ignored parameter.
        MonteCarloRiskResult calm = calculator(1).simulate(
                book(1_000), AAPL, 0.0, 0.10, 1.0 / 365, 50_000, market(), VALUATION, 0.99);
        MonteCarloRiskResult volatile_ = calculator(1).simulate(
                book(1_000), AAPL, 0.0, 0.60, 1.0 / 365, 50_000, market(), VALUATION, 0.99);

        assertThat(volatile_.valueAtRisk().isGreaterThan(calm.valueAtRisk())).isTrue();
    }

    @Test
    void convergesTowardTheAnalyticStandardDeviationAtHighConfidence() {
        // At 1 day, drift is negligible, so the P&L distribution is close to Normal(0, spot *
        // vol * sqrt(T) * quantity). The 99% VaR of a standard normal is 2.326 standard
        // deviations - a real, independent number this simulation's output can be checked
        // against, not just "some plausible-looking figure."
        double spot = 100.0;
        double volatility = 0.25;
        double years = 1.0 / 365;
        long quantity = 10_000;
        double analyticStdDev = quantity * spot * volatility * Math.sqrt(years);
        double analyticVar99 = 2.326 * analyticStdDev;

        MonteCarloRiskResult result = calculator(5).simulate(
                book(quantity), AAPL, 0.0, volatility, years, 500_000, market(), VALUATION, 0.99);

        assertThat(result.valueAtRisk().amount().doubleValue())
                .isCloseTo(analyticVar99, within(analyticVar99 * 0.10));
    }

    @Test
    void rejectsANonPositivePathCount() {
        assertThatThrownBy(() -> calculator(1).simulate(
                book(1_000), AAPL, 0.0, 0.25, 1.0 / 365, 0, market(), VALUATION, 0.95))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAConfidenceLevelOutsideZeroAndOne() {
        assertThatThrownBy(() -> calculator(1).simulate(
                book(1_000), AAPL, 0.0, 0.25, 1.0 / 365, 1_000, market(), VALUATION, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownUnderlyingFailsRatherThanReportingNoRisk() {
        assertThatThrownBy(() -> calculator(1).simulate(
                book(1_000), InstrumentId.of("GOOG"), 0.0, 0.25, 1.0 / 365, 1_000,
                market(), VALUATION, 0.95))
                .isInstanceOf(MarketDataSnapshot.MissingMarketDataException.class);
    }
}
