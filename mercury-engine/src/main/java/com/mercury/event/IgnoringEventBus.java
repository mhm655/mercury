package com.mercury.event;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The bus for when nothing is listening: {@link #publish} does nothing, and a subscriber
 * registered here is never called.
 *
 * <p>Exists so a publisher can be written one way. A venue that had to ask whether it has a
 * bus before announcing anything would carry a null check around every publication, and the
 * "no listeners" case would be a different code path from the ordinary one - tested less, and
 * the one most callers actually use. See {@link EventBus#ignoring()}.
 *
 * <p>Deliberately not a lambda: {@link EventBus} has two methods, so it is not a functional
 * interface, and it should not become one - {@code subscribe} is half of what a bus is.
 */
final class IgnoringEventBus implements EventBus {

    static final EventBus INSTANCE = new IgnoringEventBus();

    private IgnoringEventBus() {
    }

    /**
     * Accepts the subscription and drops it. Silent rather than throwing: a caller wiring up a
     * subscriber has asked for something this bus cannot do, but it has done nothing wrong -
     * whoever chose this bus decided nothing would be delivered.
     */
    @Override
    public <E> void subscribe(Class<E> eventType, Consumer<? super E> subscriber) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(subscriber, "subscriber");
    }

    @Override
    public void publish(Object event) {
        Objects.requireNonNull(event, "event");
    }

    @Override
    public String toString() {
        return "EventBus[ignoring]";
    }
}
