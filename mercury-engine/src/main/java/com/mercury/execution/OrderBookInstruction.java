package com.mercury.execution;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Price;
import com.mercury.matching.OrderType;
import com.mercury.matching.Side;
import com.mercury.matching.TimeInForce;
import java.util.Objects;
import java.util.Optional;

/**
 * An instruction to submit an order to {@link OrderBookVenue}.
 *
 * <p>No {@code OrderId} here - the venue mints one from its own {@link OrderIdGenerator},
 * which is how G-1 (reusable order ids) is closed: nothing outside the venue supplies an
 * id, so nothing outside it can supply a stale one. {@code participant} identifies who is
 * submitting, and becomes the resulting {@code Order}'s owner - the identity self-trade
 * prevention compares.
 *
 * <p>Mirrors {@code Order}'s own factories ({@code limit}, {@code market},
 * {@code immediateOrCancel}) one-for-one, since the venue turns this directly into an
 * {@code Order}.
 */
public record OrderBookInstruction(
        InstrumentId instrumentId,
        Side side,
        OrderType type,
        Optional<Price> limitPrice,
        long quantity,
        TimeInForce timeInForce,
        CounterpartyId participant) implements ExecutionInstruction {

    public OrderBookInstruction {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(limitPrice, "limitPrice");
        Objects.requireNonNull(timeInForce, "timeInForce");
        Objects.requireNonNull(participant, "participant");
    }

    /** A limit order that rests until filled or cancelled. */
    public static OrderBookInstruction limit(InstrumentId instrumentId, Side side,
                                             Price limitPrice, long quantity,
                                             CounterpartyId participant) {
        return new OrderBookInstruction(instrumentId, side, OrderType.LIMIT,
                Optional.of(limitPrice), quantity, TimeInForce.GOOD_TILL_CANCEL, participant);
    }

    /** A limit order that takes what it can immediately and cancels the rest. */
    public static OrderBookInstruction immediateOrCancel(InstrumentId instrumentId, Side side,
                                                          Price limitPrice, long quantity,
                                                          CounterpartyId participant) {
        return new OrderBookInstruction(instrumentId, side, OrderType.LIMIT,
                Optional.of(limitPrice), quantity, TimeInForce.IMMEDIATE_OR_CANCEL, participant);
    }

    /** A market order: takes whatever liquidity exists, cancels any remainder. */
    public static OrderBookInstruction market(InstrumentId instrumentId, Side side, long quantity,
                                              CounterpartyId participant) {
        return new OrderBookInstruction(instrumentId, side, OrderType.MARKET, Optional.empty(),
                quantity, TimeInForce.IMMEDIATE_OR_CANCEL, participant);
    }
}
