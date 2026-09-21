package com.mercury.app.tui;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.OrderId;
import com.mercury.execution.OrderBookInstruction;
import com.mercury.matching.Order;
import com.mercury.matching.OrderBook;

/**
 * A second, display-only {@link OrderBook} per instrument, fed the exact same instructions
 * as the real venue.
 *
 * <p>{@code OrderBookVenue} keeps its own books in a private map, by design (§5.6: single
 * writer, never touched outside its own lane) - so this does not reach into it. An
 * {@link OrderBook} is a deterministic function of the orders it receives, so mirroring the
 * same instruction sequence into a second book, with its own locally minted ids that never
 * need to match the venue's, produces identical depth without exposing any mutable state
 * the venue does not already expose itself. {@code mercury-engine} gains nothing for this -
 * every type used here was already public.
 */
final class ShadowBook {

    private final OrderBook book;
    private int nextId = 1;

    ShadowBook(InstrumentId instrumentId) {
        book = new OrderBook(instrumentId);
    }

    /** Submits the instruction a real venue call just received, for display only. */
    void mirror(OrderBookInstruction instruction) {
        OrderId id = OrderId.of("SHADOW-" + nextId++);
        Order order = new Order(id, instruction.instrumentId(), instruction.side(),
                instruction.type(), instruction.limitPrice(), instruction.quantity(),
                instruction.timeInForce(), instruction.participant());
        book.submit(order);
    }

    OrderBook book() {
        return book;
    }
}
