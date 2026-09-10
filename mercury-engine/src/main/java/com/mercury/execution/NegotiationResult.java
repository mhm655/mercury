package com.mercury.execution;

import com.mercury.risk.LimitBreach;
import com.mercury.trade.Trade;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of one {@link OtcNegotiationVenue#negotiate} call: the trade it produced, or the
 * risk-limit breaches that stopped it from producing one.
 *
 * <p>{@link OtcNegotiationVenue#execute} - the shared {@link ExecutionVenue} method - only
 * forwards {@link #trades()}, exactly as {@code OrderBookVenue.execute} only forwards
 * {@code MatchResult.fills()} and drops {@code selfTradePrevented()}. This type is where the
 * fuller picture lives, for a caller that wants it, the same way a caller reaches
 * self-trade-prevention facts by calling {@code OrderBook.submit} directly rather than through
 * the venue.
 *
 * <p>Exactly one of {@link #trades()} or {@link #breaches()} is non-empty: a rejected
 * negotiation produces no trade, and an executed one produces exactly one.
 *
 * <p>Immutable and thread-safe.
 */
public record NegotiationResult(List<Trade> trades, List<LimitBreach> breaches) {

    public NegotiationResult {
        Objects.requireNonNull(trades, "trades");
        Objects.requireNonNull(breaches, "breaches");
        trades = List.copyOf(trades);
        breaches = List.copyOf(breaches);
        if (!trades.isEmpty() && !breaches.isEmpty()) {
            throw new IllegalArgumentException(
                    "A negotiation cannot both have executed a trade and been rejected");
        }
    }

    static NegotiationResult executed(Trade trade) {
        return new NegotiationResult(List.of(trade), List.of());
    }

    static NegotiationResult rejected(List<LimitBreach> breaches) {
        return new NegotiationResult(List.of(), breaches);
    }

    public boolean isRejected() {
        return !breaches.isEmpty();
    }
}
