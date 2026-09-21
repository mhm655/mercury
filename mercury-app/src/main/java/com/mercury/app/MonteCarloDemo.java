package com.mercury.app;

import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.instrument.EuropeanOption;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.model.BlackScholesModel;
import com.mercury.risk.SensitivityCalculator;
import com.mercury.simulation.CorrelatedMonteCarloVaRCalculator;
import com.mercury.simulation.CorrelatedMonteCarloVaRCalculator.RiskFactor;
import com.mercury.simulation.CorrelationMatrix;
import com.mercury.simulation.MonteCarloOptionModel;
import com.mercury.simulation.MonteCarloRiskResult;
import com.mercury.simulation.MonteCarloVaRCalculator;
import java.util.List;

/**
 * A fourth, milestone-specific runnable - not the project's entrypoint - that proves M12's
 * single-threaded Monte Carlo visibly rather than only through tests.
 *
 * <p>{@code Main} is still the one command the README leads with; this exists for the same
 * reason {@code TradeLifecycleDemo} and {@code RiskEngineDemo} do (see their own javadoc).
 * It runs against the same instruments and market {@code DemoScenario} already builds -
 * nothing here is a new scenario, and {@code Main}'s golden-master output is completely
 * untouched by it, following the precedent {@code RiskEngineDemo} already set for VaR-style
 * calculators (a demo, not a RISK-section line).
 *
 * <p>Section 3 is M20's: {@link CorrelatedMonteCarloVaRCalculator}, the multi-factor sibling
 * to section 2's single-factor {@link MonteCarloVaRCalculator} - AAPL and MSFT simulated
 * jointly rather than one at a time, showing the naive (uncorrelated) sum of each leg's own
 * VaR against the correlated joint figure.
 *
 * <pre>
 *   mvn -q -DskipTests package
 *   java -jar mercury-app/target/mercury.jar montecarlo
 * </pre>
 */
public final class MonteCarloDemo {

    private MonteCarloDemo() {
    }

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println("M12 MONTE CARLO DEMO");
        System.out.println("=".repeat(78));

        System.out.println();
        System.out.println("1. MONTE CARLO PRICE CONVERGING TO THE BLACK-SCHOLES CLOSED FORM");
        System.out.println("-".repeat(78));
        convergeToClosedForm();

        System.out.println();
        System.out.println("2. MONTE CARLO VALUE AT RISK AND EXPECTED SHORTFALL - AAPL EXPOSURE ONLY");
        System.out.println("-".repeat(78));
        monteCarloValueAtRisk();

