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
import com.mercury.risk.SensitivityCalculator;
import com.mercury.simulation.CorrelatedMonteCarloVaRCalculator.RiskFactor;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CorrelatedMonteCarloVaRCalculatorTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final InstrumentId MSFT = InstrumentId.of("MSFT");
    private static final LocalDate VALUATION = LocalDate.of(2024, 1, 15);
    private static final PortfolioId BOOK = PortfolioId.of("BOOK");
    private static final double YEARS = 1.0 / 365;

    private static SensitivityCalculator sensitivities() {
        return new SensitivityCalculator(new PortfolioValuationService(
                PricingService.builder().register(new SpotPriceModel()).build(),
                InstrumentCatalog.of(Stock.of("AAPL", Currency.USD), Stock.of("MSFT", Currency.USD))));
    }

    private static CorrelatedMonteCarloVaRCalculator calculator(long seed) {
        return new CorrelatedMonteCarloVaRCalculator(sensitivities(), seed);
    }

    private static MarketDataSnapshot market() {
        return MarketDataSnapshot.builder(VALUATION).spot(AAPL, 100.0).spot(MSFT, 200.0).build();
    }

    private static Portfolio longBoth(long aaplQty, long msftQty) {
        return Portfolio.builder(BOOK, Currency.USD)
                .position(AAPL, aaplQty).position(MSFT, msftQty).build();
    }

    private static List<RiskFactor> factors(double volAapl, double volMsft) {
        return List.of(new RiskFactor(AAPL, 0.0, volAapl), new RiskFactor(MSFT, 0.0, volMsft));
    }

    private static CorrelationMatrix correlation(double rho) {
        return CorrelationMatrix.of(new double[][] {{1.0, rho}, {rho, 1.0}});
    }

    @Test
    void sameSeedGivesTheSameResult() {
        MonteCarloRiskResult first = calculator(42).simulate(longBoth(1_000, 500),
                factors(0.25, 0.20), correlation(0.5), YEARS, 10_000, market(), VALUATION, 0.95);
        MonteCarloRiskResult second = calculator(42).simulate(longBoth(1_000, 500),
                factors(0.25, 0.20), correlation(0.5), YEARS, 10_000, market(), VALUATION, 0.95);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void sameSeedGivesTheSameResultOnOneWorkerOrEight() {
        int pathCount = 3 * PathBlocks.BLOCK_SIZE + 17;
        SensitivityCalculator sensitivities = sensitivities();
        Portfolio portfolio = longBoth(1_000, 500);
        List<RiskFactor> factors = factors(0.25, 0.20);
        CorrelationMatrix correlation = correlation(0.5);

        MonteCarloRiskResult sequential = new CorrelatedMonteCarloVaRCalculator(sensitivities, 42)
                .simulate(portfolio, factors, correlation, YEARS, pathCount, market(), VALUATION, 0.99);
        try (SimulationWorkers one = SimulationWorkers.parallel(1);
             SimulationWorkers eight = SimulationWorkers.parallel(8)) {
            MonteCarloRiskResult onOne = new CorrelatedMonteCarloVaRCalculator(sensitivities, 42, one)
                    .simulate(portfolio, factors, correlation, YEARS, pathCount, market(), VALUATION, 0.99);
            MonteCarloRiskResult onEight = new CorrelatedMonteCarloVaRCalculator(sensitivities, 42, eight)
                    .simulate(portfolio, factors, correlation, YEARS, pathCount, market(), VALUATION, 0.99);

            assertThat(onOne).isEqualTo(sequential);
            assertThat(onEight).isEqualTo(sequential);
        }
    }

    @Test
    void zeroCorrelationApproximatesIndependentRiskAddedInQuadrature() {
        // At zero correlation and negligible drift, the joint P&L is close to Normal(0, sigma)
        // with sigma = sqrt((qtyA*spotA*volA)^2 + (qtyB*spotB*volB)^2)*sqrt(years) - independent
        // variances add, standard deviations do not. A real, independent number to check
        // against, the same cross-validation MonteCarloVaRCalculatorTest already does for one
        // factor.
        long aaplQty = 10_000;
        long msftQty = 5_000;
        double volAapl = 0.25;
        double volMsft = 0.20;
        double sigmaAapl = aaplQty * 100.0 * volAapl * Math.sqrt(YEARS);
        double sigmaMsft = msftQty * 200.0 * volMsft * Math.sqrt(YEARS);
        double combinedSigma = Math.sqrt(sigmaAapl * sigmaAapl + sigmaMsft * sigmaMsft);
        double analyticVar99 = 2.326 * combinedSigma;

        MonteCarloRiskResult result = calculator(5).simulate(longBoth(aaplQty, msftQty),
                factors(volAapl, volMsft), correlation(0.0), YEARS, 500_000, market(), VALUATION, 0.99);

        assertThat(result.valueAtRisk().amount().doubleValue())
                .isCloseTo(analyticVar99, within(analyticVar99 * 0.10));
    }

    @Test
    void higherCorrelationIncreasesValueAtRiskForABookLongBoth() {
        // A book long both assets concentrates risk as they move together more: at rho near
        // -1 the two positions partially hedge each other, at rho near +1 there is no
        // diversification benefit left. VaR should increase monotonically as rho rises.
        Portfolio portfolio = longBoth(5_000, 5_000);
        List<RiskFactor> theFactors = factors(0.25, 0.25);

        Money negativelyCorrelated = calculator(7).simulate(portfolio, theFactors, correlation(-0.9),
                YEARS, 200_000, market(), VALUATION, 0.99).valueAtRisk();
        Money uncorrelated = calculator(7).simulate(portfolio, theFactors, correlation(0.0),
                YEARS, 200_000, market(), VALUATION, 0.99).valueAtRisk();
        Money positivelyCorrelated = calculator(7).simulate(portfolio, theFactors, correlation(0.9),
                YEARS, 200_000, market(), VALUATION, 0.99).valueAtRisk();

        assertThat(uncorrelated.isGreaterThan(negativelyCorrelated)).isTrue();
        assertThat(positivelyCorrelated.isGreaterThan(uncorrelated)).isTrue();
    }

    @Test
    void nearPerfectNegativeCorrelationOfEqualDollarLongPositionsHedgesToNearZero() {
        // Long equal dollar amounts of A and B, both the same volatility, almost perfectly
        // negatively correlated (exactly -1.0 is singular - see CorrelationMatrix - so -0.999
        // is the closest this can express): when A moves up, B moves down by nearly the same
        // proportion, so one leg's gain is nearly the other's loss and the book's P&L is close
        // to riskless - the extreme case the monotonicity test above only samples the middle
        // of. (A long/short pair would instead amplify a negatively-correlated move, not hedge
        // it - both legs gain together when one rises as the other falls.)
        Portfolio hedged = Portfolio.builder(BOOK, Currency.USD)
                .position(AAPL, 2_000).position(MSFT, 1_000).build(); // 2,000*100 = 1,000*200
        List<RiskFactor> equalVol = factors(0.25, 0.25);

        MonteCarloRiskResult result = calculator(11).simulate(hedged, equalVol, correlation(-0.999),
                YEARS, 200_000, market(), VALUATION, 0.99);

        // Not exactly zero (GBM's log-normal terminal values are not exactly linear in the
        // shock), but far smaller than either leg's own standalone VaR would be.
        double soloVar = calculator(11).simulate(Portfolio.builder(BOOK, Currency.USD)
                        .position(AAPL, 2_000).build(), List.of(new RiskFactor(AAPL, 0.0, 0.25)),
                CorrelationMatrix.of(new double[][] {{1.0}}), YEARS, 200_000, market(), VALUATION, 0.99)
                .valueAtRisk().amount().doubleValue();
        assertThat(result.valueAtRisk().amount().doubleValue()).isLessThan(soloVar * 0.10);
    }

    @Test
    void reportsThePathCountAndSeedItRan() {
        MonteCarloRiskResult result = calculator(123).simulate(longBoth(1_000, 500),
                factors(0.25, 0.20), correlation(0.5), YEARS, 10_000, market(), VALUATION, 0.95);

        assertThat(result.pathCount()).isEqualTo(10_000);
        assertThat(result.seed()).isEqualTo(123);
    }

    @Test
    void aFlatBookHasNoValueAtRiskOrExpectedShortfall() {
        Portfolio empty = Portfolio.builder(BOOK, Currency.USD).build();

        MonteCarloRiskResult result = calculator(1).simulate(empty, factors(0.25, 0.20),
                correlation(0.5), YEARS, 1_000, market(), VALUATION, 0.95);

        assertThat(result.valueAtRisk()).isEqualTo(Money.zero(Currency.USD));
        assertThat(result.expectedShortfall()).isEqualTo(Money.zero(Currency.USD));
    }

    @Test
    void rejectsEmptyFactors() {
        assertThatThrownBy(() -> calculator(1).simulate(longBoth(1_000, 500), List.of(),
                CorrelationMatrix.of(new double[][] {{1.0}}), YEARS, 1_000, market(), VALUATION, 0.95))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsAFactorCountThatDoesNotMatchTheCorrelationMatrix() {
        assertThatThrownBy(() -> calculator(1).simulate(longBoth(1_000, 500),
                factors(0.25, 0.20), CorrelationMatrix.of(new double[][] {{1.0}}), YEARS, 1_000,
                market(), VALUATION, 0.95))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("factors has 2");
    }

    @Test
    void rejectsANonPositivePathCount() {
        assertThatThrownBy(() -> calculator(1).simulate(longBoth(1_000, 500), factors(0.25, 0.20),
                correlation(0.5), YEARS, 0, market(), VALUATION, 0.95))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAConfidenceLevelOutsideZeroAndOne() {
        assertThatThrownBy(() -> calculator(1).simulate(longBoth(1_000, 500), factors(0.25, 0.20),
                correlation(0.5), YEARS, 1_000, market(), VALUATION, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownUnderlyingFailsRatherThanReportingNoRisk() {
        assertThatThrownBy(() -> calculator(1).simulate(longBoth(1_000, 500),
                List.of(new RiskFactor(InstrumentId.of("GOOG"), 0.0, 0.25),
                        new RiskFactor(MSFT, 0.0, 0.20)),
                correlation(0.5), YEARS, 1_000, market(), VALUATION, 0.95))
                .isInstanceOf(MarketDataSnapshot.MissingMarketDataException.class);
    }
}
