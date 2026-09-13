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
    void helpListsEveryCommand() {
        assertThat(run("help"))
                .contains("report", "walkthrough", "lifecycle", "risk", "montecarlo");
    }

    private static String run(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            Main.main(args);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
