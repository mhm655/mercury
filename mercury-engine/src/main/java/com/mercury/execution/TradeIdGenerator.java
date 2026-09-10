package com.mercury.execution;

import com.mercury.core.id.TradeId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mints unique {@link TradeId}s.
 *
 * <h2>One instance, shared across both venues</h2>
 * {@link OrderBookVenue} and {@link OtcNegotiationVenue} both mint {@code Trade}s, and both
 * feed the same {@code PortfolioLedger}. Two independent counters - one per venue - would
 * each start at {@code TRD-1} and collide the moment a CLOB trade and an OTC trade land in
 * the same book: this was caught in review before it shipped, precisely because it is the
 * kind of bug that only shows up once both venues are in use together. Construct exactly
 * one of these and pass it to both venues.
 *
 * <h2>Deterministic</h2>
 * No randomness and no wall-clock read, so replaying the same sequence of instructions
 * mints the same ids in the same order - required for the golden-master test's
 * reproducibility guarantee.
 */
public final class TradeIdGenerator {

    private final String prefix;
    private final AtomicLong counter = new AtomicLong();

    public TradeIdGenerator(String prefix) {
        this.prefix = prefix;
    }

    public TradeId next() {
        return TradeId.of(prefix + counter.incrementAndGet());
    }
}
