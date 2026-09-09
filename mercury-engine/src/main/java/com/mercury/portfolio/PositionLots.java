package com.mercury.portfolio;

import com.mercury.core.MercuryException;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Every open acquisition of one instrument, and what closing them realises.
 *
 * <h2>One sign at a time</h2>
 * All the lots held here share a sign: the position is long or it is short, never a mixture.
 * That is not a simplification, it is what a position <em>is</em> - holding a long lot and a
 * short lot of the same instrument at once is two bookkeeping entries for a net position that
 * is smaller than either.
 *
 * <p>The consequence is a rule worth stating: {@link #apply} refuses a trade that would carry
 * the position through zero. Selling fifteen when long ten is really two trades - closing ten
 * and opening a short five - realising on the first and not the second, and at a different
 * cost basis. Splitting it here would mean inventing the boundary; refusing it makes the
 * caller book what actually happened.
 *
 * <h2>Immutable, because a ledger is a history</h2>
 * Applying a trade returns a new {@code PositionLots} rather than mutating this one. Every
 * intermediate state stays valid and shareable, which is what makes an audit trail or a replay
 * possible later, and it means a trade that turns out to be invalid leaves nothing half-done.
 */
public final class PositionLots {

    private final List<Lot> lots;

    private PositionLots(List<Lot> lots) {
        this.lots = lots;
    }

    /** No position at all. */
    public static PositionLots empty() {
        return new PositionLots(List.of());
    }

    /** The lots, oldest first. */
    public List<Lot> lots() {
        return Collections.unmodifiableList(lots);
    }

    /** The net holding: positive when long, negative when short, zero when flat. */
    public Quantity quantity() {
        return lots.stream().map(Lot::quantity).reduce(Quantity.ZERO, Quantity::plus);
    }

    /**
     * What the open position cost, in the currency it was transacted in.
     *
     * @throws IllegalStateException if the position is flat, which has no currency to report in
     */
    public Money costBasis() {
        if (lots.isEmpty()) {
            throw new IllegalStateException(
                    "A flat position has no cost basis. Ask whether it is empty first - a zero "
                            + "here would have to invent a currency to be zero in.");
        }
        Money zero = Money.zero(lots.get(0).cost().currency());
        return lots.stream().map(Lot::cost).reduce(zero, Money::plus);
    }

    public boolean isEmpty() {
        return lots.isEmpty();
    }

    /**
     * Books a trade against this position.
     *
     * @param delta  the signed change in holding: positive buys, negative sells
     * @param cash   the signed cash the trade moves: negative when buying
     * @param date   the trade date, which becomes the lot's acquisition date if it opens one
     * @param method how a disposal chooses which lots it closes
     * @return the position afterwards and what the trade realised
     *
     * @throws PositionCrossesZeroException if the trade would flip the position's direction
     */
    public Applied apply(Quantity delta, Money cash, LocalDate date, CostBasisMethod method,
                         InstrumentId instrumentId) {
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(cash, "cash");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(instrumentId, "instrumentId");
        if (delta.isZero()) {
            throw new IllegalArgumentException(
                    "A trade of zero " + instrumentId + " moves nothing and closes nothing.");
        }

        Quantity held = quantity();
        boolean opening = held.isZero() || held.signum() == delta.signum();
        if (opening) {
            List<Lot> extended = new ArrayList<>(lots);
            extended.add(new Lot(delta, cash.negated(), date));
            return new Applied(new PositionLots(List.copyOf(extended)),
                    Money.zero(cash.currency()));
        }

        // Closing. The trade reduces the holding, so its magnitude must not exceed it.
        if (delta.value().abs().compareTo(held.value().abs()) > 0) {
            throw new PositionCrossesZeroException(instrumentId, held, delta);
        }

        // consume() wants the amount in the lots' own sign, and delta is the opposite of it.
        CostBasisMethod.Consumed consumed = method.consume(lots, negate(delta));

        // realised = cash in - cost basis out. One formula for both directions: closing a long
        // brings cash in against a positive cost, closing a short pays cash out against a
        // negative one, and both come to the profit.
        Money realised = cash.minus(consumed.cost());
        return new Applied(new PositionLots(List.copyOf(consumed.remaining())), realised);
    }

    private static Quantity negate(Quantity quantity) {
        return Quantity.of(quantity.value().negate());
    }

    @Override
    public String toString() {
        return lots.isEmpty() ? "flat" : quantity() + " at " + costBasis();
    }

    /** A position after a trade, and what that trade realised. */
    public record Applied(PositionLots position, Money realised) {

        public Applied {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(realised, "realised");
        }
    }

    /** Raised when one trade would both close a position and open the opposite one. */
    public static final class PositionCrossesZeroException extends MercuryException {
        PositionCrossesZeroException(InstrumentId instrumentId, Quantity held, Quantity delta) {
            super("A trade of " + delta + " " + instrumentId + " against a holding of " + held
                    + " would carry the position through zero. That is two trades, not one: it "
                    + "closes the existing holding at its cost basis and opens a new one at "
                    + "today's price, realising on the first and not the second. Splitting it "
                    + "here would mean inventing where the boundary falls, so book the two "
                    + "legs separately.");
        }
    }
}
