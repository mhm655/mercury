package com.mercury.event;

import java.util.function.Consumer;

/**
 * Publish-subscribe dispatch, so components can react to what other components did without
 * either side knowing the other exists.
 *
 * <h2>The problem it solves</h2>
 * {@code docs/DESIGN_PROPOSAL.md} section 6 lists Observer against this interface for a
 * specific reason: "pricing, portfolio, risk and alerting must react to market events without
 * knowing about each other." A venue that booked trades into a ledger itself would have to
 * know what a ledger is, and the second thing that wants to hear about executions (a risk
 * recalculation, an alert, an audit feed) would have to be wired into the venue too. Here the
 * venue states a fact and is done.
 *
 * <h2>Two implementations, and which is the default</h2>
 * {@link SynchronousEventBus} dispatches on the publishing thread, in subscription order,
 * before {@code publish} returns. {@link AsynchronousEventBus} hands the event to a dispatcher
 * thread and returns immediately, which buys throughput and failure isolation and costs the
 * guarantee that the work is done when {@code publish} returns.
 *
 * <p><b>Synchronous is the default, deliberately.</b> Making an async bus the default is a
 * classic mistake: every test becomes a race, and every "why didn't the ledger update"
 * question becomes a scheduling question. Section 5.6 c) says so, and this codebase follows
 * it - every test and every demo runs on the synchronous bus, and asynchrony is something a
 * caller opts into for a live simulation where the publisher must not wait.
 *
 * <p>Implementations must be safe to publish to and subscribe on from several threads.
 */
public interface EventBus {

    /**
     * Registers {@code subscriber} for every event of {@code eventType}, including subtypes.
     *
     * <p>Subscribers registered for the same {@code eventType} are called in the order they
     * subscribed. Across different types that both match one event (a class and a supertype of
     * it) the order is unspecified - a subscriber that must run after another has to subscribe
     * to the same type, not rely on the shape of the hierarchy. A duplicate subscription is a
     * second subscription, called twice: the bus cannot tell an accidental double-registration
     * from a deliberate one.
     */
    <E> void subscribe(Class<E> eventType, Consumer<? super E> subscriber);

    /**
     * Delivers {@code event} to every subscriber registered for its type or a supertype of it.
     *
     * <p>An event with no subscribers is not an error. Nothing is listening yet is the normal
     * state of a bus, and a publisher must not have to care.
     */
    void publish(Object event);

    /**
     * A bus that delivers nothing - what a component uses when it has no listeners at all, so
     * that publishing is unconditional rather than guarded by a null check. See
     * {@code IgnoringEventBus}.
     */
    static EventBus ignoring() {
        return IgnoringEventBus.INSTANCE;
    }
}
