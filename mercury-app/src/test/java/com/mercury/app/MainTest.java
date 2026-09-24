package com.mercury.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The jar's commands, run the way a reader runs them. The demos had no test at all, so a
 * change that broke one surfaced only when somebody typed its command.
 */
class MainTest {

    @Test
    void theDefaultCommandPrintsTheGoldenReport() {
        assertThat(run()).isEqualTo(run("report")).startsWith("MERCURY - portfolio valuation");
    }

    @Test
    void theWalkthroughCarriesTradesAllTheWayToRisk() {
        String output = run("walkthrough");

        // The breach is refused, so only the first bond trade is booked and valued.
        assertThat(output).contains("1,000 more units: rejected");
        assertThat(output).contains("CORP-5Y             200 at a cost of 202665.56 USD");
        // Checkable by hand: +30.00 on AAPL, +5.00 on MSFT, -202.46 on the bond (mostly the
        // 10bp spread paid to Acme). Owes nothing to PnlStatement's own arithmetic.
        assertThat(output).contains("profit and loss     -167.46 USD");
        assertThat(output).contains("AAPL delta          300.00");
    }

    @Test
    void theLifecycleDemoSettlesAutomaticallyRatherThanByHand() {
        String output = run("lifecycle");

        // M19: TradeSettlementBook sweeps every trade due, not just the one section 4
        // showcases - all three trades minted by that point in the script (two from the AAPL
        // cross, one OTC swap) share a settlement date, since nothing advances the clock.
        assertThat(output).contains("3 trade(s) due settled automatically, not walked to SETTLED by hand");
        assertThat(output).contains("CONFIRMED -> SETTLED");
        // Section 5's bond trade settling and releasing its exposure is what lets the same
        // negotiation that was rejected moments earlier succeed on retry.
        assertThat(output).contains("settled automatically and released: exposure to CPTY-TINYLIMIT now 0.00 USD");
        assertThat(output).contains("same 4 units, retried: executed");
    }

    @Test
    void helpListsEveryCommand() {
        assertThat(run("help"))
                .contains("report", "walkthrough", "lifecycle", "risk", "montecarlo");
    }

    @Test
    void everyHelpSpellingSucceedsAndPrintsTheSameList() {
        for (String spelling : new String[] {"help", "--help", "-h", "HELP"}) {
            assertThat(statusOf(spelling)).as(spelling).isZero();
            assertThat(run(spelling)).as(spelling).isEqualTo(run("help"));
        }
    }

    @Test
    void commandsAreCaseInsensitive() {
        assertThat(run("REPORT")).isEqualTo(run("report"));
    }

    @Test
    void anUnknownCommandIsAUsageErrorThatNamesIt() {
        assertThat(statusOf("bogus")).isEqualTo(Main.USAGE_ERROR);
        assertThat(errorStreamOf("bogus")).contains("unknown command: bogus", "walkthrough");
    }

    @Test
    void anExtraArgumentIsRefusedRatherThanSilentlyIgnored() {
        assertThat(statusOf("report", "--csv")).isEqualTo(Main.USAGE_ERROR);
        assertThat(errorStreamOf("report", "--csv")).contains("unexpected argument: --csv");
    }

    @Test
    void everyRunNamesTheCommandsItDidNotUse() {
        // The default command used to print the report and stop, so four of the five things
        // this jar demonstrates were reachable only by reading the README's table first.
        assertThat(errorStreamOf()).contains("walkthrough", "lifecycle", "risk", "montecarlo");
        assertThat(errorStreamOf("risk")).contains("walkthrough");
    }

    @Test
    void theHintGoesToStderrSoARedirectCapturesOnlyTheReport() {
        // GoldenMasterTest documents re-recording the expected report with
        //   java -jar mercury.jar > golden/valuation-report.txt
        // so anything helpful printed on stdout would land in the golden file, and from there
        // into the README block that same test compares against. This is the guard on that.
        assertThat(run()).doesNotContain("commands in this jar");
        assertThat(run()).isEqualTo(run("report"));
    }

    private static int statusOf(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            return Main.run(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static String run(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            Main.run(args);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static String errorStreamOf(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            Main.run(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
