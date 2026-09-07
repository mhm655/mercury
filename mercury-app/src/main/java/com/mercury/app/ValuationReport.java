package com.mercury.app;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.money.Money;
import com.mercury.instrument.AccruingInterest;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.marketdata.MarketShock;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioValuation;
import com.mercury.risk.SensitivityCalculator;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Renders a valuation, its risk and a stress scenario as plain text.
 *
 * <h2>A String, not a print</h2>
 * This builds and returns the report rather than writing it to the console. That is what lets
 * the golden-master test assert on the exact bytes, and it keeps the engine free of output
 * concerns - {@code Main} is the only thing here that knows a console exists.
 *
 * <h2>Deterministic output is a requirement, not a nicety</h2>
 * Two platform defaults would break the golden master for reasons unrelated to the engine,
 * and both are pinned here:
 *
 * <ul>
 *   <li><b>{@link Locale#ROOT}</b> for every format. Under a locale with a comma decimal
 *       separator the same number renders differently, so the test would fail on a colleague's
 *       machine and pass on mine.</li>
 *   <li><b>Literal {@code \n}</b>, never {@code %n} or {@code System.lineSeparator()}. Those
 *       emit CRLF on Windows and LF on Linux, so a report captured locally would not match one
 *       produced in CI. {@code %n} is the easy one to miss - it looks like a formatting detail
 *       rather than a platform dependency.</li>
 * </ul>
 *
 * <h2>Reporting the risk the book actually carries</h2>
 * The RISK section shows spot delta, FX delta and DV01 because after M5 the demo portfolio
 * held a bond and an FX forward, and a report showing equity delta alone described about a
 * third of it. The exposure was already in the engine - the stress line moved with it - so
 * the gap was in what was printed, which is the harder kind to notice.
 *
 * <p>The same applies to the accrued-interest block. A bond's unit value here is its full
 * present value, which is the dirty price, and bond markets quote clean. Printing 998.9954
 * under the same heading as a share price, with nothing to say the two are different kinds of
 * number, invites exactly the wrong reading.
 */
public final class ValuationReport {

    private static final String SEPARATOR = "-".repeat(78);
    private static final String ROW = "-".repeat(74);

    private ValuationReport() {
    }

    /** The whole report: positions, total, deltas, and a stress scenario. */
    public static String render(Portfolio portfolio, PortfolioValuation valuation,
                                MarketDataSnapshot market, SensitivityCalculator sensitivities,
                                RiskFactors riskFactors, LocalDate asOf) {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(valuation, "valuation");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(sensitivities, "sensitivities");
        Objects.requireNonNull(riskFactors, "riskFactors");
        Objects.requireNonNull(asOf, "asOf");

        StringBuilder out = new StringBuilder(2048);
        header(out, portfolio, asOf);
        positions(out, valuation);
        accruals(out, valuation, asOf);
        risk(out, portfolio, market, sensitivities, riskFactors, asOf);
        stress(out, portfolio, market, sensitivities, asOf);
        return out.toString();
    }

    private static void header(StringBuilder out, Portfolio portfolio, LocalDate asOf) {
        line(out, "MERCURY - portfolio valuation");
        line(out, SEPARATOR);
        line(out, "Portfolio      : %s", portfolio.id());
        line(out, "Valuation date : %s", asOf);
        line(out, "Currency       : %s", portfolio.reportingCurrency().code());
        line(out, "");
    }

    private static void positions(StringBuilder out, PortfolioValuation valuation) {
        line(out, "POSITIONS");
        line(out, "  %-16s %10s %14s %16s  %s",
                "INSTRUMENT", "QUANTITY", "UNIT VALUE", "MARKET VALUE", "MODEL");
        line(out, "  %s", ROW);

        for (PortfolioValuation.PositionValuation position : valuation.lines()) {
            line(out, "  %-16s %10s %14.4f %16s  %s",
                    position.instrument().id(),
                    position.quantity(),
                    position.unitValue().value(),
                    position.marketValue().amount().toPlainString(),
                    position.unitValue().model());
        }
        line(out, "  %s", ROW);
        line(out, "  %-42s %16s", "TOTAL", valuation.totalValue().amount().toPlainString());
        line(out, "");
    }

    /**
     * Clean, accrued and dirty for every position that accrues interest.
     *
     * <p>The filter asks for a capability rather than a type. An {@code instanceof Bond} here
     * would work today and would be the first branch of the chain this design exists to avoid.
     * {@link AccruingInterest} is the same question asked so that a second accruing instrument
     * needs no change to this method.
     *
     * <p>Clean is derived as dirty minus the <em>settled</em> accrued figure - the rounded
     * {@link Money} amount, not the unrounded fraction behind it - so that the three printed
     * columns add up exactly. A report whose own row does not reconcile is worse than one
     * column fewer, and accrued interest really does change hands in whole cents.
     */
    private static void accruals(StringBuilder out, PortfolioValuation valuation, LocalDate asOf) {
        List<PortfolioValuation.PositionValuation> accruing = valuation.lines().stream()
                .filter(position -> position.instrument() instanceof AccruingInterest)
                .toList();
        if (accruing.isEmpty()) {
            return;
        }

        line(out, "ACCRUED INTEREST  (unit values above are dirty: clean + accrued)");
        line(out, "  %-16s %14s %14s %14s", "INSTRUMENT", "CLEAN", "ACCRUED", "DIRTY");
        for (PortfolioValuation.PositionValuation position : accruing) {
            AccruingInterest accruer = (AccruingInterest) position.instrument();
            double accrued = accruer.accruedInterest(asOf).amount().doubleValue();
            double dirty = position.unitValue().value();
            line(out, "  %-16s %14.4f %14.4f %14.4f",
                    position.instrument().id(), dirty - accrued, accrued, dirty);
        }
        line(out, "");
    }

    /**
     * Every risk factor the report was asked for, each measured the same way: shock, revalue,
     * difference. Three kinds of risk, one mechanism, no per-instrument code.
     */
    private static void risk(StringBuilder out, Portfolio portfolio, MarketDataSnapshot market,
                             SensitivityCalculator sensitivities, RiskFactors riskFactors,
                             LocalDate asOf) {
        line(out, "RISK");

        line(out, "  DELTA  (value change per unit rise in spot)");
        for (InstrumentId factor : riskFactors.spots()) {
            line(out, "    %-16s %16.4f",
                    factor, sensitivities.delta(portfolio, factor, market, asOf));
        }

        if (!riskFactors.fxPairs().isEmpty()) {
            line(out, "  FX DELTA  (value change per unit rise in the rate)");
            for (CurrencyPair pair : riskFactors.fxPairs()) {
                line(out, "    %-16s %16.4f",
                        pair, sensitivities.fxDelta(portfolio, pair, market, asOf));
            }
        }

        if (!riskFactors.rateCurrencies().isEmpty()) {
            line(out, "  DV01  (value change per +1bp on the discount rate)");
            for (Currency currency : riskFactors.rateCurrencies()) {
                line(out, "    %-16s %16.4f",
                        currency.code(), sensitivities.dv01(portfolio, currency, market, asOf));
            }
        }
        line(out, "");
    }

    private static void stress(StringBuilder out, Portfolio portfolio, MarketDataSnapshot market,
                               SensitivityCalculator sensitivities, LocalDate asOf) {
        // The same shock mechanism the deltas above use, applied at scenario scale rather than
        // as an infinitesimal bump - which is the point of DESIGN_PROPOSAL.md section 5.3.
        MarketShock crash = MarketShock.scaleAllSpots(0.70)
                .and(MarketShock.scaleAllVolatilities(1.50))
                .and(MarketShock.scaleAllFxRates(0.90));

        Money impact = sensitivities.valueChangeUnder(portfolio, crash, market, asOf);

        line(out, "STRESS  (equities -30%%, volatility +50%%, FX -10%%)");
        line(out, "  %-42s %16s", "P&L impact", impact.amount().toPlainString());
    }

    /** Appends one formatted line, always terminated by a literal newline. */
    private static void line(StringBuilder out, String format, Object... arguments) {
        out.append(String.format(Locale.ROOT, format, arguments)).append('\n');
    }
}
