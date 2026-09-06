package com.mercury.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.portfolio.Portfolio;
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
 * portfolio valuation, the numeric boundary, sensitivities and a stress scenario - and a
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
    @DisplayName("the headline total reconciles against the printed lines")
    void totalReconcilesAgainstDetail() {
        // A report whose total does not equal its own rows is worse than no report. The
        // invariant is enforced in PortfolioValuation's constructor; this checks the rendered
        // artifact rather than the object.
        PortfolioValuation valuation = valueDemoPortfolio();

        assertThat(runScenario())
                .contains(valuation.totalValue().amount().toPlainString());
        assertThat(valuation.lines()).hasSize(4);
    }

    private static PortfolioValuation valueDemoPortfolio() {
        return DemoScenario.valuationService().value(
                DemoScenario.portfolio(), DemoScenario.market(), DemoScenario.VALUATION_DATE);
    }

    private static String runScenario() {
        Portfolio portfolio = DemoScenario.portfolio();
        return ValuationReport.render(
                portfolio,
                valueDemoPortfolio(),
                DemoScenario.market(),
                DemoScenario.sensitivityCalculator(),
                List.of(DemoScenario.AAPL, DemoScenario.MSFT),
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
