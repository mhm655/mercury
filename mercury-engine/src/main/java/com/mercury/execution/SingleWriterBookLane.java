package com.mercury.execution;

import com.mercury.core.id.InstrumentId;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * A {@link BookLane} that owns one thread, and feeds it commands through a queue: the
 * single-writer matching engine of {@code docs/DESIGN_PROPOSAL.md} section 5.6 a).
 *
 * <h2>Serialise the commands, do not lock the structure</h2>
 * The book is touched by exactly one thread for its whole life, so the {@code OrderBook}'s
 * {@code TreeMap}s, its id index and its cached top of book are never shared - not "shared
 * carefully", not shared at all. That is the LMAX insight this project cites: a queue in front
 * of a single writer beats a lock around the data, because the data structure never has to be
 * defensive, and because the queue is a command log - which is what makes deterministic replay
 * possible at all.
 *
 * <h2>The caller still waits</h2>
 * {@link #run} submits the command and blocks for its result, because
 * {@code OrderBookVenue.execute} returns the trades it produced and callers depend on that.
 * The gain here is therefore not "the caller is freed"; it is that <b>different instruments
 * genuinely run at once</b> while each book stays uncontended, and that a book has one owner
 * rather than whichever thread got the lock. Freeing the caller is a different feature -
 * fire-and-forget submission with executions arriving as events - and it belongs with the
 * event bus, not here.
 *
 * <p>Work is never stolen or reordered: commands run in the order the queue received them.
 * Two callers racing to submit to the same book may enqueue in either order, which is exactly
 * the ambiguity that already exists when two orders arrive at a real venue at once - what is
 * guaranteed is that one of them is fully processed before the other starts.
 *
 * <p>The thread is a daemon, so a caller that forgets to {@link #close()} cannot keep a JVM
 * alive; closing is still how a lane is meant to end.
 */
final class SingleWriterBookLane implements BookLane {

    /** Ends the command loop; queued behind whatever was already submitted. */
    private static final Runnable CLOSE = () -> {
    };

    private final BlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();
    private final Thread writer;
    private volatile boolean closed;

    SingleWriterBookLane(InstrumentId instrumentId) {
        this.writer = new Thread(this::runUntilClosed, "mercury-book-" + instrumentId.value());
        this.writer.setDaemon(true);
        this.writer.start();
    }

    @Override
    public <T> T run(Supplier<T> work) {
        if (closed) {
            throw new RejectedExecutionException(
                    "This book's writer thread is closed; the command would never run");
        }
        FutureTask<T> command = new FutureTask<>(work::get);
        commands.add(command);
        try {
            return command.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the book's writer", e);
        } catch (ExecutionException e) {
            // The book rejected the command - an unknown instrument, a non-tradable one, a
            // duplicate order id. The caller must see the exception the book actually threw,
            // not a wrapper that says a thread failed: single-writer is a threading choice and
            // must not change what an error looks like.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("A book command failed", cause);
        }
    }

    /**
     * Stops accepting commands, lets the ones already queued finish, and ends the thread.
     * Idempotent.
     *
     * <p>Not an interrupt: interrupting mid-command could leave a book half-matched, with
     * resting orders filled and no trades returned to anyone. Anything queued behind the
     * closing marker is cancelled rather than abandoned, so a caller still waiting is told
     * instead of blocking forever.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        commands.add(CLOSE);
    }

    private void runUntilClosed() {
        while (true) {
            Runnable command;
            try {
                command = commands.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelQueued();
                return;
            }
            if (command == CLOSE) {
                cancelQueued();
                return;
            }
            command.run();
        }
    }

    private void cancelQueued() {
        for (Runnable queued = commands.poll(); queued != null; queued = commands.poll()) {
            if (queued instanceof FutureTask<?> command) {
                command.cancel(false);
            }
        }
    }
}
