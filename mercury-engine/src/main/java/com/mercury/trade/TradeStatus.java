package com.mercury.trade;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Where a trade stands in its lifecycle: {@code NEW -> VALIDATED -> BOOKED -> EXECUTED ->
 * CONFIRMED -> SETTLED}, plus the terminal {@code REJECTED} (a pre-booking refusal) and
 * {@code CANCELLED} (a post-booking withdrawal, permitted any time before settlement).
 *
 * <h2>An explicit transition table, not a State-pattern class hierarchy</h2>
 * {@code docs/DESIGN_PROPOSAL.md} section 6 considers, and rejects, six state classes for
 * this: the progression is mostly linear, and the real requirement is rejecting invalid
 * transitions, which an {@link EnumMap} of legal successors expresses and tests more
 * directly than a class per state would. Where states differ behaviourally - amendment
 * allowed only before {@code BOOKED}, cancellation only before {@code SETTLED} - a guard
 * attaches to the transition rather than living in a state class of its own.
 *
 * <p>{@code SETTLED}, {@code REJECTED} and {@code CANCELLED} are terminal: nothing may
 * follow them, and {@link Trade#transitionTo} enforces that through this table rather than
 * by special-casing terminal states elsewhere.
 */
public enum TradeStatus {

    NEW,
    VALIDATED,
    BOOKED,
    EXECUTED,
    CONFIRMED,
    SETTLED,
    REJECTED,
    CANCELLED;

    private static final Map<TradeStatus, Set<TradeStatus>> ALLOWED = buildTransitionTable();

    private static Map<TradeStatus, Set<TradeStatus>> buildTransitionTable() {
        Map<TradeStatus, Set<TradeStatus>> table = new EnumMap<>(TradeStatus.class);
        table.put(NEW, EnumSet.of(VALIDATED, REJECTED));
        table.put(VALIDATED, EnumSet.of(BOOKED, REJECTED));
        table.put(BOOKED, EnumSet.of(EXECUTED, CANCELLED));
        table.put(EXECUTED, EnumSet.of(CONFIRMED, CANCELLED));
        table.put(CONFIRMED, EnumSet.of(SETTLED, CANCELLED));
        table.put(SETTLED, EnumSet.noneOf(TradeStatus.class));
        table.put(REJECTED, EnumSet.noneOf(TradeStatus.class));
        table.put(CANCELLED, EnumSet.noneOf(TradeStatus.class));
        return table;
    }

    /** Whether moving from this status directly to {@code target} is a legal transition. */
    public boolean canTransitionTo(TradeStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** Every status this one may legally move to directly. Empty for a terminal status. */
    public Set<TradeStatus> allowedTransitions() {
        return Set.copyOf(ALLOWED.get(this));
    }

    /** True for the three statuses nothing may follow: {@code SETTLED}, {@code REJECTED}, {@code CANCELLED}. */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
