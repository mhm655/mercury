package com.mercury.execution;

import java.util.function.Supplier;

/**
 * A {@link BookLane} that runs work on the calling thread, under this lane's own lock.
 *
 * <h2>The default, and why</h2>
 * The result comes back from the thread that asked for it, so a single-threaded caller - every
 * test, every demo, the golden master - gets exactly the behaviour it had before lanes
 * existed, with no threads created and nothing to shut down.
 *
 * <p>Under concurrent callers it still gives each book to one caller at a time, which is what
 * M12's fix needed: unserialised, two threads matching at once over-filled a resting order.
 * The lock is per instrument rather than per venue, so a busy book no longer blocks the rest -
 * the refinement the previous venue-wide {@code synchronized} explicitly deferred until there
 * was a reason for it.
 *
 * <p>What it does not give is the single-writer property itself: the thread that owns the book
 * changes from call to call, so there is no command log and no replay. See
 * {@link SingleWriterBookLane}.
 */
final class LockedBookLane implements BookLane {

    private final Object lock = new Object();

    @Override
    public <T> T run(Supplier<T> work) {
        synchronized (lock) {
            return work.get();
        }
    }

    @Override
    public void close() {
        // Nothing owned: the callers brought their own threads.
    }
}
