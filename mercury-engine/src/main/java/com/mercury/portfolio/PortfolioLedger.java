package com.mercury.portfolio;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.money.Quantity;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the book has done: every open lot, the cash, and the profit already taken.
 *
 * <h2>The difference between this and Portfolio</h2>
 * {@link Portfolio} says what is held <em>now</em> - an instrument and a quantity - and that
 * is all a valuation needs. A ledger says how the book got there, which is what realised
 * profit, cost basis and cash all depend on and none of which can be recovered from a
 * quantity alone.
 *
 * <p>They are kept separate rather than merged because the valuation stack has no business
 * knowing about trade history, and a ledger has no business knowing about market data. The
 * bridge is {@link #toPortfolio}, which projects the history down to the positions it implies.
 *
 * <h2>Cash moves with every trade</h2>
 * A purchase is not just an increase in a holding; it is an increase in a holding <em>and</em>
 * a decrease in cash, and a ledger that recorded only the first would show a book growing out
 * of nothing. Recording both is what makes the total value of the book invariant across a
 * trade at market: you exchange one asset for another and are no richer for it, which is
 * asserted directly in the tests.
 *
 * <h2>Immutable</h2>
 * Every operation returns a new ledger. That is what a history is - a sequence of states, each
 * of which was true at the time - and it makes replay, audit and what-if analysis fall out
 * rather than needing to be built.
 */
public final class PortfolioLedger {

    private final PortfolioId id;
    private final Currency reportingCurrency;
    private final CostBasisMethod costBasisMethod;
    private final Map<InstrumentId, PositionLots> positions;
    private final CashAccount cash;
    private final Map<Currency, Money> realised;

    private PortfolioLedger(PortfolioId id, Currency reportingCurrency,
                            CostBasisMethod costBasisMethod,
                            Map<InstrumentId, PositionLots> positions, CashAccount cash,
                            Map<Currency, Money> realised) {
        this.id = id;
        this.reportingCurrency = reportingCurrency;
        this.costBasisMethod = costBasisMethod;
        this.positions = positions;
        this.cash = cash;
        this.realised = realised;
    }

    /** A new book with an opening cash balance and nothing held. */
    public static PortfolioLedger opening(PortfolioId id, Currency reportingCurrency,
                                          CostBasisMethod costBasisMethod, CashAccount cash) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reportingCurrency, "reportingCurrency");
        Objects.requireNonNull(costBasisMethod, "costBasisMethod");
        Objects.requireNonNull(cash, "cash");
        return new PortfolioLedger(id, reportingCurrency, costBasisMethod, Map.of(), cash,
                Map.of());
    }

    /** Buys {@code quantity} at {@code unitPrice}. */
    public PortfolioLedger buy(InstrumentId instrumentId, Quantity quantity, Price unitPrice,
                               Currency currency, LocalDate tradeDate) {
        return tradeAt(instrumentId, quantity, unitPrice, currency, tradeDate);
    }

    /** Sells {@code quantity} at {@code unitPrice}. The quantity is given positive. */
    public PortfolioLedger sell(InstrumentId instrumentId, Quantity quantity, Price unitPrice,
                                Currency currency, LocalDate tradeDate) {
        Objects.requireNonNull(quantity, "quantity");
        return tradeAt(instrumentId, Quantity.of(quantity.value().negate()), unitPrice, currency,
                tradeDate);
    }

    /**
     * Books a signed change in holding at a price.
     *
     * <p>The consideration is formed once, from the exact quantity and the exact price, and
     * rounds into {@link Money} a single time - the same rule the valuation layer follows, for
     * the same reason. A {@link Price} carries eight decimal places precisely so that this
     * multiplication is not done against an already-rounded figure.
     */
    public PortfolioLedger tradeAt(InstrumentId instrumentId, Quantity delta, Price unitPrice,
                                   Currency currency, LocalDate tradeDate) {
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(currency, "currency");
        return trade(instrumentId, delta,
                Money.of(delta.value().multiply(unitPrice.value()), currency), tradeDate);
    }

    /**
     * Books a signed change in holding for a stated consideration.
     *
     * <p>The primitive the priced form is built on, and the one a derivative needs. A swap or
     * an FX forward entered at the market costs <em>nothing</em> to put on: no cash moves, and
     * its cost basis is zero, so its entire value is unrealised from the first day. There is no
     * price to quote for such a trade - {@link Price} rejects zero, and rightly, because a
     * price of nothing is not a price - so the consideration is given directly.
     *
     * <p>Signed the same way throughout: positive consideration is money paid out. Selling is
     * a negative delta and therefore a negative consideration, which is money coming in.
     */
    public PortfolioLedger trade(InstrumentId instrumentId, Quantity delta, Money consideration,
                                 LocalDate tradeDate) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(consideration, "consideration");
        Objects.requireNonNull(tradeDate, "tradeDate");

        Currency currency = consideration.currency();
        Money cashFlow = consideration.negated();

        PositionLots before = positions.getOrDefault(instrumentId, PositionLots.empty());
        PositionLots.Applied applied =
                before.apply(delta, cashFlow, tradeDate, costBasisMethod, instrumentId);

        Map<InstrumentId, PositionLots> updated = new LinkedHashMap<>(positions);
        if (applied.position().isEmpty()) {
            updated.remove(instrumentId);
        } else {
            updated.put(instrumentId, applied.position());
        }

        Map<Currency, Money> updatedRealised = new LinkedHashMap<>(realised);
        if (!applied.realised().isZero()) {
            updatedRealised.merge(currency, applied.realised(), Money::plus);
        }

        return new PortfolioLedger(id, reportingCurrency, costBasisMethod,
                Collections.unmodifiableMap(updated), cash.with(cashFlow),
                Collections.unmodifiableMap(updatedRealised));
    }

    /**
     * The positions this history implies, ready to be valued.
     *
     * <p>The projection that keeps the two models apart. Everything the valuation stack needs
     * is here and nothing it does not: no lots, no cash, no realised profit.
     */
    public Portfolio toPortfolio() {
        Portfolio.Builder builder = Portfolio.builder(id, reportingCurrency);
        positions.forEach((instrumentId, lots) -> builder.position(instrumentId, lots.quantity()));
        return builder.build();
    }

    /** The current holding, zero if the book is flat in it. */
    public Quantity quantityOf(InstrumentId instrumentId) {
        return positions.getOrDefault(instrumentId, PositionLots.empty()).quantity();
    }

    /**
     * What the open position in {@code instrumentId} cost, in its own currency.
     *
     * @throws IllegalStateException if the book holds none of it
     */
    public Money costBasisOf(InstrumentId instrumentId) {
        PositionLots lots = positions.get(instrumentId);
        if (lots == null) {
            throw new IllegalStateException(
                    "The book holds no " + instrumentId + ", so there is no cost basis to "
                            + "report. A flat position would have to invent a currency to be "
                            + "zero in.");
        }
        return lots.costBasis();
    }

    /** Profit already taken in {@code currency}, over the life of the book. */
    public Money realisedPnl(Currency currency) {
        Objects.requireNonNull(currency, "currency");
        return realised.getOrDefault(currency, Money.zero(currency));
    }

    /** Every currency the book has realised profit or loss in. */
    public List<Currency> realisedCurrencies() {
        return List.copyOf(realised.keySet());
    }

    public CashAccount cash() {
        return cash;
    }

    public CostBasisMethod costBasisMethod() {
        return costBasisMethod;
    }

    /** Every instrument with an open position, in the order first traded. */
    public List<InstrumentId> instruments() {
        return List.copyOf(positions.keySet());
    }

    @Override
    public String toString() {
        return "PortfolioLedger(" + id + ", " + positions.size() + " positions, " + cash + ")";
    }
}
