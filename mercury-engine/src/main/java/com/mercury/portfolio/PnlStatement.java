package com.mercury.portfolio;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.marketdata.MarketDataSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Profit and loss, split into the part already taken and the part still at risk.
 *
 * <h2>Two numbers that are not the same kind of thing</h2>
 * <b>Realised</b> profit is a fact. It came from a trade, the cash has moved, and no later
 * market can change it. <b>Unrealised</b> profit is an opinion - it is the difference between
 * what a position cost and what a model says it is worth today, and it moves every time the
 * market does.
 *
 * <p>Reporting them as one figure is how a book that has been quietly losing money for a year
 * still looks profitable, and how a genuinely profitable one looks alarming after a bad
 * afternoon. They are kept apart here for the same reason the valuation keeps its lines: a
 * total nobody can decompose is a number nobody can trust.
 *
 * <h2>Where the two models meet</h2>
 * This is the only type that reads both a {@link PortfolioLedger} and a
 * {@link PortfolioValuation}. Cost basis comes from the history, market value from the market,
 * and neither side has to learn about the other for the subtraction to happen. That the join
 * needs one small type and no changes to either half is the payoff for keeping them separate.
 *
 * <h2>Currency</h2>
 * Cost basis is recorded in the currency a position was transacted in and market value in the
 * book's reporting currency, so the difference is taken only after converting the first - at
 * today's rate, which is the same rate the valuation used. That means a foreign position's
 * unrealised P&amp;L includes the currency move as well as the price move, which is correct:
 * it is what the holder would actually have made.
 */
public record PnlStatement(
        Currency reportingCurrency,
        Money realised,
        Money unrealised,
        List<Line> lines) {

    public PnlStatement {
        Objects.requireNonNull(reportingCurrency, "reportingCurrency");
        Objects.requireNonNull(realised, "realised");
        Objects.requireNonNull(unrealised, "unrealised");
        lines = List.copyOf(lines);
    }

    /**
     * Joins a book's history to a valuation of it.
     *
     * <h2>Both directions are checked, and only one used to be</h2>
     * The mismatch this guards against has two sides, and the asymmetric half is the
     * dangerous one. A valuation holding a position the ledger does not throws immediately -
     * {@code costBasisOf} has nothing to subtract. But a <em>ledger</em> position the
     * valuation omits used to pass silently: its cost contributed no unrealised line while
     * its realised profit was still counted, so the statement came out plausible and low, and
     * the reconciliation this project leans on - net asset value minus opening cash equals
     * total profit - would have quietly stopped holding.
     *
     * <p>Unreachable in current wiring, since every caller values
     * {@code ledger.toPortfolio()}. That is an argument for checking it, not against: the
     * guarantee comes from how the callers happen to be written today, and this method's
     * javadoc has always promised the check outright.
     *
     * @throws IllegalArgumentException if the valuation is of a different set of positions,
     *                                  in either direction
     */
    public static PnlStatement of(PortfolioLedger ledger, PortfolioValuation valuation,
                                  MarketDataSnapshot market) {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(valuation, "valuation");
        Objects.requireNonNull(market, "market");

        Set<InstrumentId> valued = valuation.lines().stream()
                .map(line -> line.instrument().id())
                .collect(Collectors.toSet());
        List<InstrumentId> unvalued = ledger.instruments().stream()
                .filter(held -> !valued.contains(held))
                .toList();
        if (!unvalued.isEmpty()) {
            throw new IllegalArgumentException(
                    "The ledger holds " + unvalued + " but the valuation does not value "
                            + (unvalued.size() == 1 ? "it" : "them") + ". Leaving a held "
                            + "position out would drop its unrealised profit while still "
                            + "counting its realised, and report a total that looks like an "
                            + "answer; these are two different books.");
        }

        Currency reporting = valuation.totalValue().currency();
        List<Line> lines = new ArrayList<>(valuation.lines().size());
        Money unrealisedTotal = Money.zero(reporting);

        for (PortfolioValuation.PositionValuation position : valuation.lines()) {
            InstrumentId instrumentId = position.instrument().id();
            if (ledger.quantityOf(instrumentId).isZero()) {
                throw new IllegalArgumentException(
                        "The valuation holds " + instrumentId + " but the ledger does not. A "
                                + "profit-and-loss statement joins one book's history to a "
                                + "valuation of that same book; these are two different books.");
            }
            Money costLocal = ledger.costBasisOf(instrumentId);
            Money costReporting = convert(costLocal, reporting, market);
            Money lineUnrealised = position.marketValue().minus(costReporting);

            lines.add(new Line(instrumentId, costLocal, costReporting,
                    position.marketValue(), lineUnrealised));
            unrealisedTotal = unrealisedTotal.plus(lineUnrealised);
        }

        Money realisedTotal = Money.zero(reporting);
        for (Currency currency : ledger.realisedCurrencies()) {
            realisedTotal = realisedTotal.plus(
                    convert(ledger.realisedPnl(currency), reporting, market));
        }
        return new PnlStatement(reporting, realisedTotal, unrealisedTotal, lines);
    }

    /** Realised plus unrealised: what the book has made altogether, so far. */
    public Money total() {
        return realised.plus(unrealised);
    }

    /**
     * The documented crossing from a ledger amount into a reported one.
     *
     * <p>The FX rate is a {@code double} from the snapshot, so this passes through the model
     * domain and back - which is why it goes through {@code Money.fromModelValue} rather than
     * pretending to be exact decimal arithmetic (ADR 0001). A same-currency conversion short
     * circuits, so a single-currency book never crosses at all.
     */
    private static Money convert(Money amount, Currency target, MarketDataSnapshot market) {
        if (amount.currency() == target) {
            return amount;
        }
        double rate = market.fxRate(amount.currency(), target);
        return Money.fromModelValue(amount.amount().doubleValue() * rate, target);
    }

    /**
     * One position's contribution.
     *
     * <p>Both cost figures are kept - what it cost where it was bought, and what that is worth
     * in the reporting currency today - because their difference is exactly the currency move,
     * and a reader who cannot see both cannot tell a price gain from an FX gain.
     */
    public record Line(
            InstrumentId instrumentId,
            Money costBasisLocal,
            Money costBasisReporting,
            Money marketValue,
            Money unrealised) {

        public Line {
            Objects.requireNonNull(instrumentId, "instrumentId");
            Objects.requireNonNull(costBasisLocal, "costBasisLocal");
            Objects.requireNonNull(costBasisReporting, "costBasisReporting");
            Objects.requireNonNull(marketValue, "marketValue");
            Objects.requireNonNull(unrealised, "unrealised");
        }

        /** True when the position was transacted in a currency the book does not report in. */
        public boolean isForeign() {
            return costBasisLocal.currency() != costBasisReporting.currency();
        }
    }
}
