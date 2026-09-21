package com.mercury.execution;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.TradeId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The running counterparty exposure an {@link OtcNegotiationVenue} checks a negotiation
 * against - extracted from that class at M18 so it can be shared.
 *
 * <h2>Why this used to live on the venue, and why that stopped being enough</h2>
 * Through M17 {@code exposureByCounterparty} and {@code exposureAdded} were instance fields on
 * {@code OtcNegotiationVenue} itself - the established shape, the same way {@code
 * OrderBookVenue} holds its own order books and owners. That is correct as long as exactly one
 * {@code OtcNegotiationVenue} ever trades against a given counterparty, which was true of every
 * wiring in this codebase - one venue per process, one process per demo. It stops being correct
 * the moment a second venue instance exists against overlapping counterparties (sharded by
 * instrument, a failover replica): each instance would track exposure independently, and a
 * limit sized for the counterparty as a whole would silently under-count. See
 * {@code docs/KNOWN_GAPS.md}'s former "Risk limits | Exposure is per-venue-instance, not global"
 * entry for the fuller statement of the gap this closes.
 *
 * <h2>A mechanical extraction, not a redesign</h2>
 * Every invariant {@code OtcNegotiationVenue.negotiate}/{@code exposureTo}/{@code release}
 * already had is preserved exactly, because this is the same code, the same lock granularity,
 * relocated rather than rewritten: {@link #lock()} is still one mutex guarding both maps, a
 * caller still holds it across the whole read-check-commit sequence a credit check needs (two
 * negotiations interleaving between the read and the commit would each see room for themselves
 * and both execute - a test proved exactly that at 400 concurrent trades against a limit sized
 * for 100), and {@link #release} still trusts only its own records, never a caller-supplied
 * {@link Trade}'s fields directly.
 *
 * <p>Thread-safe, and safe to share across more than one {@code OtcNegotiationVenue}: that
 * sharing is the entire point of extracting it.
 */
public final class ExposureLedger {

    /**
     * Guards both maps below, and must be held by a caller across a whole
     * check-then-{@link #commit} sequence - see the class javadoc.
     */
    private final Object lock = new Object();

    private final Map<CounterpartyId, Money> exposureByCounterparty = new HashMap<>();

    /**
     * Trades whose exposure is still counted, by id. An entry is removed when it is released,
     * so this holds open trades only, not every trade ever recorded here.
     */
    private final Map<TradeId, RecordedExposure> exposureAdded = new HashMap<>();

    /**
     * The lock a caller must hold for the whole span of a read-check-commit sequence against
     * this ledger - reading {@link #exposureTo}, deciding against a {@code RiskLimit}, minting
     * a {@link Trade} on approval, and calling {@link #commit}, all without releasing it. Java's
     * intrinsic locks are reentrant, so this ledger's own {@link #exposureTo} and {@link
     * #release} synchronizing on the same lock internally nests safely inside a caller's own
     * {@code synchronized} block on it.
     */
    public Object lock() {
        return lock;
    }

    /**
     * The running gross notional recorded against {@code counterparty} so far, in
     * {@code limitCurrency} - zero if nothing has executed against it through this ledger yet.
     */
    public Money exposureTo(CounterpartyId counterparty, Currency limitCurrency) {
        Objects.requireNonNull(counterparty, "counterparty");
        Objects.requireNonNull(limitCurrency, "limitCurrency");
        synchronized (lock) {
            return exposureByCounterparty.getOrDefault(counterparty, Money.zero(limitCurrency));
        }
    }

    /**
     * Records {@code trade}'s approved exposure. The caller must already hold {@link #lock()}
     * - this method does not acquire it itself, because it exists to be the last step of a
     * critical section a caller opened earlier to read {@link #exposureTo} and check it against
     * a limit; acquiring the lock again here would only prove reentrancy works, not add safety.
     */
    void commit(CounterpartyId counterparty, Money projectedExposure, Trade trade,
               Money tradeExposure) {
        exposureByCounterparty.put(counterparty, projectedExposure);
        exposureAdded.put(trade.id(), new RecordedExposure(trade, counterparty, tradeExposure));
    }

    /**
     * Takes {@code trade}'s recorded contribution back out of the running exposure it added
     * when it was committed - the same trust model {@code OtcNegotiationVenue.release} always
     * had: this looks up what was actually recorded under {@code trade.id()}, rather than
     * trusting {@code trade}'s own fields, since a {@link Trade} is a public value type any code
     * in the process can construct and walk to {@code SETTLED} by hand.
     *
     * @throws IllegalArgumentException if {@code trade} is neither {@code SETTLED} nor
     *         {@code CANCELLED}, was never recorded here, is not a continuation of the trade
     *         that was recorded, or has already been released
     */
    public void release(Trade trade) {
        Objects.requireNonNull(trade, "trade");
        if (trade.status() != TradeStatus.SETTLED && trade.status() != TradeStatus.CANCELLED) {
            throw new IllegalArgumentException(
                    "Only a SETTLED or CANCELLED trade releases exposure, but " + trade.id() + " is "
                            + trade.status());
        }
        synchronized (lock) {
            RecordedExposure recorded = exposureAdded.get(trade.id());
            if (recorded == null) {
                throw new IllegalArgumentException(
                        "Trade " + trade.id() + " was never recorded as open counterparty exposure by "
                                + "this ledger, or has already been released. Only a trade this ledger "
                                + "recorded can be released, and only once - a foreign Trade must not "
                                + "subtract from real exposure, and a second release would understate it");
            }
            if (!continues(recorded.executed(), trade)) {
                throw new IllegalArgumentException(
                        "Trade " + trade.id() + " is not the trade recorded under that id: its terms or "
                                + "its lifecycle history differ from what was originally negotiated, so "
                                + "it cannot release that trade's exposure");
            }

            Money existing = exposureByCounterparty.getOrDefault(
                    recorded.counterparty(), Money.zero(recorded.amount().currency()));
            if (existing.isLessThan(recorded.amount())) {
                // Unreachable under correct bookkeeping - see OtcNegotiationVenue's former
                // javadoc for why this throws rather than floors at zero.
                throw new IllegalStateException(
                        "Exposure to " + recorded.counterparty() + " (" + existing + ") is smaller than "
                                + "trade " + trade.id() + "'s own recorded contribution (" + recorded.amount()
                                + "). This ledger's exposure bookkeeping is inconsistent.");
            }

            exposureAdded.remove(trade.id());
            exposureByCounterparty.put(recorded.counterparty(), existing.minus(recorded.amount()));
        }
    }

    /** How many trades' exposure is still counted. Package-private, for tests of boundedness. */
    int openExposureCount() {
        synchronized (lock) {
            return exposureAdded.size();
        }
    }

    /** True if {@code candidate} is {@code executed} with zero or more transitions appended. */
    private static boolean continues(Trade executed, Trade candidate) {
        List<?> history = candidate.history();
        return candidate.instrumentId().equals(executed.instrumentId())
                && candidate.owner().equals(executed.owner())
                && candidate.delta().equals(executed.delta())
                && candidate.consideration().equals(executed.consideration())
                && candidate.tradeDate().equals(executed.tradeDate())
                && candidate.settlementDate().equals(executed.settlementDate())
                && candidate.counterparty().equals(executed.counterparty())
                && history.size() >= executed.history().size()
                && history.subList(0, executed.history().size()).equals(executed.history());
    }

    /**
     * What one executed trade added to {@code exposureByCounterparty}, recorded so
     * {@link #release} can undo exactly that amount rather than trusting a caller-supplied
     * {@link Trade}'s own fields.
     */
    private record RecordedExposure(Trade executed, CounterpartyId counterparty, Money amount) {
    }
}
