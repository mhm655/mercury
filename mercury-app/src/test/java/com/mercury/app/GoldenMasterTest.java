package com.mercury.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.portfolio.PnlStatement;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioLedger;
import com.mercury.portfolio.PortfolioValuation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the whole engine and compares the output against a committed expected report.
 *
 * <h2>What this catches that unit tests cannot</h2>
 * Every layer is exercised at once - market data, the pricing registry, both models,
 * portfolio valuation, the numeric boundary, sensitivities and every named scenario - and a
 * change anywhere in that chain moves a number here. That is precisely the class of defect
 * the pre-M4 audit found: {@code Bond} and {@code Schedule} were each individually correct
 * and well tested, and disagreed with each other, because nothing looked at the seam between
 * them.
 *
 * <p>It has already earned its place. Building this scenario is what surfaced that
 * {@code contractMultiplier} was declared on every option and applied by nothing, so option
 * legs were valued at a hundredth of their real size. Every unit test passed throughout: each
 * component was doing what its own test asked, and the mistake only became visible when the
 * numbers were printed side by side and read as a portfolio.
 *
 * <h2>Why it is deterministic</h2>
 * The scenario fixes every input, including the valuation date - nothing consults a clock,
 * and an ArchUnit rule prevents anything in the engine from doing so. Formatting is pinned to
 * {@code Locale.ROOT} with literal newlines, so the bytes do not depend on the machine.
 * Reproducibility is not incidental here: it is a property the architecture was built for
 * (injected clock, immutable snapshots, pure pricers), and this test is what proves it holds.
 *
 * <h2>When this fails</h2>
 * A failure means the engine's output changed. That is either a regression, or an intended
 * improvement - and the distinction is a judgement, never a formality. Re-record the expected
 * file <em>only</em> after confirming every changed number is right, because a golden master
 * updated reflexively tests nothing at all.
 *
 * <pre>
 *   java -cp mercury-app/target/classes;mercury-engine/target/classes com.mercury.app.Main \
 *       &gt; mercury-app/src/test/resources/golden/valuation-report.txt
 * </pre>
 */
class GoldenMasterTest {

    private static final String GOLDEN_RESOURCE = "/golden/valuation-report.txt";

    @Test
    @DisplayName("the demo scenario reproduces its committed report exactly")
    void reportMatchesGoldenMaster() {
        assertThat(runScenario()).isEqualTo(expectedReport());
    }

    @Test
    @DisplayName("running twice produces identical output")
    void isDeterministicAcrossRuns() {
        // Reproducibility asserted directly rather than inferred. Two runs in one JVM would
        // still differ if anything read a clock, hashed by identity, or iterated a
        // non-deterministic map - the last of which was a real bug found at M4, where
        // Map.copyOf left valuation line order unspecified.
        assertThat(runScenario()).isEqualTo(runScenario());
    }

    @Test
    @DisplayName("the report contains no platform-specific line endings")
    void usesPlatformIndependentNewlines() {
        // Otherwise this test passes on Windows and fails in CI, for a reason that has nothing
        // to do with the engine.
        assertThat(runScenario()).doesNotContain("\r");
    }

    @Test
    @DisplayName("every pricing model in the engine is exercised")
    void coversEveryModel() {
        // A golden master is only as good as the ground it covers. The demo deliberately holds
        // one instrument of each priced kind, so a change to any model moves a number here.
        assertThat(runScenario())
                .contains("spot")
                .contains("black-scholes")
                .contains("discounted-cashflow")
                .contains("swap-discounting");
    }

    @Test
    @DisplayName("the headline total reconciles against the printed lines")
    void totalReconcilesAgainstDetail() {
        // A report whose total does not equal its own rows is worse than no report. The
        // invariant is enforced in PortfolioValuation's constructor; this checks the rendered
        // artifact rather than the object.
        PortfolioValuation valuation = valueDemoPortfolio();

        assertThat(runScenario())
                .contains(valuation.totalValue().amount().toPlainString());
        assertThat(valuation.lines()).hasSize(8);
    }

