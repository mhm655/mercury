package com.mercury.portfolio;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Quantity;
import java.util.Objects;

/**
 * A holding: how much of one instrument a portfolio owns.
 *
 * <h2>Immutable</h2>
 * Applying a trade produces a <em>new</em> position rather than changing this one. That is
 * what makes the pro-forma projection behind pre-trade risk checks cheap - asking "what would
 * the portfolio look like if this traded?" is building a new position, not mutating and
 * rolling back - and it lets risk workers read positions concurrently with no locking.
 *
 * <h2>Signed, not directional</h2>
 * A negative quantity is a short position. Direction lives in the sign so that applying a
 * trade is addition, with no branch on which way the position currently faces.
 *
 * <h2>What is deliberately missing</h2>
 * No cost basis, no realised P&amp;L. Those need a {@code CostBasisMethod} and a trade history,
 * and they arrive at M7. A position at M4 answers only "how much", which is all the market
 * value in this milestone requires. Adding the fields now, unused and untested, would be
 * speculative.
 */
public record Position(InstrumentId instrumentId, Quantity quantity) {

    public Position {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(quantity, "quantity");
    }

    public static Position of(InstrumentId instrumentId, Quantity quantity) {
        return new Position(instrumentId, quantity);
    }

    public static Position of(InstrumentId instrumentId, long quantity) {
        return new Position(instrumentId, Quantity.of(quantity));
    }

    /** The position that results from adding {@code additional} units. */
    public Position plus(Quantity additional) {
        return new Position(instrumentId, quantity.plus(additional));
    }

    public boolean isLong() {
        return quantity.isLong();
    }

    public boolean isShort() {
        return quantity.isShort();
    }

    public boolean isFlat() {
        return quantity.isZero();
    }

    @Override
    public String toString() {
        return quantity + " " + instrumentId;
    }
}
