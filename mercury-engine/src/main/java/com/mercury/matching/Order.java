package com.mercury.matching;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.OrderId;
import com.mercury.core.money.Price;
import java.util.Objects;
import java.util.Optional;

/**
 * An instruction to buy or sell, exactly as submitted.
 *
 * <h2>Immutable on purpose</h2>
 * An order's <em>remaining</em> quantity changes as it fills, but this type does not model
 * that. What was submitted is a historical fact - it belongs in the audit trail unchanged,
 * and mutating it would erase the record of what the trader actually asked for. The
 * mutable execution state lives in the book, on {@code OrderNode}.
 *
 * <p>The separation also means an order can be safely handed to an event listener, logged,
 * or replayed without any risk that the book mutates it underneath.
 *
 * <h2>Quantity is a long</h2>
 * Book quantities are whole units. Only exchange-traded instruments reach a matching
 * engine, and those trade in whole shares or contracts; the fractional notionals that need
 * {@link com.mercury.core.money.Quantity} belong to OTC instruments, which are negotiated
 * bilaterally and never touch the book. Using a primitive here is therefore a domain
 * decision first and a performance one second - though it does also keep the matching loop
 * free of allocation.
 *
 * <p>Immutable and thread-safe.
 */
public record Order(
        OrderId id,
        InstrumentId instrumentId,
        Side side,
        OrderType type,
        Optional<Price> limitPrice,
        long quantity,
        TimeInForce timeInForce) {

    public Order {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(limitPrice, "limitPrice");
        Objects.requireNonNull(timeInForce, "timeInForce");

        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Order quantity must be positive, but was " + quantity
                            + ". Direction is carried by Side, not by the sign of the quantity.");
        }
        if (type == OrderType.LIMIT && limitPrice.isEmpty()) {
            throw new IllegalArgumentException("A limit order must state a limit price");
        }
        if (type == OrderType.MARKET) {
            if (limitPrice.isPresent()) {
                throw new IllegalArgumentException(
                        "A market order must not state a limit price; it executes at whatever "
                                + "price the book offers");
            }
            if (timeInForce != TimeInForce.IMMEDIATE_OR_CANCEL) {
                throw new IllegalArgumentException(
                        "A market order is necessarily immediate-or-cancel: having no price, it "
                                + "has no position in a price-ordered book and cannot rest");
            }
        }
    }

    /** A limit order that rests until filled or cancelled. */
    public static Order limit(OrderId id, InstrumentId instrumentId, Side side,
                              Price limitPrice, long quantity) {
        return new Order(id, instrumentId, side, OrderType.LIMIT, Optional.of(limitPrice),
                quantity, TimeInForce.GOOD_TILL_CANCEL);
    }

    /** A limit order that takes what it can immediately and cancels the rest. */
    public static Order immediateOrCancel(OrderId id, InstrumentId instrumentId, Side side,
                                          Price limitPrice, long quantity) {
        return new Order(id, instrumentId, side, OrderType.LIMIT, Optional.of(limitPrice),
                quantity, TimeInForce.IMMEDIATE_OR_CANCEL);
    }

    /** A market order: takes whatever liquidity exists, cancels any remainder. */
    public static Order market(OrderId id, InstrumentId instrumentId, Side side, long quantity) {
        return new Order(id, instrumentId, side, OrderType.MARKET, Optional.empty(),
                quantity, TimeInForce.IMMEDIATE_OR_CANCEL);
    }

    public boolean isMarketOrder() {
        return type == OrderType.MARKET;
    }

    public boolean isBuy() {
        return side.isBuy();
    }

    /**
     * Whether this order is willing to trade at {@code candidate}.
     *
     * <p>A market order accepts any price. A limit buy accepts anything at or below its
     * limit; a limit sell anything at or above. The asymmetry is why this lives here rather
     * than being written out at each call site in the matching loop.
     */
    public boolean acceptsPrice(Price candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (isMarketOrder()) {
            return true;
        }
        Price limit = limitPrice.orElseThrow();
        return side.isBuy()
                ? candidate.compareTo(limit) <= 0
                : candidate.compareTo(limit) >= 0;
    }

    @Override
    public String toString() {
        String priceText = limitPrice.map(Price::toString).orElse("MKT");
        return "%s %s %d @ %s [%s]".formatted(
                side.displayName(), instrumentId, quantity, priceText, id);
    }
}
