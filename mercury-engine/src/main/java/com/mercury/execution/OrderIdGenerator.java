package com.mercury.execution;

import com.mercury.core.id.OrderId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mints unique {@link OrderId}s for {@link OrderBookVenue} - the fix for G-1.
 *
 * <h2>The gap this closes</h2>
 * {@code OrderBook.submit} only rejects an id that is <em>currently resting</em>; once an
 * order fills, its id is free again, so a caller-supplied id is not unique over the life of
 * a book (see {@code docs/KNOWN_GAPS.md}, G-1). The fix documented there is not a growing
 * {@code HashSet} inside {@code OrderBook} - that trades a real bug for an unbounded memory
 * leak in a long-running book - it is that "real venues assign their own unique... order
 * identifiers rather than trusting client-supplied ones." This class is that assignment
 * point: {@link OrderBookVenue} is the only thing that calls it, a caller's
 * {@link OrderBookInstruction} never carries an id at all, and a monotonic counter that
 * only ever increases for the life of the venue cannot repeat a value it has already
 * handed out.
 *
 * <h2>Deterministic</h2>
 * No randomness and no wall-clock read, so replaying the same sequence of instructions
 * against a fresh venue mints the same ids in the same order - required for the
 * golden-master test's reproducibility guarantee.
 *
 * <p>Not thread-safe by locking - {@code AtomicLong} makes concurrent calls safe, but
 * {@code OrderBook} itself is single-writer (see its own class javadoc), so this is never
 * contended in practice.
 */
final class OrderIdGenerator {

    private final String prefix;
    private final AtomicLong counter = new AtomicLong();

    OrderIdGenerator(String prefix) {
        this.prefix = prefix;
    }

    OrderId next() {
        return OrderId.of(prefix + counter.incrementAndGet());
    }
}
