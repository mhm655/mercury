package com.mercury.app;

import com.mercury.app.tui.TuiDemo;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.portfolio.PortfolioLedger;
import com.mercury.portfolio.PortfolioValuation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The one entry point: prints the valuation report, or runs a named demo.
 *
 * <p>Only this and the demos it dispatches to know a console exists. Everything in the engine
 * returns values; presentation stops in this module, which is why the engine can be driven
 * equally well by a test, a benchmark, or the Spring module that arrives later.
 *
 * <pre>
 *   mvn -q -DskipTests package
 *   java -jar mercury-app/target/mercury.jar              the valuation report
 *   java -jar mercury-app/target/mercury.jar walkthrough  orders to trades to book to risk
 *   java -jar mercury-app/target/mercury.jar help         every command
 * </pre>
 */
public final class Main {

    /** Command name to what it runs. Ordered, so {@code help} lists them as written. */
    private static final Map<String, Consumer<String[]>> DEMOS = new LinkedHashMap<>();

    static {
        DEMOS.put("walkthrough", EndToEndDemo::main);
        DEMOS.put("lifecycle", TradeLifecycleDemo::main);
        DEMOS.put("risk", RiskEngineDemo::main);
        DEMOS.put("montecarlo", MonteCarloDemo::main);
        DEMOS.put("tui", TuiDemo::main);
    }

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("report")) {
            printReport();
            printOtherCommands();
            return;
        }
        Consumer<String[]> demo = DEMOS.get(args[0]);
        if (demo != null) {
            demo.accept(new String[0]);
            printOtherCommands();
            return;
        }
        boolean askedForHelp = args[0].equals("help") || args[0].equals("--help");
        (askedForHelp ? System.out : System.err).print(usage());
        if (!askedForHelp) {
            System.exit(2);
        }
    }

    private static void printReport() {
        // Ledger and market built once and reused below. Since M14, DemoScenario.ledger() runs
        // the demo's eight trades through real matching and OTC pricing rather than a cheap
        // builder chain, and .market() bootstraps two curves - so calling either of them twice
        // per report, as an earlier version of this method did (once via .portfolio(), which
        // calls .ledger() itself, and again directly for the render call below), silently
        // doubled the cost of the one command most readers of this project actually run.
        PortfolioLedger ledger = DemoScenario.ledger();
        MarketDataSnapshot market = DemoScenario.market();
        PortfolioValuation valuation = DemoScenario.valuationService()
                .value(ledger.toPortfolio(), market, DemoScenario.VALUATION_DATE);

        String report = ValuationReport.render(
                ledger,
                valuation,
                market,
                DemoScenario.sensitivityCalculator(),
                DemoScenario.riskFactors(),
                DemoScenario.scenarios(),
                DemoScenario.VALUATION_DATE);

        // print, not println: the report already ends with a newline, and println would append
        // a platform separator - CRLF on Windows - reintroducing exactly the OS dependence
        // ValuationReport is careful to avoid.
        System.out.print(report);
    }

    /**
     * Names the commands this run did not use.
     *
     * <h2>Why this exists</h2>
     * The default command printed sixty lines of report and then stopped, never mentioning
     * that {@code walkthrough}, {@code lifecycle}, {@code risk} and {@code montecarlo} exist.
     * Four of the five things this jar can demonstrate were reachable only by reading the
     * README's command table first - so a reader who typed the obvious thing saw the least
     * interesting fifth of the project and had no reason to suspect there was more.
     *
     * <h2>Why stderr</h2>
     * Because stdout is data. {@code GoldenMasterTest} documents re-recording the expected
     * report as
     *
     * <pre>
     *   java -jar mercury-app/target/mercury.jar &gt; src/test/resources/golden/valuation-report.txt
     * </pre>
     *
     * and a hint on stdout would land in that file, and from there into the README block the
     * same test compares against. A terminal shows both streams, so the reader sees this and
     * a redirect does not - which is the whole distinction the two streams are for.
     */
    private static void printOtherCommands() {
        System.err.println();
        System.err.println("Six commands in this jar; this was one. The rest:");
        System.err.println("  walkthrough  orders cross, become trades, become a book, and get");
        System.err.println("               risked - the whole engine in one pass, and the one to");
        System.err.println("               run next if you run only one more");
        System.err.println("  lifecycle    trade state machine, self-trade prevention, a credit breach");
        System.err.println("  risk         Gamma and Vega against their closed forms; historical VaR");
        System.err.println("  montecarlo   Monte Carlo converging on Black-Scholes; VaR and shortfall");
        System.err.println("  tui          the walkthrough replayed one step at a time (Enter to advance)");
        System.err.println("  report       the valuation report (the default)");
        System.err.println("Run one with: java -jar mercury.jar <command>");
    }

    private static String usage() {
        return """
                usage: java -jar mercury.jar [command]

                  report       the valuation report (the default)
                  walkthrough  orders -> trades -> ledger -> valuation -> risk, in one run
                  lifecycle    trade lifecycle, self-trade prevention, credit limits
                  risk         Gamma/Vega against closed form, historical VaR
                  montecarlo   Monte Carlo pricing convergence, VaR and Expected Shortfall
                  tui          the walkthrough replayed one step at a time (Enter to advance)
                """;
    }
}
