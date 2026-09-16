package com.mercury.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * An {@link EventBus} that delivers on the publishing thread, before {@code publish} returns.
 *
 * <h2>Why this is the default</h2>
 * Everything a caller already knows about ordinary method calls stays true: when
 * {@code publish} returns the work is done, a stack trace runs from the subscriber back to the
 * publisher, and a test needs no waiting, no timeout and no flush. The cost is that a slow
 * subscriber slows the publisher down, and a failing one fails it - see below.
 *
 * <h2>A subscriber that throws, fails the publisher</h2>
 * Deliberate, and the sharpest difference from {@link AsynchronousEventBus}. If booking a
 * trade to the ledger throws, the venue's caller finds out, rather than the execution being
 * reported as successful while the position quietly never arrived. Remaining subscribers do
 * not run - an ordinary method call does not continue past an exception either, and pretending
 * otherwise would mean deciding, here, that some half-delivered state is acceptable.
 *
 * <p>Thread-safe: subscribing while another thread publishes is safe, and a subscription made
 * during a dispatch is seen by the next dispatch, not the one in progress.
 */
public final class SynchronousEventBus implements EventBus {

    private final Map<Class<?>, List<Consumer<?>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public <E> void subscribe(Class<E> eventType, Consumer<? super E> subscriber) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.computeIfAbsent(eventType, type -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    @Override
    public void publish(Object event) {
        Objects.requireNonNull(event, "event");
        Dispatch.to(subscribers, event, null);
    }
}
