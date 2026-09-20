package com.mercury.execution;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.OrderId;
import com.mercury.core.id.TradeId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.event.EventBus;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.matching.Fill;
import com.mercury.matching.MatchResult;
import com.mercury.matching.Order;
import com.mercury.matching.OrderBook;
import com.mercury.matching.Side;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeExecuted;
import com.mercury.trade.TradeStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The exchange-traded {@link ExecutionVenue}: routes an {@link OrderBookInstruction} into a
 * price-time-priority {@link OrderBook} for its instrument, and turns every {@link Fill}
 * that results into {@link Trade}s.
 *
 * <h2>One {@code Trade} per side, per fill</h2>
 * A single fill crosses two orders, and each side is a real trade for its own owner - the
 * resting order's owner traded exactly as much as the aggressor did. Both get a
 * {@code Trade}, each with an empty {@code counterparty}: a central limit order book is
 * anonymous by design (see {@code TradabilityProfile}), so neither side is told who filled
 * against them, exactly as a real exchange member only ever receives its own execution
 * report. Each is derived directly from that fill's own {@code price() x quantity()} -
 * never from an order-level average, which would risk drifting a cent from the sum of the
 * real fills once an order sweeps more than one price level.
 *
 * <h2>Only fills become trades</h2>
 * An order that rests unfilled, or is cancelled with nothing filled, produces no
 * {@code Trade} at all - that outcome is already fully represented by the
 * {@code OrderStatus} on the {@link MatchResult} this venue receives from the book.
 *
 * <h2>Order ids: G-1</h2>
 * {@code OrderBookInstruction} never carries an id; this venue mints one from its own
 * {@link OrderIdGenerator} for every order it submits, which is what makes an id reused
 * after a fill (G-1) unreachable through this, the sole sanctioned entry point. See that
 * class's javadoc.
 */
public final class OrderBookVenue implements ExecutionVenue<OrderBookInstruction>, AutoCloseable {

    private final OrderIdGenerator orderIdGenerator = new OrderIdGenerator("OB-");
    private final TradeIdGenerator tradeIdGenerator;
    private final InstrumentCatalog instruments;
    private final EventBus events;
    private final BookConcurrency concurrency;

    /**
     * One entry per instrument ever traded, created on first use. Concurrent because two
     * callers can arrive for two different instruments at once, and this map is the one piece
     * of state no single book's lane can own.
     */
    private final Map<InstrumentId, InstrumentLane> lanes = new ConcurrentHashMap<>();

    /** A venue nothing listens to; every execution is still returned to the caller. */
    public OrderBookVenue(TradeIdGenerator tradeIdGenerator, InstrumentCatalog instruments) {
        this(tradeIdGenerator, instruments, EventBus.ignoring());
    }

    /**
     * @param events every {@link Trade} this venue mints is published here as a
     *               {@link TradeExecuted}, both sides of every fill - see
     *               {@code LedgerKeeper} for why a subscriber must filter by owner
     */
    public OrderBookVenue(TradeIdGenerator tradeIdGenerator, InstrumentCatalog instruments,
                          EventBus events) {
        this(tradeIdGenerator, instruments, events, BookConcurrency.INLINE);
    }

    /**
     * @param concurrency who matches: the calling thread, or a thread that owns the book. The
     *                    same trades either way - see {@link BookConcurrency}. A venue built
     *                    with {@link BookConcurrency#THREAD_PER_BOOK} owns threads and should
     *                    be {@link #close()}d.
     */
    public OrderBookVenue(TradeIdGenerator tradeIdGenerator, InstrumentCatalog instruments,
                          EventBus events, BookConcurrency concurrency) {
        this.tradeIdGenerator = Objects.requireNonNull(tradeIdGenerator, "tradeIdGenerator");
        this.instruments = Objects.requireNonNull(instruments, "instruments");
        this.events = Objects.requireNonNull(events, "events");
        this.concurrency = Objects.requireNonNull(concurrency, "concurrency");
    }

    /**
     * One instrument's book, the owners of its resting orders, and the lane that hands them
     * out. Grouped into one object because they are one thing: the state a single writer owns.
     * Nothing in here may be touched except inside {@link BookLane#run}.
     *
     * <p>{@code owners} holds the owner of every order that can still be filled, so a resting
     * order's owner is known when a later aggressor fills it. An entry is dropped as soon as
     * its order leaves the book, so it is bounded by resting orders rather than by every order
     * ever submitted.
     */
    private record InstrumentLane(OrderBook book, Map<OrderId, CounterpartyId> owners,
                                  BookLane lane) {

        static InstrumentLane of(InstrumentId instrumentId, BookConcurrency concurrency) {
            return new InstrumentLane(new OrderBook(instrumentId), new HashMap<>(),
                    concurrency.laneFor(instrumentId));
        }
    }

