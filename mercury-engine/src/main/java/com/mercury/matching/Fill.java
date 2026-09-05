package com.mercury.matching;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.OrderId;
import com.mercury.core.money.Price;
import java.util.Objects;

/**
 * One execution: a quantity crossed between two orders at one price.
 *
 * <h2>Why the price is the resting order's</h2>
 * When a buy at 101 hits a resting sell at 100, the trade happens at <b>100</b>, not 101.
 * The resting order named its price first and is entitled to it; the aggressor gets price
 * improvement. Executing at the aggressor's price instead would let anyone extract value by
 * crossing with a deliberately wide limit, and would make the book's displayed prices a
 * fiction.
 *
 * <p>This is the single most important rule in the matching engine and the one most often
 * got wrong, so it is stated here, enforced in {@code OrderBook}, and tested directly.
 *
 * <h2>Sequence, not timestamp</h2>
 * {@code sequence} is assigned by the book and increases monotonically. It orders fills
 * deterministically without consulting a clock - which keeps replay reproducible and avoids
 * ties, since two fills can easily share a millisecond.
 *
 * <p>Immutable and thread-safe.
 */
public record Fill(
        long sequence,
        InstrumentId instrumentId,
        OrderId restingOrderId,
        OrderId aggressingOrderId,
        Side aggressorSide,
        Price price,
        long quantity) {

    public Fill {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(restingOrderId, "restingOrderId");
        Objects.requireNonNull(aggressingOrderId, "aggressingOrderId");
        Objects.requireNonNull(aggressorSide, "aggressorSide");
        Objects.requireNonNull(price, "price");

        if (quantity <= 0) {
            throw new IllegalArgumentException("Fill quantity must be positive, but was " + quantity);
        }
        if (restingOrderId.equals(aggressingOrderId)) {
            throw new IllegalArgumentException(
                    "An order cannot trade with itself (" + restingOrderId + ")");
        }
    }

    /** The order id on the buy side of this execution. */
    public OrderId buyOrderId() {
        return aggressorSide.isBuy() ? aggressingOrderId : restingOrderId;
    }

    /** The order id on the sell side of this execution. */
    public OrderId sellOrderId() {
        return aggressorSide.isBuy() ? restingOrderId : aggressingOrderId;
    }

    @Override
    public String toString() {
        return "#%d %s %d @ %s (%s aggressor)".formatted(
                sequence, instrumentId, quantity, price, aggressorSide.displayName());
    }
}
