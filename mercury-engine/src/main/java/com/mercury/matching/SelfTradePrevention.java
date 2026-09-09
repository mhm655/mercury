package com.mercury.matching;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.OrderId;
import java.util.Objects;

/**
 * One instance of self-trade prevention firing: a resting order and an incoming order shared
 * an owner, so the quantity that would have crossed between them was left unmatched instead.
 *
 * <h2>Why this is a fact, not silence</h2>
 * The trade this quantity would have made is real information - a real venue's self-trade
 * prevention produces an auditable event, because a regulator expects to see that STP fired
 * rather than infer it from an order filling less than it should have. Recording it here,
 * next to {@link Fill}, means a caller can distinguish "this quantity found no genuine
 * counterparty" from "this quantity was blocked from trading against its own owner" - two very
 * different explanations for the same unfilled remainder.
 *
 * <h2>Why matching continues past it rather than cancelling the whole order</h2>
 * Only the specific pairing is prevented. An aggressor whose remaining quantity would cross a
 * same-owner resting order at one price level can still fill against every other participant,
 * at this level and beyond - most of an order is typically not blocked by one self-owned
 * resting order sitting in its path, and cancelling the whole thing would be needlessly
 * punitive. See {@code OrderBook.match} for where this is recorded.
 *
 * <p>Immutable and thread-safe.
 */
public record SelfTradePrevention(
        InstrumentId instrumentId,
        OrderId restingOrderId,
        OrderId aggressingOrderId,
        long quantity) {

    public SelfTradePrevention {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(restingOrderId, "restingOrderId");
        Objects.requireNonNull(aggressingOrderId, "aggressingOrderId");

        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Self-trade-prevented quantity must be positive, but was " + quantity);
        }
    }

    @Override
    public String toString() {
        return "STP %s: %s blocked against own order %s, quantity %d".formatted(
                instrumentId, aggressingOrderId, restingOrderId, quantity);
    }
}
