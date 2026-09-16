package com.mercury.trade;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Published when a venue has executed a trade - the fact, stated once, for whoever cares.
 *
 * <h2>Why it lives in {@code com.mercury.trade}</h2>
 * The venues that publish it are in {@code com.mercury.execution}; the ledger that consumes it
 * is in {@code com.mercury.portfolio}. Putting the event in either package would force the
 * other to depend on it, and {@code LayeringRulesTest} already forbids the direction that
 * would take (trade and market data must stay usable without execution or risk). An event
 * describing a trade belongs with the trade, and both sides may depend on that.
 *
 * <p>Carries the whole {@link Trade}, not an id: a subscriber that received only an id would
 * need somewhere to look the trade up, which would mean a store, which is exactly the
 * coupling an event exists to avoid.
 *
 * <p>Immutable and thread-safe, which is what makes it safe to hand to subscribers on another
 * thread ({@code AsynchronousEventBus}).
 */
public record TradeExecuted(Trade trade) {

    private static final Set<TradeStatus> EXECUTED_OR_LATER = EnumSet.of(
            TradeStatus.EXECUTED, TradeStatus.CONFIRMED, TradeStatus.SETTLED);

    public TradeExecuted {
        Objects.requireNonNull(trade, "trade");
        if (!EXECUTED_OR_LATER.contains(trade.status())) {
            throw new IllegalArgumentException(
                    "TradeExecuted announces an execution, but " + trade.id() + " is "
                            + trade.status() + "; an order that rested or was cancelled has not "
                            + "executed and must not be announced as though it had");
        }
    }

    @Override
    public String toString() {
        return "executed " + trade.id() + " " + trade.instrumentId() + " " + trade.delta()
                + " for " + trade.consideration();
    }
}
