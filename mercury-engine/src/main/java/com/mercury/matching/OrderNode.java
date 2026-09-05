package com.mercury.matching;

import com.mercury.core.id.OrderId;

/**
 * One resting order inside a {@link PriceLevel}, and a node in that level's intrusive
 * doubly-linked list.
 *
 * <h2>Why intrusive linking</h2>
 * The links live on the node itself rather than in a separate list structure. That is what
 * makes cancellation O(1): given a node - which the book's {@code HashMap<OrderId, OrderNode>}
 * hands back in O(1) - unlinking is four pointer writes, with no search for the node's
 * position.
 *
 * <p>The obvious alternative, an {@code ArrayDeque<Order>} per level, is O(1) at the ends
 * but O(n) to remove from the middle, and cancellation is overwhelmingly a middle-of-queue
 * operation: in real markets most orders are cancelled rather than filled, and rarely from
 * the front. That single difference is the reason this class exists instead of a
 * collection.
 *
 * <h2>Time priority is structural, not stored</h2>
 * There is deliberately no sequence number here. A node's priority is its <em>position</em>
 * in the level's queue: new orders are appended to the tail and matching always takes the
 * head, so arrival order is the list order. An earlier version carried a sequence field that
 * was written on every insert and never read - it documented itself as establishing time
 * priority while establishing nothing. Storing an ordering that the structure already
 * guarantees invites the two to drift apart.
 *
 * <h2>Mutable, deliberately</h2>
 * This is the mutable execution state that {@link Order} deliberately does not carry.
 * {@code remainingQuantity} decreases as the order fills. The node is owned entirely by one
 * {@code OrderBook}, which is owned by one thread, so the mutability is contained - see the
 * single-writer note on {@code OrderBook}.
 *
 * <p>Package-private: this is an implementation detail of the book, not part of its API.
 */
final class OrderNode {

    /** The order exactly as submitted; never mutated. */
    private final Order order;

    private long remainingQuantity;

    /** Intrusive list links. Null at the ends of the level's queue. */
    OrderNode previous;
    OrderNode next;

    /** The level holding this node, so removal can tell the level it has emptied. */
    PriceLevel level;

    OrderNode(Order order) {
        this.order = order;
        this.remainingQuantity = order.quantity();
    }

    Order order() {
        return order;
    }

    OrderId orderId() {
        return order.id();
    }

    long remainingQuantity() {
        return remainingQuantity;
    }

    /**
     * Reduces the remaining quantity by a filled amount.
     *
     * @throws IllegalArgumentException if the fill would take the remainder below zero,
     *                                  which would mean the book had over-filled an order
     */
    void reduceBy(long filled) {
        if (filled <= 0) {
            throw new IllegalArgumentException("Fill quantity must be positive, but was " + filled);
        }
        if (filled > remainingQuantity) {
            throw new IllegalArgumentException(
                    "Cannot fill " + filled + " against a remaining quantity of "
                            + remainingQuantity + " on order " + orderId()
                            + "; the matching engine has over-filled");
        }
        remainingQuantity -= filled;
    }

    boolean isFullyFilled() {
        return remainingQuantity == 0;
    }

    /** Detaches this node from its neighbours. Leaves the level's bookkeeping to the caller. */
    void unlink() {
        if (previous != null) {
            previous.next = next;
        }
        if (next != null) {
            next.previous = previous;
        }
        previous = null;
        next = null;
    }

    @Override
    public String toString() {
        return "%s remaining=%d".formatted(orderId(), remainingQuantity);
    }
}
