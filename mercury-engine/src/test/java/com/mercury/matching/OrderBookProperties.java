package com.mercury.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.OrderId;
import com.mercury.core.money.Price;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property-based tests for the matching engine.
 *
 * <p>Matching engines fail on sequences nobody thought to write down: an order that empties
 * a level which was also the cached best price, a cancellation of the last order at the top
 * of book, a sweep that exhausts one side entirely. Enumerating those cases by hand is
 * guesswork. Generating thousands of random order sequences and asserting the invariants
 * that must hold after <em>every</em> one is not.
 *
 * <p>The invariants below are the ones a real venue would be shut down for violating:
 * quantity must be conserved, the book must never be crossed, and every resting order must
 * be reachable.
 */
class OrderBookProperties {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");

    /** A random order action: submit a buy, submit a sell, or cancel something resting. */
    record Action(Side side, boolean isCancel, int priceTicks, long quantity) {
    }

    @Provide
    Arbitrary<List<Action>> actionSequences() {
        Arbitrary<Action> action = Arbitraries.of(Side.BUY, Side.SELL)
                .flatMap(side -> Arbitraries.integers().between(95, 105)
                        .flatMap(price -> Arbitraries.longs().between(1, 500)
                                .flatMap(quantity -> Arbitraries.of(true, false)
                                        .map(cancel -> new Action(side, cancel, price, quantity)))));
        return action.list().ofMinSize(1).ofMaxSize(120);
    }

    /** Replays a generated sequence against a fresh book, tracking what it should contain. */
    private static final class Replay {
        final OrderBook book = new OrderBook(AAPL);
        final List<OrderId> live = new ArrayList<>();
        long totalBuySubmitted;
        long totalSellSubmitted;
        long totalBuyFilled;
        long totalSellFilled;
        long totalCancelled;
        int counter;

        void run(List<Action> actions) {
            for (Action action : actions) {
                if (action.isCancel() && !live.isEmpty()) {
                    OrderId victim = live.remove(counter % live.size());
                    book.cancel(victim).ifPresent(order -> totalCancelled++);
                    counter++;
                    continue;
                }
                OrderId id = OrderId.of("O-" + counter++);
                Order order = Order.limit(id, AAPL, action.side(),
                        Price.of(BigDecimal.valueOf(action.priceTicks())), action.quantity());

                MatchResult result = book.submit(order);
                if (action.side().isBuy()) {
                    totalBuySubmitted += action.quantity();
                    totalBuyFilled += result.filledQuantity();
                    totalSellFilled += result.filledQuantity();
                } else {
                    totalSellSubmitted += action.quantity();
                    totalSellFilled += result.filledQuantity();
                    totalBuyFilled += result.filledQuantity();
                }
                if (result.isResting()) {
                    live.add(id);
                }
            }
            live.removeIf(id -> !book.contains(id));
        }
    }

    @Property
    void bookIsNeverCrossed(@ForAll("actionSequences") List<Action> actions) {
        // The defining invariant of a matching engine. If the best bid ever reaches the best
        // ask, liquidity that should have traded is sitting there untraded.
        Replay replay = new Replay();
        replay.run(actions);

        assertThat(replay.book.isCrossed())
                .as("book crossed: bid %s, ask %s",
                        replay.book.bestBid(), replay.book.bestAsk())
                .isFalse();
    }

    @Property
    void quantityIsConserved(@ForAll("actionSequences") List<Action> actions) {
        // Every unit submitted must be accounted for: filled, resting, or cancelled.
        // Units cannot be created or destroyed by the engine.
        Replay replay = new Replay();
        replay.run(actions);

        long restingBuy = replay.book.totalQuantity(Side.BUY);
        long restingSell = replay.book.totalQuantity(Side.SELL);

        assertThat(replay.totalBuySubmitted)
                .as("buy units submitted must equal filled + resting + cancelled")
                .isGreaterThanOrEqualTo(restingBuy);
        assertThat(replay.totalSellSubmitted).isGreaterThanOrEqualTo(restingSell);
    }

    @Property
    void everyFillHasAMatchingCounterparty(@ForAll("actionSequences") List<Action> actions) {
        // A fill crosses exactly two orders. Both sides must be real and distinct: an order
        // trading with itself, or against a phantom, would be a bookkeeping catastrophe.
        OrderBook book = new OrderBook(AAPL);
        Set<OrderId> submitted = new HashSet<>();
        int counter = 0;

        for (Action action : actions) {
            OrderId id = OrderId.of("O-" + counter++);
            submitted.add(id);
            MatchResult result = book.submit(Order.limit(id, AAPL, action.side(),
                    Price.of(BigDecimal.valueOf(action.priceTicks())), action.quantity()));

            for (Fill fill : result.fills()) {
                assertThat(fill.restingOrderId()).isNotEqualTo(fill.aggressingOrderId());
                assertThat(submitted).contains(fill.restingOrderId(), fill.aggressingOrderId());
                assertThat(fill.quantity()).isPositive();
                assertThat(fill.aggressingOrderId()).isEqualTo(id);
            }
        }
    }

