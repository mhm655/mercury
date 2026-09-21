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

    /**
     * Runs {@code work} on the calling thread, under the same lock {@link #run} uses. There is
     * no writer thread here to hand work to, so this is not fire-and-forget the way
     * {@link SingleWriterBookLane#submit} is - {@code work} has already run by the time this
     * returns. Kept symmetric with {@link BookLane} rather than throwing, since a caller that
     * chose {@code submit} for its semantics (never blocking on a result) still gets that on
     * {@link BookConcurrency#INLINE}, just not the latency win {@code THREAD_PER_BOOK} exists
     * for.
     */
    @Override
    public void submit(Runnable work) {
        synchronized (lock) {
            work.run();
        }
    }

    @Override
    public void close() {
        // Nothing owned: the callers brought their own threads.
    }
}
