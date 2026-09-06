package com.mercury.app;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Money;
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
 */
public final class ValuationReport {

    private static final String SEPARATOR = "-".repeat(78);
    private static final String ROW = "-".repeat(74);

    private ValuationReport() {
    }

    /** The whole report: positions, total, deltas, and a stress scenario. */
    public static String render(Portfolio portfolio, PortfolioValuation valuation,
                                MarketDataSnapshot market, SensitivityCalculator sensitivities,
                                List<InstrumentId> riskFactors, LocalDate asOf) {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(valuation, "valuation");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(sensitivities, "sensitivities");
        Objects.requireNonNull(riskFactors, "riskFactors");
        Objects.requireNonNull(asOf, "asOf");

        StringBuilder out = new StringBuilder(2048);
        header(out, portfolio, asOf);
        positions(out, valuation);
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

    private static void risk(StringBuilder out, Portfolio portfolio, MarketDataSnapshot market,
                             SensitivityCalculator sensitivities, List<InstrumentId> riskFactors,
                             LocalDate asOf) {
        line(out, "DELTA  (portfolio value change per unit move in spot)");
        for (InstrumentId factor : riskFactors) {
            line(out, "  %-16s %14.4f",
                    factor, sensitivities.delta(portfolio, factor, market, asOf));
        }
        line(out, "");
    }

    private static void stress(StringBuilder out, Portfolio portfolio, MarketDataSnapshot market,
                               SensitivityCalculator sensitivities, LocalDate asOf) {
        // The same shock mechanism the deltas above use, applied at scenario scale rather than
        // as an infinitesimal bump - which is the point of DESIGN_PROPOSAL.md section 5.3.
        MarketShock crash = MarketShock.scaleAllSpots(0.70)
                .and(MarketShock.scaleAllVolatilities(1.50));

        Money impact = sensitivities.valueChangeUnder(portfolio, crash, market, asOf);

        line(out, "STRESS  (equities -30%%, volatility +50%%)");
        line(out, "  %-42s %16s", "P&L impact", impact.amount().toPlainString());
    }

    /** Appends one formatted line, always terminated by a literal newline. */
    private static void line(StringBuilder out, String format, Object... arguments) {
        out.append(String.format(Locale.ROOT, format, arguments)).append('\n');
    }
}