    @Test
    @DisplayName("every kind of risk the book carries is reported, not only equity delta")
    void reportsEveryRiskFactor() {
        // The M5 audit found the demo holding a bond and an FX forward while the report showed
        // spot delta alone - roughly 250,000 of rate exposure and 519,000 of euro exposure with
        // no line of output naming either. The engine could measure both the whole time, which
        // is what made it easy to miss. This test fails if a risk factor stops being reported.
        String report = runScenario();

        assertThat(report)
                .contains("DELTA")
                .contains("FX DELTA")
                .contains("DV01")
                .contains("EUR/USD")
                .contains("USD")
                .contains("EUR");
    }

    @Test
    @DisplayName("total profit is exactly what the book has made since it opened")
    void profitReconcilesAgainstOpeningCash() {
        // The strongest check in the suite, because it owes nothing to the code it checks.
        // This book began as one million of cash and nothing else. Whatever it has done since,
        // its profit must be what it is worth now - positions plus cash - minus what it
        // started with. Cost basis, FIFO matching, realised-versus-unrealised and the FX
        // conversion all have to be right at once for that to come out.
        PortfolioLedger ledger = DemoScenario.ledger();
        MarketDataSnapshot market = DemoScenario.market();
        PortfolioValuation valuation = valueDemoPortfolio();

        Money cash = Money.zero(Currency.USD);
        for (Currency currency : ledger.cash().currencies()) {
            Money balance = ledger.cash().balance(currency);
            cash = cash.plus(currency == Currency.USD ? balance
                    : Money.fromModelValue(balance.amount().doubleValue()
                            * market.fxRate(currency, Currency.USD), Currency.USD));
        }
        Money netAssetValue = valuation.totalValue().plus(cash);
        Money madeSinceOpening = netAssetValue.minus(DemoScenario.OPENING_CASH);

        PnlStatement pnl = PnlStatement.of(ledger, valuation, market);

        assertThat(pnl.total()).isEqualTo(madeSinceOpening);
        assertThat(pnl.realised()).isEqualTo(Money.of("2400.00", Currency.USD));
    }

    @Test
    @DisplayName("realised and unrealised profit are reported as separate lines")
    void pnlIsSplit() {
        // One figure would let a book that has been quietly losing money look profitable.
        assertThat(runScenario())
                .contains("PROFIT AND LOSS")
                .contains("FIFO cost basis")
                .contains("Realised")
                .contains("Unrealised")
                .contains("NET ASSET VALUE");
    }

    @Test
    @DisplayName("the currency breakdown adds up to the headline total")
    void exposureReconciles() {
        // A decomposition that does not sum to the thing it decomposes is worse than no
        // decomposition. The first version summed the unrounded model values and the two rows
        // came to a cent more than the total.
        PortfolioValuation valuation = valueDemoPortfolio();

        Money summed = valuation.currencies().stream()
                .map(valuation::exposureTo)
                .reduce(Money.zero(valuation.totalValue().currency()), Money::plus);

        assertThat(summed).isEqualTo(valuation.totalValue());
        assertThat(runScenario())
                .contains("EXPOSURE BY CURRENCY")
                .contains("BUND-3Y");
    }

    @Test
    @DisplayName("a foreign position is quoted in its own currency and valued in the book's")
    void foreignPositionShowsBothCurrencies() {
        // The euro bond's unit value is in euros and its market value in dollars. Printing
        // both under one heading without naming the currency would be a trap, so the row
        // carries a CCY column.
        PortfolioValuation.PositionValuation bund = valueDemoPortfolio().lines().stream()
                .filter(line -> line.instrument().id().equals(DemoScenario.EUR_BOND))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no euro bond in the demo book"));

        assertThat(bund.isForeign()).isTrue();
        assertThat(bund.localValue().currency().code()).isEqualTo("EUR");
        assertThat(bund.marketValue().currency().code()).isEqualTo("USD");
        assertThat(runScenario()).contains("BUND-3Y                 200   EUR");
    }

