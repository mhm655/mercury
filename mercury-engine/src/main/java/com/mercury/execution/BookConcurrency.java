package com.mercury.execution;

import com.mercury.core.id.InstrumentId;

/**
 * How an {@link OrderBookVenue} gives out access to its books. Both choices produce the same
 * trades from the same instructions; they differ in who does the matching.
 *
 * <p>Named as a choice rather than a boolean flag, and stated at construction rather than
 * per call, because it is a property of a venue for its whole life - a venue that ran some
 * instruments on their own threads and others inline would have two answers to "which thread
 * owns this book", which is the one question this setting exists to have a single answer to.
 */
public enum BookConcurrency {

    /**
     * Matching happens on the calling thread, under a lock held per instrument. The default:
     * no threads to create or close, deterministic for a single-threaded caller, and still
     * safe when several threads call at once.
     */
    INLINE {
        @Override
        BookLane laneFor(InstrumentId instrumentId) {
            return new LockedBookLane();
        }
    },

    /**
     * Each book is owned by its own thread and fed from a command queue - the single-writer
     * matching engine of {@code docs/DESIGN_PROPOSAL.md} section 5.6 a). Callers still get
     * their trades back, so the win is that instruments run genuinely in parallel while no
     * book is ever contended, and that a book has one owner rather than whichever thread took
     * the lock.
     *
     * <p>A venue using this owns threads: close it when finished
     * ({@link OrderBookVenue#close()}). Its threads are daemons, so forgetting cannot hang a
     * JVM - it only leaks them for the process's life.
     */
    THREAD_PER_BOOK {
        @Override
        BookLane laneFor(InstrumentId instrumentId) {
            return new SingleWriterBookLane(instrumentId);
        }
    };

    /** The lane guarding one instrument's book under this policy. */
    abstract BookLane laneFor(InstrumentId instrumentId);
}