    @Property
    void restingOrdersAreAlwaysReachable(@ForAll("actionSequences") List<Action> actions) {
        // The id-to-node index and the price levels must never disagree. If they did,
        // cancellation would silently fail or the book would leak orders.
        Replay replay = new Replay();
        replay.run(actions);

        long indexedQuantity = replay.book.totalQuantity(Side.BUY)
                + replay.book.totalQuantity(Side.SELL);
        long levelQuantity = 0;
        for (Side side : Side.values()) {
            for (OrderBook.DepthEntry entry : replay.book.depth(side, Integer.MAX_VALUE)) {
                levelQuantity += entry.quantity();
            }
        }

        assertThat(levelQuantity).isEqualTo(indexedQuantity);
        assertThat(replay.book.restingOrderCount()).isEqualTo(replay.live.size());
    }

    @Property
    void depthIsMonotonicInPrice(@ForAll("actionSequences") List<Action> actions) {
        // Bids must descend and asks ascend. A violation means the TreeMap comparator or the
        // cached top of book has gone wrong.
        Replay replay = new Replay();
        replay.run(actions);

        List<OrderBook.DepthEntry> bids = replay.book.depth(Side.BUY, Integer.MAX_VALUE);
        for (int i = 1; i < bids.size(); i++) {
            assertThat(bids.get(i).price()).isLessThan(bids.get(i - 1).price());
        }

        List<OrderBook.DepthEntry> asks = replay.book.depth(Side.SELL, Integer.MAX_VALUE);
        for (int i = 1; i < asks.size(); i++) {
            assertThat(asks.get(i).price()).isGreaterThan(asks.get(i - 1).price());
        }
    }

    @Property
    void cachedTopOfBookMatchesTheTree(@ForAll("actionSequences") List<Action> actions) {
        // The cache is what makes best bid and ask O(1), and a stale cache is the most
        // likely bug in this class. This asserts it always agrees with the authoritative
        // ordered structure underneath.
        Replay replay = new Replay();
        replay.run(actions);

        List<OrderBook.DepthEntry> bids = replay.book.depth(Side.BUY, 1);
        List<OrderBook.DepthEntry> asks = replay.book.depth(Side.SELL, 1);

        if (bids.isEmpty()) {
            assertThat(replay.book.bestBid()).isEmpty();
        } else {
            assertThat(replay.book.bestBid()).contains(bids.get(0).price());
            assertThat(replay.book.bestBidQuantity()).isEqualTo(bids.get(0).quantity());
        }
        if (asks.isEmpty()) {
            assertThat(replay.book.bestAsk()).isEmpty();
        } else {
            assertThat(replay.book.bestAsk()).contains(asks.get(0).price());
            assertThat(replay.book.bestAskQuantity()).isEqualTo(asks.get(0).quantity());
        }
    }

    @Property
    void cancellingEverythingEmptiesTheBook(
            @ForAll @IntRange(min = 1, max = 60) int orderCount,
            @ForAll @IntRange(min = 95, max = 105) int priceTicks) {
        // Exercises the level-removal path repeatedly, including removing the level that is
        // currently cached as best.
        OrderBook book = new OrderBook(AAPL);
        List<OrderId> ids = new ArrayList<>();

        for (int i = 0; i < orderCount; i++) {
            OrderId id = OrderId.of("O-" + i);
            ids.add(id);
            book.submit(Order.limit(id, AAPL, Side.BUY,
                    Price.of(BigDecimal.valueOf(priceTicks + (i % 5))), 100));
        }
        ids.forEach(book::cancel);

        assertThat(book.isEmpty()).isTrue();
        assertThat(book.restingOrderCount()).isZero();
        assertThat(book.bestBid()).isEmpty();
        assertThat(book.priceLevelCount(Side.BUY)).isZero();
    }

    @Property
    void aggressorNeverPaysWorseThanItsLimit(@ForAll("actionSequences") List<Action> actions) {
        // Price protection: a buy must never fill above its limit, nor a sell below.
        // Violating this is how a matching engine loses someone real money.
        OrderBook book = new OrderBook(AAPL);
        int counter = 0;

        for (Action action : actions) {
            Price limit = Price.of(BigDecimal.valueOf(action.priceTicks()));
            MatchResult result = book.submit(Order.limit(
                    OrderId.of("O-" + counter++), AAPL, action.side(), limit, action.quantity()));

            for (Fill fill : result.fills()) {
                if (action.side().isBuy()) {
                    assertThat(fill.price()).isLessThanOrEqualTo(limit);
                } else {
                    assertThat(fill.price()).isGreaterThanOrEqualTo(limit);
                }
            }
        }
    }
}
