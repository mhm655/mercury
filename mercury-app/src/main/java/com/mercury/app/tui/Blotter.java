package com.mercury.app.tui;

import com.mercury.core.id.CounterpartyId;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeExecuted;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Every trade Mercury's own book was on either side of, in the order they executed - plus a
 * rejected negotiation, which never becomes a {@link Trade} and so never reaches the event
 * bus at all.
 *
 * <p>{@code TuiDemo} appends the rejection case directly from the {@code NegotiationResult}
 * its own step already holds, rather than inventing a new bus event for a UI-only need. The
 * M16 breakdown in {@code docs/DESIGN_PROPOSAL.md} §10.4 flagged this as the open question a
 * blotter would raise; the answer turned out not to need one, since the caller that runs the
 * negotiation already has the richer information the bus does not carry.
 */
final class Blotter implements Consumer<TradeExecuted> {

    private final CounterpartyId ownBook;
    private final List<String> rows = new ArrayList<>();

    Blotter(CounterpartyId ownBook) {
        this.ownBook = ownBook;
    }

    @Override
    public void accept(TradeExecuted event) {
        Trade trade = event.trade();
        if (!trade.owner().equals(ownBook)) {
            return;
        }
        rows.add(String.format(Locale.ROOT, "%-12s %-10s %8s  %16s  %s",
                trade.id(), trade.instrumentId(), trade.delta(), trade.consideration(),
                trade.counterparty().map(c -> "vs " + c).orElse("order book")));
    }

    void recordRejection(String message) {
        rows.add("REJECTED     " + message);
    }

    List<String> rows() {
        return List.copyOf(rows);
    }
}
