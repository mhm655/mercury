package com.mercury.benchmarks;

import com.mercury.core.id.OrderId;
import com.mercury.core.money.Price;
import com.mercury.matching.Order;
import com.mercury.matching.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A deliberately naive order book, for comparison only.
 *
 * <p>This is the implementation the real {@code OrderBook} claims to beat: orders in a flat
 * {@link ArrayList} per side, scanned linearly for everything. It exists so the complexity
 * argument in {@code OrderBook}'s documentation can be <em>measured</em> rather than
 * asserted - a table of big-O costs is a claim, and a benchmark against the obvious
 * alternative is evidence.
 *
 * <p>It is not a strawman. Linear scanning is what most first attempts look like, it is
 * perfectly correct, and at small book sizes it is genuinely competitive - array traversal
 * is cache-friendly in a way that pointer-chasing through a tree is not. The point of the
 * benchmark is to find where that stops being true.
 *
 * <p>Correctness is not the goal here: it supports only the operations being measured.
 * Nothing in the engine depends on it, which is why it lives in the benchmark module.
 */
final class NaiveOrderBook {

    private final List<Order> bids = new ArrayList<>();
    private final List<Order> asks = new ArrayList<>();

    /** O(1) amortised - the one operation the naive design does not lose on. */
    void add(Order order) {
        (order.isBuy() ? bids : asks).add(order);
    }

    /**
     * O(n): scans the list for the id, then shifts every subsequent element down.
     *
     * <p>This is the operation that motivates the real design. Cancellation is the most
     * common event in a live book, and here it costs a full scan plus an array copy.
     */
    boolean cancel(OrderId orderId) {
        return remove(bids, orderId) || remove(asks, orderId);
    }

    private static boolean remove(List<Order> orders, OrderId orderId) {
        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i).id().equals(orderId)) {
                orders.remove(i);
                return true;
            }
        }
        return false;
    }

    /** O(n): every query walks the whole side, because nothing keeps the list ordered. */
    Optional<Price> bestBid() {
        return best(bids, Side.BUY);
    }

    /** O(n), for the same reason. */
    Optional<Price> bestAsk() {
        return best(asks, Side.SELL);
    }

    private static Optional<Price> best(List<Order> orders, Side side) {
        Price best = null;
        for (Order order : orders) {
            Price price = order.limitPrice().orElseThrow();
            if (best == null
                    || (side.isBuy() ? price.compareTo(best) > 0 : price.compareTo(best) < 0)) {
                best = price;
            }
        }
        return Optional.ofNullable(best);
    }

    int size() {
        return bids.size() + asks.size();
    }
}
