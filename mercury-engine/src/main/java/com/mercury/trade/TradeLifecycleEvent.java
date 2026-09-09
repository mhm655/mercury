package com.mercury.trade;

import java.time.Instant;
import java.util.Objects;

/**
 * One step in a trade's audit trail: what it moved from, what it moved to, when, and why.
 *
 * <p>Real capital-markets systems keep an append-only history of every trade's lifecycle -
 * who touched it, when, and for what reason - because being able to reconstruct why a
 * number was what it was on a given day is a regulatory expectation, not a convenience.
 * {@link Trade#transitionTo} appends exactly one of these per transition; nothing ever
 * rewrites or removes one.
 *
 * <p>{@code at} always comes from an injected {@code SimulationClock}, never a real clock -
 * see {@code SimulationClock} for why, and {@code LayeringRulesTest} for how that is
 * enforced.
 *
 * <p>Immutable and thread-safe.
 */
public record TradeLifecycleEvent(TradeStatus from, TradeStatus to, Instant at, String reason) {

    public TradeLifecycleEvent {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("A lifecycle event must state a reason");
        }
    }

    @Override
    public String toString() {
        return "%s -> %s at %s: %s".formatted(from, to, at, reason);
    }
}