    @Test
    @DisplayName("the Market Crash scenario shocks every risk factor the book carries")
    void stressCoversRates() {
        // The rates leg of this scenario was specified in the design document and missing from
        // the implementation until M6, which nobody noticed while the book held no material
        // rate risk. A stress test that silently omits a factor is worse than one that is
        // absent, because it produces a number people act on. M11 split the report's one
        // hardcoded scenario into three named ones; this still checks specifically Market
        // Crash, the one this regression was originally about.
        assertThat(runScenario())
                .contains("equities -30%")
                .contains("volatility +50%")
                .contains("FX -10%")
                .contains("rates +150bp");
    }

    @Test
    @DisplayName("the report shows curves that were fitted, not typed in")
    void showsBootstrappedCurves() {
        // Nothing in DemoScenario states a five-year zero rate. It states deposit and swap
        // quotes and lets the bootstrapper find the curve that reprices them. This asserts the
        // shape that produces - a USD front end above its long end, which is what mid-2024
        // looked like - so a curve accidentally rebuilt flat would fail here rather than
        // merely look dull.
        String report = runScenario();
        assertThat(report).contains("DISCOUNT CURVES");

        // Anchored to the section, not to the first line beginning "USD". Once the book held
        // two currencies the exposure block gained a USD row of its own, several lines earlier
        // and with a different number of columns - so a naive findFirst matched the wrong row
        // and read past the end of it.
        List<String> lines = report.lines().toList();
        int header = lines.indexOf(lines.stream()
                .filter(line -> line.startsWith("DISCOUNT CURVES"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no curve section")));
        String usd = lines.subList(header, lines.size()).stream()
                .filter(line -> line.trim().startsWith("USD"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no USD curve row"));
        String[] cells = usd.trim().split("\\s+");

        double oneYear = Double.parseDouble(cells[1].replace("%", ""));
        double fiveYear = Double.parseDouble(cells[3].replace("%", ""));

        assertThat(oneYear).isGreaterThan(fiveYear);
    }

    @Test
    @DisplayName("the accrued-interest row adds up")
    void accruedRowReconciles() {
        // clean + accrued = dirty, on the printed figures rather than on the objects behind
        // them - a rounding choice made one way in the model and another in the renderer would
        // leave a row that does not add up, which is exactly what a reader checks first.
        String[] cells = runScenario().lines()
                .filter(line -> line.contains("CORP-5Y"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no accrual row for CORP-5Y"))
                .trim()
                .split("\\s+");

        double clean = Double.parseDouble(cells[1]);
        double accrued = Double.parseDouble(cells[2]);
        double dirty = Double.parseDouble(cells[3]);

        assertThat(clean + accrued).isEqualTo(dirty, offset(1e-9));
        assertThat(accrued).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("a bond's unit value is its dirty price, and the report says so")
    void namesTheDirtyPrice() {
        // Printing a bond's full present value under the same "UNIT VALUE" heading as a share
        // price invites the reader to compare two numbers that mean different things.
        assertThat(runScenario()).contains("dirty");
    }

    private static PortfolioValuation valueDemoPortfolio() {
        return DemoScenario.valuationService().value(
                DemoScenario.portfolio(), DemoScenario.market(), DemoScenario.VALUATION_DATE);
    }

    private static String runScenario() {
        Portfolio portfolio = DemoScenario.portfolio();
        return ValuationReport.render(
                DemoScenario.ledger(),
                valueDemoPortfolio(),
                DemoScenario.market(),
                DemoScenario.sensitivityCalculator(),
                DemoScenario.riskFactors(),
                DemoScenario.scenarios(),
                DemoScenario.VALUATION_DATE);
    }

    private static String expectedReport() {
        try (InputStream in = GoldenMasterTest.class.getResourceAsStream(GOLDEN_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing golden file " + GOLDEN_RESOURCE);
            }
            // Read as bytes and decode explicitly: letting the platform choose an encoding
            // would be the same class of mistake as letting it choose a line separator.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + GOLDEN_RESOURCE, e);
        }
    }
}
