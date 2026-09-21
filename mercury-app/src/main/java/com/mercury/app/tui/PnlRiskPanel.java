package com.mercury.app.tui;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.portfolio.PnlStatement;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioLedger;
import com.mercury.portfolio.PortfolioValuation;
import com.mercury.portfolio.PortfolioValuationService;
import com.mercury.risk.SensitivityCalculator;
import com.mercury.simulation.MonteCarloRiskResult;
import com.mercury.simulation.MonteCarloVaRCalculator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * P&amp;L and risk, per the M16c row of the table in {@code docs/DESIGN_PROPOSAL.md} §10.4:
 * valuation, P&amp;L, delta and DV01 are recomputed on every {@link #render} call because they
 * are cheap; Monte Carlo VaR is not, so it is recomputed only every {@link #varCadence} calls
 * and the stale figure is shown, labelled with the step it came from, on the calls between.
 *
 * <p>Pure data assembly and one small piece of cached state - no ANSI and no terminal, so it
 * is testable the same way {@link BookDepthView} and {@link Blotter} are. {@code TuiDemo} adds
 * colour and writes the result.
 */
final class PnlRiskPanel {

    private final PortfolioValuationService valuationService;
    private final SensitivityCalculator sensitivities;
    private final MonteCarloVaRCalculator varCalculator;
    private final InstrumentId varUnderlying;
    private final double varVolatility;
    private final int varPathCount;
    private final int varCadence;

    private MonteCarloRiskResult lastVar;
    private int lastVarStep = -1;

    PnlRiskPanel(PortfolioValuationService valuationService, SensitivityCalculator sensitivities,
                MonteCarloVaRCalculator varCalculator, InstrumentId varUnderlying,
                double varVolatility, int varPathCount, int varCadence) {
        this.valuationService = Objects.requireNonNull(valuationService, "valuationService");
        this.sensitivities = Objects.requireNonNull(sensitivities, "sensitivities");
        this.varCalculator = Objects.requireNonNull(varCalculator, "varCalculator");
        this.varUnderlying = Objects.requireNonNull(varUnderlying, "varUnderlying");
        this.varVolatility = varVolatility;
        if (varPathCount <= 0) {
            throw new IllegalArgumentException(
                    "varPathCount must be positive, but was " + varPathCount);
        }
        this.varPathCount = varPathCount;
        if (varCadence <= 0) {
            throw new IllegalArgumentException("varCadence must be positive, but was " + varCadence);
        }
        this.varCadence = varCadence;
    }

    /**
     * @param stepsRun how many scenario steps have run so far - the tick this call is being
     *                 asked to render, and what throttles the VaR recompute
     */
    List<String> render(PortfolioLedger ledger, MarketDataSnapshot market, LocalDate asOf,
                        int stepsRun) {
        Portfolio portfolio = ledger.toPortfolio();
        if (portfolio.size() == 0) {
            return List.of("  (no positions yet)");
        }

        PortfolioValuation valuation = valuationService.value(portfolio, market, asOf);
        PnlStatement pnl = PnlStatement.of(ledger, valuation, market);

        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "  positions total     %s", valuation.totalValue()));
        lines.add(String.format(Locale.ROOT, "  realised P&L        %s", pnl.realised()));
        lines.add(String.format(Locale.ROOT, "  unrealised P&L      %s", pnl.unrealised()));
        lines.add(String.format(Locale.ROOT, "  %-4s delta          %.2f", varUnderlying,
                sensitivities.delta(portfolio, varUnderlying, market, asOf)));
        lines.add(String.format(Locale.ROOT, "  USD DV01            %.2f",
                sensitivities.dv01(portfolio, Currency.USD, market, asOf)));
        lines.add(varLine(portfolio, market, asOf, stepsRun));
        return lines;
    }

    private String varLine(Portfolio portfolio, MarketDataSnapshot market, LocalDate asOf,
                           int stepsRun) {
        if (lastVar == null || stepsRun - lastVarStep >= varCadence) {
            lastVar = varCalculator.simulate(portfolio, varUnderlying, 0.0, varVolatility,
                    1.0 / 365.0, varPathCount, market, asOf, 0.99);
            lastVarStep = stepsRun;
        }
        String staleness = lastVarStep == stepsRun ? "" : " (as of step " + lastVarStep + ")";
        return String.format(Locale.ROOT, "  99%% 1-day VaR       %s%s", lastVar.valueAtRisk(),
                staleness);
    }
}
