package com.mercury.matching;

import com.mercury.core.money.Price;

/**
 * All resting orders at one price, queued in arrival order.
 *
 * <h2>Price-time priority</h2>
 * Price priority comes from the book's {@code TreeMap}, which orders levels. Time priority
 * comes from this queue: orders join at the tail and are matched from the head, so the
 * order that arrived first at a given price trades first. Both halves of the rule live in
 * exactly one place each.
 *
 * <h2>Aggregate quantity is maintained incrementally</h2>
 * {@code totalQuantity} is updated on every add, fill and removal rather than recomputed by
 * walking the queue. Market-depth displays and the matching loop both read it constantly,
 * and walking a level with thousands of orders to answer "how much is available here?"
 * would turn an O(1) question into an O(n) one.
 *
 * <p>Package-private, and not thread-safe: owned entirely by one {@code OrderBook}.
 */
final class PriceLevel {

    private final Price price;

    private OrderNode head;
    private OrderNode tail;

    private long totalQuantity;
    private int orderCount;

    PriceLevel(Price price) {
        this.price = price;
    }

    Price price() {
        return price;
    }

    /** Total unfilled quantity resting at this price. Maintained incrementally, O(1) to read. */
    long totalQuantity() {
        return totalQuantity;
    }

    int orderCount() {
        return orderCount;
    }

    boolean isEmpty() {
        return head == null;
    }

    /** The next order to be matched here: the one that has waited longest. */
    OrderNode head() {
        return head;
    }

    /** Appends to the tail, which is what gives later arrivals lower priority. O(1). */
    void append(OrderNode node) {
        node.level = this;
        if (tail == null) {
            head = node;
            tail = node;
        } else {
            node.previous = tail;
            tail.next = node;
            tail = node;
        }
        totalQuantity += node.remainingQuantity();
        orderCount++;
    }

    /**
     * Detaches {@code node} from this level in O(1).
     *
     * <p>Fixing up the head and tail references before delegating to
     * {@link OrderNode#unlink()} is what keeps this constant-time - there is no scan to find
     * the node's position, because the node already knows its neighbours.
     */
    void remove(OrderNode node) {
        if (node.level != this) {
            throw new IllegalStateException(
                    "Order " + node.orderId() + " is not resting at price " + price);
        }
        if (head == node) {
            head = node.next;
        }
        if (tail == node) {
            tail = node.previous;
        }
        node.unlink();
        node.level = null;

        totalQuantity -= node.remainingQuantity();
        orderCount--;
    }

    /** Records that {@code filled} units traded out of a node still resting here. */
    void recordFill(long filled) {
        totalQuantity -= filled;
    }

    @Override
    public String toString() {
        return "%s x %d (%d orders)".formatted(price, totalQuantity, orderCount);
    }
}
