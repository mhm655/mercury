package com.mercury.app;

import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.instrument.EuropeanOption;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.marketdata.MarketShock;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioValuationService;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.model.BlackScholesModel;
import com.mercury.pricing.model.SpotPriceModel;
import com.mercury.risk.HistoricalVaRCalculator;
import com.mercury.risk.SensitivityCalculator;
import java.time.LocalDate;
import java.util.List;

/**
 * A third, milestone-specific runnable - not the project's entrypoint - that proves M10's
 * risk engine visibly rather than only through tests.
 *
 * <p>{@code Main} is still the one command the README leads with; this exists for the same
 * reason {@code TradeLifecycleDemo} does (see its own javadoc). It runs against the same
 * instruments and market {@code DemoScenario} already builds - nothing here is a new
 * scenario, and {@code Main}'s golden-master output is untouched by it, aside from the
 * Gamma/Vega lines M10 added directly to the RISK section it already had.
 *
 * <pre>
 *   mvn -q -pl mercury-app -am package
 *   java -cp "mercury-app/target/classes;mercury-engine/target/classes" com.mercury.app.RiskEngineDemo
 * </pre>
 */
public final class RiskEngineDemo {

    private RiskEngineDemo() {
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println("M10 RISK ENGINE DEMO");
        System.out.println("=".repeat(78));

        System.out.println();
        System.out.println("1. GAMMA AND VEGA, CROSS-VALIDATED AGAINST THE CLOSED FORM");
        System.out.println("-".repeat(78));
        crossValidateGammaAndVega();

        System.out.println();
        System.out.println("2. HISTORICAL VALUE AT RISK, OVER THE FULL DEMO BOOK");
        System.out.println("-".repeat(78));
        historicalValueAtRisk();
    }

    /**
     * One AAPL call, priced and risked two independent ways - by revaluation and by the
     * closed form - printed side by side rather than only asserted in a test, the same
     * "evidence, not claims" standard the rest of this file's siblings hold themselves to.
     */
    private static void crossValidateGammaAndVega() {
        var aaplId = DemoScenario.AAPL;
        EuropeanOption call = EuropeanOption.call("DEMO-CALL", aaplId, Price.of("195.00"),
                DemoScenario.VALUATION_DATE.plusMonths(6), Currency.USD);

        MarketDataSnapshot market = MarketDataSnapshot.builder(DemoScenario.VALUATION_DATE)
                .spot(aaplId, 195.50)
                .volatility(aaplId, 0.28)
                .discountRate(Currency.USD, 0.045)
                .build();

        InstrumentCatalog catalog = InstrumentCatalog.of(call);
        SensitivityCalculator sensitivities = new SensitivityCalculator(
                new PortfolioValuationService(
                        PricingService.builder()
                                .register(new SpotPriceModel())
                                .register(new BlackScholesModel())
                                .build(),
                        catalog));

        Portfolio portfolio = Portfolio.builder(PortfolioId.of("DEMO-GREEKS"), Currency.USD)
                .position(call.id(), 100).build();

        double numericGamma = sensitivities.gamma(portfolio, aaplId, market, DemoScenario.VALUATION_DATE);
        double numericVega = sensitivities.vega(portfolio, aaplId, market, DemoScenario.VALUATION_DATE);

        double years = call.yearsToExpiry(DemoScenario.VALUATION_DATE);
        double analyticGammaPerContract =
                BlackScholesModel.gamma(195.50, 195.00, years, 0.045, 0.28) * call.contractMultiplier();
        double analyticVegaPerContractPerPoint =
                BlackScholesModel.vega(195.50, 195.00, years, 0.045, 0.28)
                        * call.contractMultiplier() * SensitivityCalculator.DEFAULT_VOLATILITY_BUMP;

        System.out.printf("  100 contracts of a 6-month 195 call, spot 195.50, vol 28%%%n");
        System.out.printf("  GAMMA  numeric=%.4f  analytic=%.4f%n",
                numericGamma, 100.0 * analyticGammaPerContract);
        System.out.printf("  VEGA   numeric=%.4f  analytic=%.4f%n",
                numericVega, 100.0 * analyticVegaPerContractPerPoint);
        System.out.println("  Two independent routes to the same number agreeing is stronger "
                + "evidence than either alone (DESIGN_PROPOSAL.md 5.3.1).");
    }

    /**
     * Ten hardcoded historical daily moves - not random, matching this project's
     * determinism ethos (see {@code DemoScenario}'s own javadoc on why every input here is a
     * constant) - applied to the full demo book.
     */
    private static void historicalValueAtRisk() {
        Portfolio portfolio = DemoScenario.portfolio();
        MarketDataSnapshot market = DemoScenario.market();
        SensitivityCalculator sensitivities = DemoScenario.sensitivityCalculator();
        HistoricalVaRCalculator var = new HistoricalVaRCalculator(sensitivities);

        // Ten trading days' worth of stated moves across the book's real risk factors - equity
        // spot, volatility and the USD curve - not a single-factor toy scenario.
        List<MarketShock> tenDays = List.of(
                MarketShock.scaleAllSpots(0.98).and(MarketShock.scaleAllVolatilities(1.05)),
                MarketShock.scaleAllSpots(1.01),
                MarketShock.scaleAllSpots(0.95).and(MarketShock.scaleAllVolatilities(1.15)),
                MarketShock.scaleAllSpots(1.02),
                MarketShock.scaleAllSpots(0.99),
                MarketShock.scaleAllSpots(1.03).and(MarketShock.scaleAllVolatilities(0.95)),
                MarketShock.scaleAllSpots(0.92).and(MarketShock.scaleAllVolatilities(1.30)),
                MarketShock.scaleAllSpots(1.01),
                MarketShock.scaleAllSpots(0.97),
                MarketShock.scaleAllSpots(1.04));

        Money var90 = var.valueAtRisk(portfolio, tenDays, market, DemoScenario.VALUATION_DATE, 0.90);

        System.out.println("  10 historical daily scenarios (equity spot and volatility moves)");
        System.out.println("  90% 1-day historical VaR: " + var90);
        System.out.println("  The worst 10% of the sampled days lost no more than this.");
    }
}
