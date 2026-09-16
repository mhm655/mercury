package com.mercury.event;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The type matching both buses share: an event goes to subscribers registered for its own
 * class or for any supertype of it, in subscription order.
 *
 * <p>Shared rather than written twice, so the two implementations cannot drift into
 * disagreeing about who receives what - which is the kind of difference that would make the
 * synchronous bus a dishonest stand-in for the asynchronous one in tests.
 */
final class Dispatch {

    private Dispatch() {
    }

    /**
     * @param onFailure what to do with a subscriber that throws; {@code null} lets the
     *                  exception propagate to the caller, which is what the synchronous bus
     *                  wants and the asynchronous one cannot have (there is no caller left to
     *                  throw to by the time the dispatcher runs).
     */
    @SuppressWarnings("unchecked")
    static void to(Map<Class<?>, List<Consumer<?>>> subscribers, Object event,
                   BiConsumer<Object, RuntimeException> onFailure) {
        for (Map.Entry<Class<?>, List<Consumer<?>>> entry : subscribers.entrySet()) {
            if (!entry.getKey().isInstance(event)) {
                continue;
            }
            for (Consumer<?> subscriber : entry.getValue()) {
                try {
                    ((Consumer<Object>) subscriber).accept(event);
                } catch (RuntimeException e) {
                    if (onFailure == null) {
                        throw e;
                    }
                    onFailure.accept(event, e);
                }
            }
        }
    }
}