    /**
     * Matches {@code instruction} against its instrument's book and returns the trades.
     *
     * <p>Not synchronized: exclusivity is per book, not per venue, so two instruments never
     * wait for each other. Everything that touches a book happens inside that book's
     * {@link BookLane} - either this thread under a lock, or the thread that owns the book,
     * depending on {@link BookConcurrency}. Unserialised, two threads matching at once
     * over-filled a resting order under test, which is why the lane exists at all.
     */
    @Override
    public List<Trade> execute(OrderBookInstruction obi, SimulationClock clock) {
        Objects.requireNonNull(obi, "obi");
        Objects.requireNonNull(clock, "clock");

        // Resolved before the book is touched: failing after matching would leave resting
        // orders filled with no trades returned for either side.
        FinancialInstrument instrument = instruments.require(obi.instrumentId());
        if (!instrument.tradability().isExchangeTraded()) {
            throw new IllegalArgumentException(
                    instrument.id() + " is not exchange traded (" + instrument.tradability()
                            + "); it belongs on the OTC venue, not in a matching engine");
        }
        Currency currency = instrument.currency();

        // Minted outside the lane: the generator is atomic, and an id is not book state.
        OrderId orderId = orderIdGenerator.next();
        Order order = new Order(orderId, obi.instrumentId(), obi.side(), obi.type(),
                obi.limitPrice(), obi.quantity(), obi.timeInForce(), obi.participant());

        InstrumentLane instrumentLane = laneFor(obi.instrumentId());
        return instrumentLane.lane().run(
                () -> match(instrumentLane, order, obi.participant(), currency, clock));
    }

    /**
     * Everything that touches the book. Runs with exclusive access to {@code instrumentLane} -
     * on the calling thread or on the book's own writer thread, which is the only difference
     * between the two {@link BookConcurrency} choices.
     */
    private List<Trade> match(InstrumentLane instrumentLane, Order order,
                              CounterpartyId participant, Currency currency, SimulationClock clock) {
        OrderBook book = instrumentLane.book();
        Map<OrderId, CounterpartyId> owners = instrumentLane.owners();
        owners.put(order.id(), participant);

        MatchResult result = book.submit(order);

        List<Trade> trades = new ArrayList<>();
        for (Fill fill : result.fills()) {
            trades.add(tradeFor(owners, fill, fill.aggressorSide(), fill.aggressingOrderId(),
                    currency, clock));
            Side restingSide = fill.aggressorSide().isBuy() ? Side.SELL : Side.BUY;
            trades.add(tradeFor(owners, fill, restingSide, fill.restingOrderId(), currency, clock));
        }

        // Owners are needed only while an order can still be filled. Checked after the trades
        // are built, since building them reads the owners of orders that just filled.
        for (Fill fill : result.fills()) {
            if (!book.contains(fill.restingOrderId())) {
                owners.remove(fill.restingOrderId());
            }
        }
        if (!book.contains(order.id())) {
            owners.remove(order.id());
        }

        // Published while this book is still exclusively held, so subscribers see executions
        // in the order the book actually matched them - a blotter or audit feed that received
        // them reordered would be recording a history that never happened. The cost is that a
        // synchronous subscriber runs inside the lane and holds up the next caller for this
        // instrument; that is exactly what AsynchronousEventBus is for, and it is why this
        // loop does no work of its own beyond announcing.
        for (Trade trade : trades) {
            events.publish(new TradeExecuted(trade));
        }
        return List.copyOf(trades);
    }

    private InstrumentLane laneFor(InstrumentId instrumentId) {
        return lanes.computeIfAbsent(instrumentId, id -> InstrumentLane.of(id, concurrency));
    }

    /**
     * Releases every book's lane. Only {@link BookConcurrency#THREAD_PER_BOOK} owns anything
     * to release; on the default venue this does nothing, which is why callers that never
     * asked for threads have never had to call it.
     *
     * <p>The books themselves are left as they are. This ends who may touch them, not what
     * they hold - a closed venue is one nothing more can be traded on, not one whose resting
     * orders were silently cancelled.
     */
    @Override
    public void close() {
        lanes.values().forEach(instrumentLane -> instrumentLane.lane().close());
    }

    /** How many orders' owners are held, across every book. For tests of boundedness. */
    int trackedOwnerCount() {
        return lanes.values().stream()
                .mapToInt(instrumentLane ->
                        instrumentLane.lane().run(() -> instrumentLane.owners().size()))
                .sum();
    }

    /**
     * One side of one fill, as an executed {@code Trade}. {@code side} is this party's own
     * side of the trade (not necessarily the fill's aggressor side), which is what decides
     * the sign of {@code delta} and {@code consideration}.
     */
    private Trade tradeFor(Map<OrderId, CounterpartyId> owners, Fill fill, Side side,
                           OrderId orderId, Currency currency, SimulationClock clock) {
        CounterpartyId owner = owners.get(orderId);
        long signedQuantity = side.isBuy() ? fill.quantity() : -fill.quantity();
        Quantity delta = Quantity.of(signedQuantity);
        // Multiply in BigDecimal first, convert to Money once - the same rule
        // PortfolioLedger.tradeAt follows, and for the same reason: rounding the per-unit
        // price to the currency's minor units before multiplying by quantity would scale
        // the rounding error by the fill size instead of rounding the final total once.
        Money consideration = Money.of(
                fill.price().value().multiply(BigDecimal.valueOf(signedQuantity)), currency);

        Trade trade = Trade.newTrade(tradeIdGenerator.next(), fill.instrumentId(), owner, delta,
                consideration, clock.today(), Optional.empty(), Optional.empty());
        return trade.transitionTo(TradeStatus.VALIDATED, "matched on the order book", clock)
                .transitionTo(TradeStatus.BOOKED, "booked to the ledger", clock)
                .transitionTo(TradeStatus.EXECUTED, "matched in full at " + fill.price(), clock);
    }
}
