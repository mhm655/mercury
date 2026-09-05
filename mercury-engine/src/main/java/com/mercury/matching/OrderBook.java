package com.mercury.matching;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.OrderId;
import com.mercury.core.money.Price;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A central limit order book for one instrument, matching on price-time priority.
 *
 * <h2>Structure</h2>
 * <pre>
 * OrderBook
 * |- TreeMap&lt;Price, PriceLevel&gt; bids   (descending: best bid first)
 * |- TreeMap&lt;Price, PriceLevel&gt; asks   (ascending:  best ask first)
 * |- HashMap&lt;OrderId, OrderNode&gt;      (O(1) cancellation)
 * |- cached best bid / best ask levels (O(1) top of book)
 * </pre>
 *
 * <h2>Complexity</h2>
 * P is the number of distinct price levels on a side, f the fills produced.
 *
 * <table border="1">
 *   <caption>Operation costs</caption>
 *   <tr><th>Operation</th><th>Cost</th><th>Why</th></tr>
 *   <tr><td>Insert at a new price</td><td>O(log P)</td><td>TreeMap insertion</td></tr>
 *   <tr><td>Insert at an existing price</td><td>O(1)</td><td>append to the level's tail</td></tr>
 *   <tr><td>Cancel</td><td>O(1)</td>
 *       <td>id-to-node map plus intrusive unlink; O(log P) only when the level empties</td></tr>
 *   <tr><td>Best bid / best ask</td><td>O(1)</td><td>cached level reference</td></tr>
 *   <tr><td>Match one fill</td><td>O(1)</td><td>always the head of the best level</td></tr>
 *   <tr><td>Match sweeping k levels</td><td>O(k log P + f)</td><td>one removal per emptied level</td></tr>
 * </table>
 *
 * <h3>Alternatives considered and rejected</h3>
 * <ul>
 *   <li><b>{@code ArrayList} scanned linearly.</b> O(n) for essentially everything. The
 *       baseline, and the thing a benchmark exists to beat.</li>
 *   <li><b>A single {@code PriorityQueue} per side.</b> O(log n) insert and O(log n) to pop
 *       the best, but no O(1) cancellation (removal is O(n)), no grouping by price level for
 *       depth queries, and no stable FIFO among equal prices - so it cannot express time
 *       priority at all, which is disqualifying.</li>
 *   <li><b>A fixed array indexed by tick.</b> O(1) for everything and the right answer for a
 *       real exchange, where the tick grid is bounded and known. Rejected here because it
 *       assumes a price range the simulation does not fix, and would allocate an array
 *       sized to the whole grid regardless of how sparse the book is.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <b>This class is not thread-safe, deliberately.</b> The concurrency model is single-writer:
 * each book is owned by exactly one thread and fed from a command queue, so books for
 * different instruments run in parallel while a single book is never contended. Serialising
 * commands beats locking the structure - it is faster, far easier to reason about, and it
 * makes deterministic replay from the command log fall out for free. Adding
 * {@code synchronized} here would be slower and would buy nothing the queue does not already
 * provide. See {@code DESIGN_PROPOSAL.md} section 5.6.
 */
public final class OrderBook {

    private final InstrumentId instrumentId;

