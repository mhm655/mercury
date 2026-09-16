package com.mercury.portfolio;

import com.mercury.core.id.CounterpartyId;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeExecuted;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Keeps one owner's {@link PortfolioLedger} up to date from {@link TradeExecuted} events:
 * books the trades that belong to {@code owner} and ignores everyone else's.
 *
 * <h2>Why a keeper rather than a ledger that listens</h2>
 * {@link PortfolioLedger} is immutable - booking a trade returns a new ledger - which is what
 * makes valuation a pure function of a ledger and makes pro-forma projection free. A value
 * like that cannot subscribe to anything: there is nothing for an event to update. So the
 * mutable cell lives here, in exactly one small class, and the ledger stays what it was.
 *
 * <h2>Filtering by owner is the whole job</h2>
 * A central limit order book publishes both sides of every fill, so a venue announces trades
 * belonging to other participants as routinely as it announces ours. Booking those would put
 * the market maker's inventory into our book. The {@code EndToEndDemo} walkthrough used to do
 * this filtering by hand in the demo itself, which meant the rule lived in a demo rather than
 * in the engine - this is that rule, moved somewhere it can be tested.
 *
 * <h2>Duplicate executions</h2>
 * Not this class's problem: {@code PortfolioLedger.book} already refuses to book a trade id
 * twice, so an event delivered twice cannot double-count a position. Silently ignoring a
 * repeat here instead would hide a bus that duplicates events, which is a defect worth seeing.
 *
 * <p>Thread-safe: {@link #accept} is synchronized, so this works behind
 * {@code AsynchronousEventBus} (deliveries arrive on the dispatcher thread while the ledger is
 * read from another) as well as the synchronous one.
 */
public final class LedgerKeeper implements Consumer<TradeExecuted> {

    private final CounterpartyId owner;
    private PortfolioLedger ledger;

    public LedgerKeeper(CounterpartyId owner, PortfolioLedger opening) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ledger = Objects.requireNonNull(opening, "opening");
    }

    /** Books {@code event}'s trade if this keeper's owner made it, and ignores it otherwise. */
    @Override
    public synchronized void accept(TradeExecuted event) {
        Objects.requireNonNull(event, "event");
        Trade trade = event.trade();
        if (trade.owner().equals(owner)) {
            ledger = ledger.book(trade);
        }
    }

    /** The ledger as of every event delivered so far. */
    public synchronized PortfolioLedger ledger() {
        return ledger;
    }

    public CounterpartyId owner() {
        return owner;
    }
}
