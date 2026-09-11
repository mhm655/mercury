package com.mercury.app;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.money.Money;
import com.mercury.core.time.Tenor;
import com.mercury.instrument.AccruingInterest;
import com.mercury.curve.YieldCurve;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.marketdata.MarketShock;
import com.mercury.portfolio.PnlStatement;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioLedger;
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
 * <p>M10 adds Gamma and Vega for the same reason: the book has held options with real
 * curvature and volatility sensitivity since M4, and neither number was ever printed. Gamma
 * is reported for every spot factor - it is well-defined, and zero, for a pure stock -
 * while Vega is reported only for {@link RiskFactors#volatilitySpots()}, since it needs a
 * quoted volatility that not every underlying has.
 *
 * <p>The same applies to the accrued-interest block. A bond's unit value here is its full
 * present value, which is the dirty price, and bond markets quote clean. Printing 998.9954
 * under the same heading as a share price, with nothing to say the two are different kinds of
 * number, invites exactly the wrong reading.
 */
public final class ValuationReport {

    private static final String SEPARATOR = "-".repeat(84);
    private static final String ROW = "-".repeat(80);

    private ValuationReport() {
    }

    /** The whole report: positions, total, deltas, and a stress scenario. */
    public static String render(PortfolioLedger ledger, PortfolioValuation valuation,
                                MarketDataSnapshot market, SensitivityCalculator sensitivities,
                                RiskFactors riskFactors, LocalDate asOf) {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(valuation, "valuation");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(sensitivities, "sensitivities");
        Objects.requireNonNull(riskFactors, "riskFactors");
        Objects.requireNonNull(asOf, "asOf");

        Portfolio portfolio = ledger.toPortfolio();
        StringBuilder out = new StringBuilder(2048);
        header(out, portfolio, asOf);
        positions(out, valuation);
        currencies(out, valuation, portfolio);
        cashAndPnl(out, ledger, valuation, market);
        accruals(out, valuation, asOf);
        curves(out, market, riskFactors, asOf);
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
        line(out, "  %-16s %10s %5s %14s %16s  %s",
                "INSTRUMENT", "QUANTITY", "CCY", "UNIT VALUE", "MARKET VALUE", "MODEL");
        line(out, "  %s", ROW);

        for (PortfolioValuation.PositionValuation position : valuation.lines()) {
            // Unit value is quoted in the instrument's own currency and market value in the
            // book's. Naming the currency per line is what keeps that from being a trap: the
            // two columns are in different units whenever CCY is not the reporting currency.
            line(out, "  %-16s %10s %5s %14.4f %16s  %s",
                    position.instrument().id(),
                    position.quantity(),
                    position.localValue().currency().code(),
                    position.unitValue().value(),
                    position.marketValue().amount().toPlainString(),
                    position.unitValue().model());
        }
        line(out, "  %s", ROW);
        line(out, "  %-48s %16s", "TOTAL", valuation.totalValue().amount().toPlainString());
        line(out, "");
    }

    /**
     * Cash, profit already taken, and profit still at risk.
     *
     * <h2>Why realised and unrealised are two rows and never one</h2>
     * Realised profit is a fact: the trade happened, the cash moved, and no later market can
     * change it. Unrealised profit is an opinion - the difference between what a position cost
     * and what a model says it is worth today. Adding them is how a book that has been quietly
     * losing money for a year still looks profitable, and how a good one looks alarming after a
     * bad afternoon.
     *
     * <p>The net asset value line is what makes the cash balances mean something. Positions
     * alone are not what the book is worth; the cash spent acquiring them is part of the same
     * number, and a report showing only the first would make every purchase look like a gain.
     */
    private static void cashAndPnl(StringBuilder out, PortfolioLedger ledger,
                                   PortfolioValuation valuation, MarketDataSnapshot market) {
        Currency reporting = valuation.totalValue().currency();
        line(out, "CASH");
        Money cashInReporting = Money.zero(reporting);
        for (Currency currency : ledger.cash().currencies()) {
            Money balance = ledger.cash().balance(currency);
            line(out, "  %-48s %16s", currency.code(), balance.amount().toPlainString());
            cashInReporting = cashInReporting.plus(currency == reporting
                    ? balance
                    : Money.fromModelValue(
                            balance.amount().doubleValue() * market.fxRate(currency, reporting),
                            reporting));
        }
        line(out, "  %-48s %16s", "NET ASSET VALUE  (positions + cash)",
                valuation.totalValue().plus(cashInReporting).amount().toPlainString());
        line(out, "");

        PnlStatement pnl = PnlStatement.of(ledger, valuation, market);
        line(out, "PROFIT AND LOSS  (%s cost basis)", ledger.costBasisMethod().displayName());
        line(out, "  %-48s %16s", "Realised", pnl.realised().amount().toPlainString());
        line(out, "  %-48s %16s", "Unrealised", pnl.unrealised().amount().toPlainString());
        line(out, "  %-48s %16s", "Total", pnl.total().amount().toPlainString());
        line(out, "");
    }

    /**
     * What the book is worth in each currency it settles in, converted to the reporting one.
     *
     * <p>Skipped entirely for a single-currency book, where every row would repeat the total.
     *
     * <p>This measures <em>settlement</em> exposure: the value of positions that pay in a
     * currency. It is not the same question as the FX delta further down, which measures how
     * the book moves when a rate does - a dollar-settled forward on the euro appears in the
     * second and not the first. Two questions, two numbers, and conflating them is how a
     * currency report ends up double-counting.
     */
    private static void currencies(StringBuilder out, PortfolioValuation valuation,
                                   Portfolio portfolio) {
        List<Currency> currencies = valuation.currencies();
        if (currencies.size() < 2) {
            return;
        }
        line(out, "EXPOSURE BY CURRENCY  (positions settling in each, valued in %s)",
                portfolio.reportingCurrency().code());
        for (Currency currency : currencies) {
            line(out, "  %-48s %16s", currency.code(),
                    valuation.exposureTo(currency).amount().toPlainString());
        }
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
     * Each discount curve, sampled at a few standard horizons.
     *
     * <p>Sampled rather than listed pillar by pillar. The pillars sit on whatever dates the
     * quoted instruments happen to settle, which differ between currencies and make a ragged
     * table; asking each curve for the same four horizons is both readable and a fair
     * demonstration of what a curve is for, since three of the four fall between pillars and
     * are answers the interpolator had to produce.
     */
    private static void curves(StringBuilder out, MarketDataSnapshot market,
                               RiskFactors riskFactors, LocalDate asOf) {
        if (riskFactors.rateCurrencies().isEmpty() || riskFactors.curveTenors().isEmpty()) {
            return;
        }

        StringBuilder header = new StringBuilder("  %-10s".formatted("CURRENCY"));
        for (Tenor tenor : riskFactors.curveTenors()) {
            header.append("%10s".formatted(tenor));
        }
        line(out, "DISCOUNT CURVES  (zero rates, continuously compounded, bootstrapped from quotes)");
        line(out, "%s", header);

        for (Currency currency : riskFactors.rateCurrencies()) {
            YieldCurve curve = market.yieldCurve(currency);
            StringBuilder row = new StringBuilder("  %-10s".formatted(currency.code()));
            for (Tenor tenor : riskFactors.curveTenors()) {
                double years = curve.timeTo(tenor.addTo(asOf));
                row.append("%9.4f%%".formatted(curve.zeroRate(years) * 100.0));
            }
            line(out, "%s", row);
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

        line(out, "  GAMMA  (value change per unit^2, the curvature delta alone misses)");
        for (InstrumentId factor : riskFactors.spots()) {
            line(out, "    %-16s %16.4f",
                    factor, sensitivities.gamma(portfolio, factor, market, asOf));
        }

        if (!riskFactors.volatilitySpots().isEmpty()) {
            line(out, "  VEGA  (value change per 1 vol point rise)");
            for (InstrumentId factor : riskFactors.volatilitySpots()) {
                line(out, "    %-16s %16.4f",
                        factor, sensitivities.vega(portfolio, factor, market, asOf));
            }
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
        //
        // The rates leg was missing until M6. The design document had specified this scenario
        // as equities down, volatility up, FX down AND rates up 150bp from the start, and the
        // implementation quietly dropped the last one - which nobody noticed while the book
        // held no material rate risk. A bond and a swap made the omission expensive: the
        // headline stress number was describing three quarters of a market crash.
        MarketShock crash = MarketShock.scaleAllSpots(0.70)
                .and(MarketShock.scaleAllVolatilities(1.50))
                .and(MarketShock.scaleAllFxRates(0.90))
                .and(MarketShock.bumpAllRates(BasisPoints.of(150)));

        Money impact = sensitivities.valueChangeUnder(portfolio, crash, market, asOf);

        line(out, "STRESS  (equities -30%%, volatility +50%%, FX -10%%, rates +150bp)");
        line(out, "  %-42s %16s", "P&L impact", impact.amount().toPlainString());
    }

    /** Appends one formatted line, always terminated by a literal newline. */
    private static void line(StringBuilder out, String format, Object... arguments) {
        out.append(String.format(Locale.ROOT, format, arguments)).append('\n');
    }
}
