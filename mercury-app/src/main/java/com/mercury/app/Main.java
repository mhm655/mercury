package com.mercury.app;

import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioValuation;
import java.util.List;

/**
 * Runs the demo scenario and prints the report.
 *
 * <p>The only class in the project that knows a console exists. Everything above it returns
 * values; presentation stops here, which is why the engine can be driven equally well by a
 * test, a benchmark, or the Spring module that arrives later.
 *
 * <pre>
 *   mvn -q -pl mercury-app -am package
 *   java -cp "mercury-app/target/classes;mercury-engine/target/classes" com.mercury.app.Main
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Portfolio portfolio = DemoScenario.portfolio();
        PortfolioValuation valuation = DemoScenario.valuationService()
                .value(portfolio, DemoScenario.market(), DemoScenario.VALUATION_DATE);

        String report = ValuationReport.render(
                portfolio,
                valuation,
                DemoScenario.market(),
                DemoScenario.sensitivityCalculator(),
                List.of(DemoScenario.AAPL, DemoScenario.MSFT),
                DemoScenario.VALUATION_DATE);

        // print, not println: the report already ends with a newline, and println would append
        // a platform separator - CRLF on Windows - reintroducing exactly the OS dependence
        // ValuationReport is careful to avoid.
        System.out.print(report);
    }
}
