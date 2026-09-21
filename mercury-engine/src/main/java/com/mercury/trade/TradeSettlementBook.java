package com.mercury.trade;

import com.mercury.core.id.TradeId;
import com.mercury.core.time.SimulationClock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Drives trades to {@link TradeStatus#SETTLED} automatically once the clock reaches their
 * {@link Trade#settlementDate()} - the scheduler {@code docs/DESIGN_PROPOSAL.md} section A2.7
 * decided on ("settlement is triggered by clock advancement, not by a real timer") and
 * {@code docs/KNOWN_GAPS.md}'s "Settlement scheduling" entry recorded as never actually built:
 * through M18, driving a trade to {@code SETTLED} was a caller's explicit action, by hand,
 * every time.
 *
 * <h2>Subscribed the same way {@code LedgerKeeper} is, pull-based the same way the rest of this
 * codebase's date logic already is</h2>
 * This is a {@link Consumer} of {@link TradeExecuted}, holding the one mutable cell a scheduler
 * needs - an immutable {@link Trade} cannot subscribe to anything, the same reason
 * {@code LedgerKeeper} exists rather than {@code PortfolioLedger} listening directly. But
 * nothing here pushes on a timer: {@link #settleDueBy} is a step a caller invokes explicitly
 * after advancing a {@link SimulationClock}, the same pull-based shape
 * {@code Schedule.unpaidPeriodsAsOf} already uses for date-driven logic elsewhere in this
 * engine, rather than a listener wired into the clock itself - there is no existing precedent
 * for the clock pushing anything, and inventing one for this alone would be new architecture
 * this milestone does not need.
 *
 * <h2>No new event type</h2>
 * {@link #settleDueBy} returns the trades it just settled directly to its caller, who decides
 * what to do next - releasing an OTC trade's exposure, for instance (see
 * {@code com.mercury.execution.ExposureLedger#release}). The same explicit-composition style
 * {@code TuiDemo} already uses for a rejection it holds directly rather than inventing an event
 * for; a settlement announcement nothing yet consumes would be exactly the speculative
 * machinery {@code docs/DESIGN_PROPOSAL.md} section A2.9 argues against.
 *
 * <p>Thread-safe: every method is synchronized, the same discipline {@code LedgerKeeper} uses
 * so this also works behind an asynchronous event bus.
 */
public final class TradeSettlementBook implements Consumer<TradeExecuted> {

    /**
     * Trades recorded as {@link #accept}ed and not yet settled by this book, in the order they
     * were recorded - a {@link LinkedHashMap} rather than a plain hash map so
     * {@link #settleDueBy} processes them in a deterministic order, matching this codebase's
     * reproducibility discipline everywhere else a collection is iterated and observed.
     *
     * <p>Nothing here observes a trade cancelled elsewhere: {@link TradeExecuted} is published
     * once, at first execution, never again on a later transition - so a trade this book never
     * settles because it was cancelled through some other path stays in this map. No event this
     * codebase publishes tells this class that happened; inventing one for a case no caller
     * exercises today would be exactly the speculative machinery {@code docs/DESIGN_PROPOSAL.md}
     * section A2.9 argues against.
     */
    private final Map<TradeId, Trade> open = new LinkedHashMap<>();

    /**
     * Records {@code event}'s trade as open, unless a trade with that id is already recorded -
     * {@link com.mercury.core.MercuryException} aside, a repeat delivery on this codebase's
     * event bus is not this class's problem to detect twice, the same reasoning
     * {@code LedgerKeeper}'s own javadoc gives for leaving duplicate-id detection to
     * {@code PortfolioLedger.book}.
     */
    @Override
    public synchronized void accept(TradeExecuted event) {
        Objects.requireNonNull(event, "event");
        Trade trade = event.trade();
        open.putIfAbsent(trade.id(), trade);
    }

    /**
     * Walks every open trade whose {@link Trade#settlementDate()} is on or before {@code asOf}
     * through {@link TradeStatus#CONFIRMED} then {@link TradeStatus#SETTLED}, in the order they
     * were recorded, and returns the ones just settled. A trade with no settlement date, or one
     * still in the future, is left open. Idempotent: a trade this method already settled is no
     * longer in {@link #open}, so calling it again with the same or a later date never re-settles
     * anything.
     */
    public synchronized List<Trade> settleDueBy(LocalDate asOf, SimulationClock clock) {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(clock, "clock");

        List<Trade> settled = new ArrayList<>();
        for (Trade trade : List.copyOf(open.values())) {
            if (trade.settlementDate().isPresent() && !trade.settlementDate().get().isAfter(asOf)) {
                Trade confirmed = trade.transitionTo(TradeStatus.CONFIRMED, "confirmation sent", clock);
                Trade justSettled = confirmed.transitionTo(
                        TradeStatus.SETTLED, "cash and securities exchanged", clock);
                open.remove(trade.id());
                settled.add(justSettled);
            }
        }
        return List.copyOf(settled);
    }

    /** Every trade still open, in the order they were recorded. */
    public synchronized List<Trade> openTrades() {
        return List.copyOf(open.values());
    }
}