        System.out.println();
        System.out.println("3. MULTI-FACTOR: CORRELATED AAPL/MSFT VALUE AT RISK");
        System.out.println("-".repeat(78));
        correlatedValueAtRisk();
    }

    /**
     * The same AAPL call {@code RiskEngineDemo} cross-validates Gamma and Vega against,
     * priced by {@code MonteCarloOptionModel} at rising path counts alongside the fixed
     * Black-Scholes answer - the error visibly shrinking is the "convergence" the roadmap
     * names, not a single agreeing point.
     */
    private static void convergeToClosedForm() {
        var aaplId = DemoScenario.AAPL;
        EuropeanOption call = EuropeanOption.call("DEMO-CALL", aaplId, Price.of("195.00"),
                DemoScenario.VALUATION_DATE.plusMonths(6), Currency.USD);

        MarketDataSnapshot market = MarketDataSnapshot.builder(DemoScenario.VALUATION_DATE)
                .spot(aaplId, 195.50)
                .volatility(aaplId, 0.28)
                .discountRate(Currency.USD, 0.045)
                .build();

        double analytic = new BlackScholesModel().price(call, market, DemoScenario.VALUATION_DATE).value();
        System.out.printf("  6-month 195 call, spot 195.50, vol 28%% - Black-Scholes: %.4f%n", analytic);

        for (int pathCount : new int[] {100, 10_000, 1_000_000}) {
            double simulated = new MonteCarloOptionModel(42, pathCount)
                    .price(call, market, DemoScenario.VALUATION_DATE).value();
            double error = Math.abs(simulated - analytic);
            System.out.printf("  %,9d paths: %10.4f  (error %.4f)%n", pathCount, simulated, error);
        }
        System.out.println("  GBM has a closed-form terminal distribution, so every path drawn "
                + "here is exact - only Monte Carlo sampling error narrows with more paths, "
                + "never discretization error.");
    }

    /**
     * The AAPL exposure <em>alone</em> in the full demo book, risked by Monte Carlo.
     *
     * <p>Deliberately not the same comparison as {@code RiskEngineDemo}'s historical VaR: that
     * one shocks spot, volatility <em>and</em> rates together across the whole book, because
     * {@code MarketShock} composes; {@link MonteCarloVaRCalculator} simulates one risk factor
     * at a time (see its own javadoc and {@code docs/KNOWN_GAPS.md}), so this number is
     * strictly narrower - AAPL's own contribution, not the book's aggregate risk. Printing it
     * without saying so would be exactly the silently-incomplete-risk-number mistake this
     * project's own history (C-2, D-1 in {@code docs/KNOWN_GAPS.md}) treats as worth avoiding,
     * not repeating it here for a new calculator.
     */
    private static void monteCarloValueAtRisk() {
        var aaplId = DemoScenario.AAPL;
        SensitivityCalculator sensitivities = DemoScenario.sensitivityCalculator();
        MonteCarloVaRCalculator monteCarloVaR = new MonteCarloVaRCalculator(sensitivities, 7);

        // 1-day horizon, zero drift (the standard short-horizon VaR assumption - drift is
        // negligible next to a single day's volatility), the AAPL volatility this same demo
        // book already quotes.
        double oneDay = 1.0 / 365.0;
        double volatility = DemoScenario.market().volatility(aaplId);

        MonteCarloRiskResult result = monteCarloVaR.simulate(
                DemoScenario.portfolio(), aaplId, 0.0, volatility, oneDay, 200_000,
                DemoScenario.market(), DemoScenario.VALUATION_DATE, 0.99);

        System.out.println("  200,000 simulated 1-day paths on AAPL spot, vol "
                + String.format("%.0f%%", volatility * 100)
                + " - AAPL's contribution only, not the whole book's risk (single risk factor "
                + "at a time; see docs/KNOWN_GAPS.md)");
        System.out.println("  99% Monte Carlo VaR:               " + result.valueAtRisk());
        System.out.println("  95% confidence interval on that VaR: "
                + result.valueAtRiskConfidenceInterval());
        System.out.println("  99% Monte Carlo Expected Shortfall: " + result.expectedShortfall());
        System.out.println("  seed=" + result.seed() + " - a specific figure is only reproducible "
                + "later if the seed that produced it travels with it, not just with whoever ran it.");
        System.out.println("  Expected Shortfall is never smaller than VaR: it averages every "
                + "scenario at least as bad as the VaR threshold, not just the threshold itself. The "
                + "confidence interval is a different '95%' from the VaR's own '99%' - see "
                + "HistoricalVaRCalculator's javadoc for why the two must not be conflated.");
    }

    /**
     * AAPL and MSFT simulated jointly, correlated, against the naive (uncorrelated) sum of
     * each leg's own standalone VaR - the diversification effect a single-factor calculator
     * cannot show, because it never sees more than one risk factor at once.
     *
     * <p>Both the MSFT volatility and the AAPL/MSFT correlation are assumed here, for this
     * demo alone, not read from anywhere in the engine: {@code DemoScenario.market()} quotes
     * no MSFT volatility, since nothing in this book prices an MSFT option; and this engine has
     * no correlation estimator at all (no historical-data loader - see
     * {@code docs/KNOWN_GAPS.md}). Which correlation a book is risked against is the same kind
     * of reporting decision {@code DemoScenario.scenarios()} already makes explicit for stress
     * scenarios, not an engine constant.
     */
    private static void correlatedValueAtRisk() {
        var aaplId = DemoScenario.AAPL;
        var msftId = DemoScenario.MSFT;
        SensitivityCalculator sensitivities = DemoScenario.sensitivityCalculator();
        double oneDay = 1.0 / 365.0;
        double aaplVol = DemoScenario.market().volatility(aaplId);
        double msftVol = 0.24;
        double correlation = 0.6;

        MonteCarloVaRCalculator singleFactor = new MonteCarloVaRCalculator(sensitivities, 7);
        Money aaplAlone = singleFactor.simulate(DemoScenario.portfolio(), aaplId, 0.0, aaplVol,
                oneDay, 200_000, DemoScenario.market(), DemoScenario.VALUATION_DATE, 0.99)
                .valueAtRisk();
        Money msftAlone = singleFactor.simulate(DemoScenario.portfolio(), msftId, 0.0, msftVol,
                oneDay, 200_000, DemoScenario.market(), DemoScenario.VALUATION_DATE, 0.99)
                .valueAtRisk();
        Money naiveSum = aaplAlone.plus(msftAlone);

        CorrelatedMonteCarloVaRCalculator correlatedVaR =
                new CorrelatedMonteCarloVaRCalculator(sensitivities, 7);
        List<RiskFactor> factors = List.of(
                new RiskFactor(aaplId, 0.0, aaplVol), new RiskFactor(msftId, 0.0, msftVol));
        CorrelationMatrix correlationMatrix = CorrelationMatrix.of(
                new double[][] {{1.0, correlation}, {correlation, 1.0}});
        MonteCarloRiskResult joint = correlatedVaR.simulate(DemoScenario.portfolio(), factors,
                correlationMatrix, oneDay, 200_000, DemoScenario.market(),
                DemoScenario.VALUATION_DATE, 0.99);

        System.out.println("  AAPL vol " + String.format("%.0f%%", aaplVol * 100) + " (quoted), MSFT vol "
                + String.format("%.0f%%", msftVol * 100) + " (assumed - no MSFT option in this book "
                + "quotes one), correlation " + correlation + " (assumed - no correlation estimator "
                + "in this engine; see docs/KNOWN_GAPS.md)");
        System.out.println("  99% VaR, AAPL alone:            " + aaplAlone);
        System.out.println("  99% VaR, MSFT alone:            " + msftAlone);
        System.out.println("  naive sum (no diversification): " + naiveSum);
        System.out.println("  99% VaR, correlated jointly:    " + joint.valueAtRisk());
        System.out.println("  diversification benefit:        " + naiveSum.minus(joint.valueAtRisk())
                + " (the naive sum overstates risk whenever correlation is below 1 - two "
                + "imperfectly-correlated risks are never as bad together as their worst cases "
                + "added up)");
    }
}
