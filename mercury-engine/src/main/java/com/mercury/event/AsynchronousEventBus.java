package com.mercury.event;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * An {@link EventBus} that queues events and delivers them on one dispatcher thread, so
 * {@code publish} returns without waiting for any subscriber.
 *
 * <h2>What it buys, and what it costs</h2>
 * Buys: a publisher no longer pays for its subscribers. A venue that matches an order in
 * microseconds is not held up by a subscriber that revalues a book in milliseconds, and a
 * subscriber that fails no longer fails the execution that told it something.
 *
 * <p>Costs: when {@code publish} returns, nothing has happened yet. A caller that publishes a
 * trade and immediately reads the ledger may see the state from before. Tests become races
 * unless they wait. And a failing subscriber's exception has nowhere to go - there is no
 * caller left to throw to - so failures must be handed somewhere explicitly, which is what the
 * failure handler below is for. This is the trade {@code docs/DESIGN_PROPOSAL.md} section
 * 5.6 c) describes, and the reason {@link SynchronousEventBus} is what everything defaults to.
 *
 * <h2>One dispatcher thread, not a pool</h2>
 * A pool would deliver two events concurrently, so a subscriber could observe a later event
 * before an earlier one - for a trade blotter or an audit feed, that is a reordered history.
 * One thread keeps publication order intact while still decoupling the publisher. The queue is
 * unbounded: back-pressure would mean blocking the publisher, which is the thing this class
 * exists not to do. A genuinely faster producer than consumer would need a bounded queue and a
 * stated drop-or-block policy; nothing here produces at that rate, and inventing the policy
 * before there is a producer to size it against would be guesswork.
 *
 * <p>Thread-safe. {@link #close()} stops the dispatcher after the events already queued have
 * been delivered, so a caller that wants to see their effects can close and then look.
 */
public final class AsynchronousEventBus implements EventBus, AutoCloseable {

    /** Ends the dispatch loop; queued behind whatever was already published. */
    private static final Object POISON = new Object();

    private final Map<Class<?>, List<Consumer<?>>> subscribers = new ConcurrentHashMap<>();
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final BiConsumer<Object, RuntimeException> failureHandler;
    private final AtomicInteger failureCount = new AtomicInteger();
    private final Thread dispatcher;
    private volatile boolean closed;

    /**
     * A bus whose subscriber failures are counted and otherwise swallowed - see
     * {@link #failureCount()}.
     *
     * <p>Counting is the floor, not a good production answer: a real deployment should pass a
     * handler that logs or alerts. The engine has no logger, and no framework to get one from
     * (see {@code LayeringRulesTest}), so the default here cannot be "log it" - and discarding
     * failures with no trace at all is how a subscriber dies unnoticed.
     */
    public AsynchronousEventBus() {
        this((event, failure) -> {
        });
    }

    /**
     * @param failureHandler called on the dispatcher thread for every subscriber that throws.
     *                       It should not throw; if it does, that failure is counted and
     *                       dropped, because a dispatcher that dies takes every later event
     *                       with it.
     */
    public AsynchronousEventBus(BiConsumer<Object, RuntimeException> failureHandler) {
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.dispatcher = new Thread(this::dispatchUntilClosed, "mercury-event-dispatcher");
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
    }

    @Override
    public <E> void subscribe(Class<E> eventType, Consumer<? super E> subscriber) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.computeIfAbsent(eventType, type -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    /**
     * Queues {@code event} and returns; delivery happens later, on the dispatcher thread.
     *
     * @throws IllegalStateException if this bus is closed - an event accepted after close
     *                               would never be delivered, and silently dropping it is
     *                               worse than saying so
     */
    @Override
    public void publish(Object event) {
        Objects.requireNonNull(event, "event");
        if (closed) {
            throw new IllegalStateException(
                    "This event bus is closed; " + event.getClass().getSimpleName()
                            + " was published after close and would never be delivered");
        }
        queue.add(event);
    }

    /** How many subscriber calls have thrown since this bus was created. */
    public int failureCount() {
        return failureCount.get();
    }

    /**
     * Stops accepting events, waits for those already queued to be delivered, and stops the
     * dispatcher. Idempotent.
     *
     * <p>Draining rather than discarding is the point: it is what lets a caller publish, close,
     * and then read the state its subscribers built, with no sleep and no polling anywhere.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        queue.add(POISON);
        try {
            dispatcher.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while draining the event bus", e);
        }
    }

    private void dispatchUntilClosed() {
        while (true) {
            Object event;
            try {
                event = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (event == POISON) {
                return;
            }
            Dispatch.to(subscribers, event, this::recordFailure);
        }
    }

    private void recordFailure(Object event, RuntimeException failure) {
        failureCount.incrementAndGet();
        try {
            failureHandler.accept(event, failure);
        } catch (RuntimeException handlerFailed) {
            // Nothing left to tell. Letting this escape would kill the dispatcher thread and
            // silently stop delivery of every later event, which is strictly worse.
            failureCount.incrementAndGet();
        }
    }
}
