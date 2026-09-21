package com.mercury.app.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.app.DemoScenario;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.money.Quantity;
import com.mercury.core.id.PortfolioId;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.portfolio.CashAccount;
import com.mercury.portfolio.CostBasisMethod;
import com.mercury.portfolio.PortfolioLedger;
import com.mercury.portfolio.PortfolioValuationService;
import com.mercury.risk.SensitivityCalculator;
import com.mercury.simulation.MonteCarloVaRCalculator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Uses {@link DemoScenario}'s own instruments, market and services rather than a bespoke
 * fixture - the panel's whole job is assembling their output, so a test double for any of
 * them would only prove this class calls a mock correctly, not that the numbers it shows are
 * real. Path counts are kept small so the Monte Carlo VaR calls stay fast; correctness of the
 * VaR figure itself is {@link com.mercury.simulation.MonteCarloVaRCalculatorTest}'s job, not
 * this class's.
 */
class PnlRiskPanelTest {

    private static final MarketDataSnapshot MARKET = DemoScenario.market();
    private static final SensitivityCalculator SENSITIVITIES = DemoScenario.sensitivityCalculator();
    private static final int TEST_PATH_COUNT = 200;

    @Test
    void emptyPortfolioShowsNoPositionsRatherThanZeroedFigures() {
        PnlRiskPanel panel = panel(2);
        PortfolioLedger empty = openingLedger();

        List<String> lines = panel.render(empty, MARKET, DemoScenario.VALUATION_DATE, 0);

        assertThat(lines).containsExactly("  (no positions yet)");
    }

    @Test
    void nonEmptyPortfolioShowsValuationPnlGreeksAndVar() {
        PnlRiskPanel panel = panel(2);
        PortfolioLedger held = openingLedger().buy(DemoScenario.AAPL, Quantity.of(100),
                Price.of("190.00"), Currency.USD, DemoScenario.VALUATION_DATE);

        List<String> lines = panel.render(held, MARKET, DemoScenario.VALUATION_DATE, 0);

        assertThat(String.join("\n", lines))
                .contains("positions total")
                .contains("realised P&L")
                .contains("unrealised P&L")
                .contains("AAPL delta")
                .contains("USD DV01")
                .contains("99% 1-day VaR");
    }

    @Test
    void varIsLabelledStaleOnFramesBetweenRecomputes() {
        PnlRiskPanel panel = panel(2);
        PortfolioLedger held = openingLedger().buy(DemoScenario.AAPL, Quantity.of(100),
                Price.of("190.00"), Currency.USD, DemoScenario.VALUATION_DATE);

        String stepZero = varLine(panel.render(held, MARKET, DemoScenario.VALUATION_DATE, 0));
        String stepOne = varLine(panel.render(held, MARKET, DemoScenario.VALUATION_DATE, 1));
        String stepTwo = varLine(panel.render(held, MARKET, DemoScenario.VALUATION_DATE, 2));

        assertThat(stepZero).doesNotContain("as of step");
        assertThat(stepOne).contains("as of step 0");
        assertThat(stepTwo).doesNotContain("as of step");
    }

    @Test
    void rejectsANonPositivePathCount() {
        assertThatThrownBy(() -> new PnlRiskPanel(DemoScenario.valuationService(), SENSITIVITIES,
                new MonteCarloVaRCalculator(SENSITIVITIES, 7), DemoScenario.AAPL,
                MARKET.volatility(DemoScenario.AAPL), 0, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANonPositiveCadence() {
        assertThatThrownBy(() -> new PnlRiskPanel(DemoScenario.valuationService(), SENSITIVITIES,
                new MonteCarloVaRCalculator(SENSITIVITIES, 7), DemoScenario.AAPL,
                MARKET.volatility(DemoScenario.AAPL), TEST_PATH_COUNT, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PnlRiskPanel panel(int cadence) {
        PortfolioValuationService valuationService = DemoScenario.valuationService();
        return new PnlRiskPanel(valuationService, SENSITIVITIES,
                new MonteCarloVaRCalculator(SENSITIVITIES, 7), DemoScenario.AAPL,
                MARKET.volatility(DemoScenario.AAPL), TEST_PATH_COUNT, cadence);
    }

    private static PortfolioLedger openingLedger() {
        return PortfolioLedger.opening(PortfolioId.of("TEST"), Currency.USD,
                CostBasisMethod.FIRST_IN_FIRST_OUT, CashAccount.of(Money.of("1000000", Currency.USD)));
    }

    private static String varLine(List<String> lines) {
        return lines.stream().filter(line -> line.contains("VaR")).findFirst()
                .orElseThrow(() -> new AssertionError("no VaR line in " + lines));
    }
}
