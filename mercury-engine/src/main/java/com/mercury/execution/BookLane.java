package com.mercury.execution;

import java.util.function.Supplier;

/**
 * Exclusive access to one instrument's book. Whatever a lane runs, it runs alone: no two
 * callers are ever inside the same instrument's book at once, and different instruments do not
 * wait for each other.
 *
 * <h2>Why an interface rather than one strategy</h2>
 * {@code docs/DESIGN_PROPOSAL.md} section 5.6 a) describes the matching engine as
 * single-writer: each book owned by one thread and fed from a command queue, so commands are
 * serialised rather than the data structure locked. That is {@link SingleWriterBookLane}, and
 * it is what a live venue should run.
 *
 * <p>It is not what a test or a demo wants. Handing work to another thread means the answer
 * comes back from somewhere else, and a golden-master run would depend on a scheduler. So
 * {@link LockedBookLane} - the same exclusivity, held by the calling thread - stays the
 * default, and single-writer is opted into. Both give identical results; see
 * {@link BookConcurrency}.
 *
 * <p>Everything a lane protects (the {@code OrderBook} and its owners) must be reached only
 * through {@link #run}, or the exclusivity is a comment rather than a guarantee.
 */
interface BookLane extends AutoCloseable {

    /** Runs {@code work} with exclusive access to this lane's book, and returns its result. */
    <T> T run(Supplier<T> work);

    /** Releases whatever the lane owns. Idempotent; a lane that owns nothing does nothing. */
    @Override
    void close();
}
