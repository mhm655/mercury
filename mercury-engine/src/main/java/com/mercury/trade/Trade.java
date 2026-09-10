package com.mercury.trade;

import com.mercury.core.MercuryException;
import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.TradeId;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A trade, from the moment it exists through to settlement - the entity
 * {@code docs/DESIGN_PROPOSAL.md} section 3.4 calls "an entity with an append-only
 * lifecycle history."
 *
 * <h2>Only ever created from an actual execution</h2>
 * A {@code Trade} exists because something traded. An order that merely rests, or is
 * cancelled unfilled, is not a trade at any quantity - that stays represented by
 * {@code OrderStatus}/{@code MatchResult} on the matching side, where it already belongs.
 * Manufacturing a {@code Trade} for an order with nothing behind it would put a phantom
 * entry with no execution in the audit trail this type exists to keep honest.
 *
 * <h2>Immutable, like {@code PortfolioLedger} and {@code Position}</h2>
 * Every operation returns a new {@code Trade}. {@link #transitionTo} is the only way the
 * status changes, and it can only ever append to {@code history}, never rewrite it -
 * exactly what an audit trail requires.
 *
 * <h2>{@code delta} and {@code consideration}: the bridge to the ledger</h2>
 * These carry the same shape {@code PortfolioLedger.trade(instrumentId, delta,
 * consideration, tradeDate)} already consumes, signed the same way: positive consideration
 * is money paid out, selling is a negative delta and therefore a negative consideration.
 * {@code PortfolioLedger.book(Trade)} is the thin bridge that turns one of these into a
 * ledger entry once it has actually executed.
 *
 * <h2>{@code owner} versus {@code counterparty}</h2>
 * {@code owner} is whose book this trade affects - always present, because every trade
 * belongs to somebody's ledger. {@code counterparty} is the other side, when it is known:
 * present for an OTC trade, where the design is bilateral and the counterparty is named by
 * construction; empty for a CLOB trade, where the exchange is anonymous by design - see
 * {@code TradabilityProfile}. A single fill on an order book produces two {@code Trade}s,
 * one per side, each with the matching participant as {@code owner} and an empty
 * {@code counterparty} - exactly what a real member firm sees, since anonymity means each
 * side only ever receives its own execution report.
 *
 * <h2>{@code settlementDate}</h2>
 * Carried now even though nothing schedules a trade to {@code SETTLED} automatically yet -
 * the same "cheap to add now, invasive to retrofit" reasoning
 * {@code docs/DESIGN_PROPOSAL.md} section A2.8 gives for {@link Counterparty}. Driving a
 * trade to {@code SETTLED} is a caller's explicit action until a real settlement scheduler
 * exists.
 *
 * <p>Immutable and thread-safe.
 */
public record Trade(
        TradeId id,
        InstrumentId instrumentId,
        CounterpartyId owner,
        Quantity delta,
        Money consideration,
        LocalDate tradeDate,
        Optional<LocalDate> settlementDate,
        Optional<CounterpartyId> counterparty,
        TradeStatus status,
        List<TradeLifecycleEvent> history) {

    public Trade {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(consideration, "consideration");
        Objects.requireNonNull(tradeDate, "tradeDate");
        Objects.requireNonNull(settlementDate, "settlementDate");
        Objects.requireNonNull(counterparty, "counterparty");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(history, "history");
        if (delta.isZero()) {
            throw new IllegalArgumentException("A trade must change a position by a non-zero "
                    + "amount; a zero delta is not an execution");
        }
        history = List.copyOf(history);
    }

    /** A freshly created trade, in {@link TradeStatus#NEW} with no history yet. */
    public static Trade newTrade(TradeId id, InstrumentId instrumentId, CounterpartyId owner,
                                 Quantity delta, Money consideration, LocalDate tradeDate,
                                 Optional<LocalDate> settlementDate,
                                 Optional<CounterpartyId> counterparty) {
        return new Trade(id, instrumentId, owner, delta, consideration, tradeDate, settlementDate,
                counterparty, TradeStatus.NEW, List.of());
    }

    /**
     * Moves this trade to {@code target}, appending one {@link TradeLifecycleEvent}.
     *
     * @throws InvalidTradeTransitionException if {@code target} is not a legal successor of
     *                                          the current status
     */
    public Trade transitionTo(TradeStatus target, String reason, SimulationClock clock) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(clock, "clock");
        if (!status.canTransitionTo(target)) {
            throw new InvalidTradeTransitionException(this, target);
        }
        List<TradeLifecycleEvent> updated = new ArrayList<>(history);
        updated.add(new TradeLifecycleEvent(status, target, clock.now(), reason));
        return new Trade(id, instrumentId, owner, delta, consideration, tradeDate, settlementDate,
                counterparty, target, updated);
    }

    @Override
    public String toString() {
        return "%s %s %s %s [%s]".formatted(id, status, instrumentId, delta, consideration);
    }

    /** Raised by {@link #transitionTo} when the requested move is not a legal one. */
    public static final class InvalidTradeTransitionException extends MercuryException {

        InvalidTradeTransitionException(Trade trade, TradeStatus target) {
            super("Trade " + trade.id() + " cannot move from " + trade.status() + " to " + target
                    + ". " + (trade.status().isTerminal()
                            ? trade.status() + " is terminal; nothing may follow it."
                            : "Legal transitions from " + trade.status() + ": "
                                    + trade.status().allowedTransitions()));
        }
    }
}