    /** Bids descending, so {@code firstEntry()} is the best bid. */
    private final TreeMap<Price, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());

    /** Asks ascending, so {@code firstEntry()} is the best ask. */
    private final TreeMap<Price, PriceLevel> asks = new TreeMap<>();

    /** Every resting order, for O(1) cancellation. */
    private final Map<OrderId, OrderNode> restingOrders = new HashMap<>();

    /**
     * Cached top of book. Maintained on every mutation so reads are O(1) rather than the
     * O(log P) a {@code TreeMap.firstEntry()} would cost - and the matching loop reads the
     * opposite side's top on every iteration.
     */
    private PriceLevel bestBid;
    private PriceLevel bestAsk;

    /**
     * Monotonic counter identifying fills, so executions can be ordered deterministically
     * without consulting a clock - two fills can easily share a millisecond, and wall time
     * would break replay.
     *
     * <p>It does <em>not</em> establish time priority among resting orders. That is
     * structural: orders append to their level's tail and match from its head, so queue
     * position is arrival order. See {@code OrderNode}.
     */
    private long fillSequence;

    public OrderBook(InstrumentId instrumentId) {
        this.instrumentId = Objects.requireNonNull(instrumentId, "instrumentId");
    }

    public InstrumentId instrumentId() {
        return instrumentId;
    }

    // ------------------------------------------------------------ submission

    /**
     * Matches {@code order} against the book, resting any remainder if permitted.
     *
     * @throws IllegalArgumentException if the order is for a different instrument or its id
     *                                  is already resting
     */
    public MatchResult submit(Order order) {
        Objects.requireNonNull(order, "order");
        if (!order.instrumentId().equals(instrumentId)) {
            throw new IllegalArgumentException(
                    "Order " + order.id() + " is for " + order.instrumentId()
                            + " but this book is for " + instrumentId);
        }
        if (restingOrders.containsKey(order.id())) {
            throw new IllegalArgumentException(
                    "Order id " + order.id() + " is already resting in the book");
        }

        List<Fill> fills = new ArrayList<>();
        long remaining = match(order, fills);
        long filled = order.quantity() - remaining;

        if (remaining > 0 && order.timeInForce().restsInBook()) {
            rest(order, remaining);
            return new MatchResult(order.id(),
                    filled > 0 ? OrderStatus.PARTIALLY_FILLED_RESTING : OrderStatus.RESTING,
                    fills, filled, remaining);
        }
        OrderStatus status;
        if (remaining == 0) {
            status = OrderStatus.FILLED;
        } else if (filled > 0) {
            status = OrderStatus.PARTIALLY_FILLED_CANCELLED;
        } else {
            status = OrderStatus.CANCELLED;
        }
        return new MatchResult(order.id(), status, fills, filled, 0);
    }

    /**
     * Crosses {@code order} against the opposite side, appending executions to {@code fills}.
     *
     * <p>Walks the opposite book from the best price outwards, taking each level's queue from
     * the head so that price priority and time priority are both honoured. Stops when the
     * order is filled, the book runs out, or the next level is a price the order will not
     * accept.
     *
     * @return the quantity still unfilled
     */
    private long match(Order order, List<Fill> fills) {
        long remaining = order.quantity();
        TreeMap<Price, PriceLevel> opposite = order.isBuy() ? asks : bids;

        while (remaining > 0) {
            PriceLevel level = order.isBuy() ? bestAsk : bestBid;
            if (level == null || !order.acceptsPrice(level.price())) {
                break;
            }
            while (remaining > 0 && !level.isEmpty()) {
                OrderNode resting = level.head();
                long fillQuantity = Math.min(remaining, resting.remainingQuantity());

                // Executes at the RESTING order's price, never the aggressor's: the resting
                // order named its price first and is entitled to it, and the aggressor takes
                // the price improvement. See Fill's documentation.
                fills.add(new Fill(++fillSequence, instrumentId, resting.orderId(), order.id(),
                        order.side(), level.price(), fillQuantity));

                resting.reduceBy(fillQuantity);
                level.recordFill(fillQuantity);
                remaining -= fillQuantity;

                if (resting.isFullyFilled()) {
                    level.remove(resting);
                    restingOrders.remove(resting.orderId());
                }
            }
            if (level.isEmpty()) {
                opposite.remove(level.price());
                refreshBest(order.isBuy() ? Side.SELL : Side.BUY);
            }
        }
        return remaining;
    }

    /** Queues the unfilled remainder, creating its price level if this is the first order there. */
    private void rest(Order order, long remaining) {
        Price price = order.limitPrice().orElseThrow(() ->
                new IllegalStateException("A market order cannot rest: " + order.id()));

        TreeMap<Price, PriceLevel> side = order.isBuy() ? bids : asks;
        // computeIfAbsent is O(log P) when the level is new and O(log P) to look up otherwise;
        // the append inside PriceLevel is the O(1) part.
        PriceLevel level = side.computeIfAbsent(price, PriceLevel::new);

        OrderNode node = new OrderNode(order);
        long alreadyFilled = order.quantity() - remaining;
        if (alreadyFilled > 0) {
            // Only when the order partially filled before resting. reduceBy rejects zero,
            // because a zero-quantity fill is meaningless everywhere else it is called.
            node.reduceBy(alreadyFilled);
        }
        level.append(node);
        restingOrders.put(order.id(), node);

        updateBestAfterInsert(order.side(), level);
    }

    // --------------------------------------------------------- cancellation

    /**
     * Removes a resting order.
     *
     * <p>O(1): the id-to-node map locates the node without searching, and the intrusive links
     * let it be detached in constant time. Only when the removal empties a price level does
     * the cost rise to O(log P) for the {@code TreeMap} removal.
     *
     * @return the cancelled order, or empty if no such order is resting - which is an
     *         ordinary race (it may have just filled), not an error
     */
    public Optional<Order> cancel(OrderId orderId) {
        Objects.requireNonNull(orderId, "orderId");
        OrderNode node = restingOrders.remove(orderId);
        if (node == null) {
            return Optional.empty();
        }
        PriceLevel level = node.level;
        Side side = node.order().side();
        level.remove(node);

        if (level.isEmpty()) {
            (side.isBuy() ? bids : asks).remove(level.price());
            refreshBest(side);
        }
        return Optional.of(node.order());
    }

    // ------------------------------------------------------- top of book

    /** Highest price anyone is bidding, or empty if there are no bids. O(1). */
    public Optional<Price> bestBid() {
        return bestBid == null ? Optional.empty() : Optional.of(bestBid.price());
    }

    /** Lowest price anyone is offering, or empty if there are no asks. O(1). */
    public Optional<Price> bestAsk() {
        return bestAsk == null ? Optional.empty() : Optional.of(bestAsk.price());
    }

    /** Quantity available at the best bid, or zero if there is none. */
    public long bestBidQuantity() {
        return bestBid == null ? 0 : bestBid.totalQuantity();
    }

    /** Quantity available at the best ask, or zero if there is none. */
    public long bestAskQuantity() {
        return bestAsk == null ? 0 : bestAsk.totalQuantity();
    }

    /** Best ask minus best bid, or empty unless both sides are populated. */
    public Optional<java.math.BigDecimal> spread() {
        if (bestBid == null || bestAsk == null) {
            return Optional.empty();
        }
        return Optional.of(bestAsk.price().value().subtract(bestBid.price().value()));
    }

    /**
     * True if the best bid is at or above the best ask.
     *
     * <p>Should never hold after {@link #submit}: any crossing is matched away immediately.
     * Exposed so tests can assert the invariant directly rather than inferring it.
     */
    public boolean isCrossed() {
        return bestBid != null && bestAsk != null
                && bestBid.price().compareTo(bestAsk.price()) >= 0;
    }

    // ------------------------------------------------------------- depth

    /** Aggregated depth, best price first, capped at {@code levels} entries per side. */
    public List<DepthEntry> depth(Side side, int levels) {
        Objects.requireNonNull(side, "side");
        if (levels <= 0) {
            throw new IllegalArgumentException("Level count must be positive, but was " + levels);
        }
        TreeMap<Price, PriceLevel> book = side.isBuy() ? bids : asks;
        List<DepthEntry> entries = new ArrayList<>(Math.min(levels, book.size()));
        for (PriceLevel level : book.values()) {
            if (entries.size() == levels) {
                break;
            }
            entries.add(new DepthEntry(level.price(), level.totalQuantity(), level.orderCount()));
        }
        return List.copyOf(entries);
    }

    /** One aggregated price level as seen from outside the book. */
    public record DepthEntry(Price price, long quantity, int orderCount) {
    }

    // -------------------------------------------------------- introspection

    /** Number of orders currently resting on both sides. */
    public int restingOrderCount() {
        return restingOrders.size();
    }

    /** Number of distinct price levels on one side. */
    public int priceLevelCount(Side side) {
        return (side.isBuy() ? bids : asks).size();
    }

    /** True if an order with this id is currently resting. O(1). */
    public boolean contains(OrderId orderId) {
        return restingOrders.containsKey(orderId);
    }

    /** Total unfilled quantity resting on one side. */
    public long totalQuantity(Side side) {
        long total = 0;
        for (PriceLevel level : (side.isBuy() ? bids : asks).values()) {
            total += level.totalQuantity();
        }
        return total;
    }

    public boolean isEmpty() {
        return restingOrders.isEmpty();
    }

    // ----------------------------------------------------- cache maintenance

    /**
     * Updates the cached top of book after an insertion.
     *
     * <p>O(1): a newly inserted level can only become the best if it beats the current one,
     * which is a single comparison. The expensive path is only needed when a level
     * disappears - see {@link #refreshBest}.
     */
    private void updateBestAfterInsert(Side side, PriceLevel level) {
        if (side.isBuy()) {
            if (bestBid == null || level.price().compareTo(bestBid.price()) > 0) {
                bestBid = level;
            }
        } else {
            if (bestAsk == null || level.price().compareTo(bestAsk.price()) < 0) {
                bestAsk = level;
            }
        }
    }

    /**
     * Recomputes the cached top of book from the {@code TreeMap}.
     *
     * <p>O(log P), and called only when a price level has just emptied - so the cost is paid
     * once per level exhausted rather than once per fill.
     */
    private void refreshBest(Side side) {
        if (side.isBuy()) {
            Map.Entry<Price, PriceLevel> entry = bids.firstEntry();
            bestBid = entry == null ? null : entry.getValue();
        } else {
            Map.Entry<Price, PriceLevel> entry = asks.firstEntry();
            bestAsk = entry == null ? null : entry.getValue();
        }
    }

    @Override
    public String toString() {
        return "OrderBook(%s) bid %s x %d | ask %s x %d, %d orders".formatted(
                instrumentId,
                bestBid().map(Price::toString).orElse("-"), bestBidQuantity(),
                bestAsk().map(Price::toString).orElse("-"), bestAskQuantity(),
                restingOrders.size());
    }
}
