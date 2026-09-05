package com.mercury.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.OrderId;
import com.mercury.core.money.Price;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");

    private OrderBook book;
    private AtomicInteger orderCounter;

    @BeforeEach
    void setUp() {
        book = new OrderBook(AAPL);
        orderCounter = new AtomicInteger();
    }

    private OrderId nextId() {
        return OrderId.of("O-" + orderCounter.incrementAndGet());
    }

    private MatchResult buy(String price, long quantity) {
        return book.submit(Order.limit(nextId(), AAPL, Side.BUY, Price.of(price), quantity));
    }

    private MatchResult sell(String price, long quantity) {
        return book.submit(Order.limit(nextId(), AAPL, Side.SELL, Price.of(price), quantity));
    }

    private MatchResult marketBuy(long quantity) {
        return book.submit(Order.market(nextId(), AAPL, Side.BUY, quantity));
    }

    private MatchResult marketSell(long quantity) {
        return book.submit(Order.market(nextId(), AAPL, Side.SELL, quantity));
    }

    @Nested
    @DisplayName("resting orders")
    class Resting {

        @Test
        @DisplayName("a non-crossing limit order rests without trading")
        void restsWhenNotCrossing() {
            MatchResult result = buy("100.00", 500);

            assertThat(result.status()).isEqualTo(OrderStatus.RESTING);
            assertThat(result.fills()).isEmpty();
            assertThat(result.restingQuantity()).isEqualTo(500);
            assertThat(book.restingOrderCount()).isEqualTo(1);
            assertThat(book.bestBid()).contains(Price.of("100.00"));
            assertThat(book.bestBidQuantity()).isEqualTo(500);
        }

        @Test
        @DisplayName("orders at the same price aggregate into one level")
        void ordersAggregateAtAPrice() {
            buy("100.00", 500);
            buy("100.00", 300);
            buy("100.00", 200);

            assertThat(book.priceLevelCount(Side.BUY)).isEqualTo(1);
            assertThat(book.bestBidQuantity()).isEqualTo(1000);
            assertThat(book.restingOrderCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("the book tracks the best price on each side")
        void tracksBestPrices() {
            buy("99.00", 100);
            buy("100.00", 100);   // better bid
            buy("98.00", 100);
            sell("102.00", 100);
            sell("101.00", 100);  // better ask
            sell("103.00", 100);

            assertThat(book.bestBid()).contains(Price.of("100.00"));
            assertThat(book.bestAsk()).contains(Price.of("101.00"));
            assertThat(book.spread()).contains(new java.math.BigDecimal("1.00000000"));
        }

        @Test
        @DisplayName("an empty book has no top of book")
        void emptyBook() {
            assertThat(book.bestBid()).isEmpty();
            assertThat(book.bestAsk()).isEmpty();
            assertThat(book.spread()).isEmpty();
            assertThat(book.isEmpty()).isTrue();
            assertThat(book.isCrossed()).isFalse();
        }
    }

    @Nested
    @DisplayName("price priority")
    class PricePriority {

        @Test
        @DisplayName("a buy matches the cheapest ask first")
        void matchesBestPriceFirst() {
            sell("102.00", 100);
            sell("100.00", 100);
            sell("101.00", 100);

            MatchResult result = buy("103.00", 100);

            assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(result.fills()).hasSize(1);
            assertThat(result.fills().get(0).price()).isEqualTo(Price.of("100.00"));
        }

        @Test
        @DisplayName("a sell matches the highest bid first")
        void sellMatchesHighestBid() {
            buy("98.00", 100);
            buy("100.00", 100);
            buy("99.00", 100);

            MatchResult result = sell("97.00", 100);

            assertThat(result.fills().get(0).price()).isEqualTo(Price.of("100.00"));
        }

        @Test
        @DisplayName("a large order sweeps successive levels in price order")
        void sweepsLevelsInOrder() {
            sell("100.00", 100);
            sell("101.00", 100);
            sell("102.00", 100);

            MatchResult result = buy("102.00", 250);

            // 300 was available across three levels, so all 250 fills and nothing rests.
            assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(result.filledQuantity()).isEqualTo(250);
            assertThat(result.restingQuantity()).isZero();
            assertThat(result.fills()).extracting(Fill::price).containsExactly(
                    Price.of("100.00"), Price.of("101.00"), Price.of("102.00"));
            assertThat(result.fills()).extracting(Fill::quantity).containsExactly(100L, 100L, 50L);
            // The partially consumed top level keeps its remaining 50.
            assertThat(book.bestAsk()).contains(Price.of("102.00"));
            assertThat(book.bestAskQuantity()).isEqualTo(50);
        }

        @Test
        @DisplayName("matching stops at a price the order will not accept")
        void stopsAtUnacceptablePrice() {
            sell("100.00", 100);
            sell("105.00", 100);

            MatchResult result = buy("101.00", 300);

            // Takes the 100 offer, refuses the 105 offer, rests the remaining 200 at 101.
            assertThat(result.filledQuantity()).isEqualTo(100);
            assertThat(result.restingQuantity()).isEqualTo(200);
            assertThat(book.bestBid()).contains(Price.of("101.00"));
            assertThat(book.bestAsk()).contains(Price.of("105.00"));
        }
    }

    @Nested
    @DisplayName("time priority")
    class TimePriority {

        @Test
        @DisplayName("at one price, the order that arrived first trades first")
        void firstInFirstOut() {
            OrderId first = nextId();
            OrderId second = nextId();
            OrderId third = nextId();
            book.submit(Order.limit(first, AAPL, Side.SELL, Price.of("100.00"), 100));
            book.submit(Order.limit(second, AAPL, Side.SELL, Price.of("100.00"), 100));
            book.submit(Order.limit(third, AAPL, Side.SELL, Price.of("100.00"), 100));

            MatchResult result = buy("100.00", 250);

            assertThat(result.fills()).extracting(Fill::restingOrderId)
                    .containsExactly(first, second, third);
            assertThat(result.fills()).extracting(Fill::quantity)
                    .containsExactly(100L, 100L, 50L);
        }

        @Test
        @DisplayName("a partially filled order keeps its place at the front of the queue")
        void partialFillKeepsPriority() {
            OrderId first = nextId();
            OrderId second = nextId();
            book.submit(Order.limit(first, AAPL, Side.SELL, Price.of("100.00"), 100));
            book.submit(Order.limit(second, AAPL, Side.SELL, Price.of("100.00"), 100));

            buy("100.00", 60);   // takes 60 of the first order
            MatchResult next = buy("100.00", 60);

            // The first order keeps priority for its remaining 40 before the second is touched.
            assertThat(next.fills()).extracting(Fill::restingOrderId)
                    .containsExactly(first, second);
            assertThat(next.fills()).extracting(Fill::quantity).containsExactly(40L, 20L);
        }

        @Test
        @DisplayName("resting behind a better price does not jump the queue")
        void betterPriceStillWinsOverTime() {
            OrderId early = nextId();
            OrderId lateButBetter = nextId();
            book.submit(Order.limit(early, AAPL, Side.SELL, Price.of("101.00"), 100));
            book.submit(Order.limit(lateButBetter, AAPL, Side.SELL, Price.of("100.00"), 100));

            MatchResult result = buy("101.00", 100);

            // Price priority outranks time priority: the cheaper later order trades first.
            assertThat(result.fills().get(0).restingOrderId()).isEqualTo(lateButBetter);
        }
    }

    @Nested
    @DisplayName("execution price")
    class ExecutionPrice {

        @Test
        @DisplayName("trades at the resting order's price, not the aggressor's")
        void executesAtRestingPrice() {
            // A resting sell at 100, hit by a buy willing to pay 105. The trade is at 100.
            sell("100.00", 100);

            MatchResult result = buy("105.00", 100);

            assertThat(result.fills().get(0).price()).isEqualTo(Price.of("100.00"));
        }

        @Test
        @DisplayName("the aggressor receives the price improvement")
        void aggressorGetsPriceImprovement() {
            sell("100.00", 100);

            MatchResult result = buy("110.00", 100);

            // Paid 100 rather than the 110 it was prepared to pay: 10 per unit of improvement.
            assertThat(result.averageFillPrice()).contains(new java.math.BigDecimal("100.00000000"));
        }

        @Test
        @DisplayName("the same rule applies when a sell aggresses")
        void sellAggressorAlsoGetsRestingPrice() {
            buy("100.00", 100);

            MatchResult result = sell("95.00", 100);

            assertThat(result.fills().get(0).price()).isEqualTo(Price.of("100.00"));
        }

        @Test
        @DisplayName("the fill records which side aggressed")
        void recordsAggressor() {
            sell("100.00", 100);
            Fill fill = buy("100.00", 100).fills().get(0);

            assertThat(fill.aggressorSide()).isEqualTo(Side.BUY);
            assertThat(fill.buyOrderId()).isEqualTo(fill.aggressingOrderId());
            assertThat(fill.sellOrderId()).isEqualTo(fill.restingOrderId());
        }
    }

    @Nested
    @DisplayName("partial fills")
    class PartialFills {

        @Test
        @DisplayName("a large aggressor consumes several resting orders")
        void largeOrderConsumesMany() {
            sell("100.00", 40);
            sell("100.00", 40);
            sell("100.00", 40);

            MatchResult result = buy("100.00", 100);

            assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(result.fills()).hasSize(3);
            assertThat(result.fills()).extracting(Fill::quantity).containsExactly(40L, 40L, 20L);
            // 20 of the third order still rests.
            assertThat(book.bestAskQuantity()).isEqualTo(20);
        }

        @Test
        @DisplayName("the unfilled remainder of a limit order rests")
        void remainderRests() {
            sell("100.00", 40);

            MatchResult result = buy("100.00", 100);

            assertThat(result.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED_RESTING);
            assertThat(result.filledQuantity()).isEqualTo(40);
            assertThat(result.restingQuantity()).isEqualTo(60);
            assertThat(book.bestBidQuantity()).isEqualTo(60);
        }

        @Test
        @DisplayName("a fully consumed price level is removed")
        void emptyLevelIsRemoved() {
            sell("100.00", 100);
            sell("101.00", 100);

            buy("100.00", 100);

            assertThat(book.priceLevelCount(Side.SELL)).isEqualTo(1);
            assertThat(book.bestAsk()).contains(Price.of("101.00"));
        }
    }

    @Nested
    @DisplayName("market orders")
    class MarketOrders {

        @Test
        @DisplayName("take whatever liquidity exists, at any price")
        void takeAnyPrice() {
            sell("100.00", 50);
            sell("200.00", 50);

            MatchResult result = marketBuy(100);

            assertThat(result.status()).isEqualTo(OrderStatus.FILLED);
            assertThat(result.fills()).extracting(Fill::price)
                    .containsExactly(Price.of("100.00"), Price.of("200.00"));
        }

        @Test
        @DisplayName("never rest - an unfilled remainder is cancelled")
        void neverRest() {
            sell("100.00", 40);

            MatchResult result = marketBuy(100);

            assertThat(result.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED_CANCELLED);
            assertThat(result.filledQuantity()).isEqualTo(40);
            assertThat(result.restingQuantity()).isZero();
            // Nothing was added to the bid side: a market order has no price to rest at.
            assertThat(book.bestBid()).isEmpty();
        }

        @Test
        @DisplayName("are cancelled outright against an empty book")
        void cancelledAgainstEmptyBook() {
            MatchResult result = marketSell(100);

            assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(result.fills()).isEmpty();
            assertThat(book.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("cannot be constructed with a limit price")
        void rejectPriceOnMarketOrder() {
            assertThatThrownBy(() -> new Order(OrderId.of("X"), AAPL, Side.BUY, OrderType.MARKET,
                    java.util.Optional.of(Price.of("100")), 100, TimeInForce.IMMEDIATE_OR_CANCEL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not state a limit price");
        }

        @Test
        @DisplayName("cannot be good-till-cancel, having no price to rest at")
        void rejectGtcMarketOrder() {
            assertThatThrownBy(() -> new Order(OrderId.of("X"), AAPL, Side.BUY, OrderType.MARKET,
                    java.util.Optional.empty(), 100, TimeInForce.GOOD_TILL_CANCEL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot rest");
        }
    }

    @Nested
    @DisplayName("immediate or cancel")
    class ImmediateOrCancel {

        @Test
        @DisplayName("fills what it can and cancels the rest")
        void fillsThenCancels() {
            sell("100.00", 40);

            MatchResult result = book.submit(Order.immediateOrCancel(
                    nextId(), AAPL, Side.BUY, Price.of("100.00"), 100));

            assertThat(result.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED_CANCELLED);
            assertThat(result.filledQuantity()).isEqualTo(40);
            assertThat(book.bestBid()).isEmpty();
        }

        @Test
        @DisplayName("respects its limit price, unlike a market order")
        void respectsLimit() {
            sell("105.00", 100);

            MatchResult result = book.submit(Order.immediateOrCancel(
                    nextId(), AAPL, Side.BUY, Price.of("100.00"), 100));

            assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(result.fills()).isEmpty();
        }
    }

    @Nested
    @DisplayName("cancellation")
    class Cancellation {

        @Test
        @DisplayName("removes a resting order")
        void cancelsRestingOrder() {
            OrderId id = nextId();
            book.submit(Order.limit(id, AAPL, Side.BUY, Price.of("100.00"), 500));

            assertThat(book.cancel(id)).isPresent();

            assertThat(book.contains(id)).isFalse();
            assertThat(book.restingOrderCount()).isZero();
            assertThat(book.bestBid()).isEmpty();
        }

        @Test
        @DisplayName("removes an order from the middle of a queue without disturbing the others")
        void cancelsFromMiddleOfQueue() {
            // The case an ArrayDeque handles in O(n) and the intrusive list in O(1).
            OrderId first = nextId();
            OrderId middle = nextId();
            OrderId last = nextId();
            book.submit(Order.limit(first, AAPL, Side.SELL, Price.of("100.00"), 100));
            book.submit(Order.limit(middle, AAPL, Side.SELL, Price.of("100.00"), 100));
            book.submit(Order.limit(last, AAPL, Side.SELL, Price.of("100.00"), 100));

            book.cancel(middle);

            assertThat(book.bestAskQuantity()).isEqualTo(200);
            MatchResult result = buy("100.00", 200);
            // Time priority among the survivors is unchanged.
            assertThat(result.fills()).extracting(Fill::restingOrderId)
                    .containsExactly(first, last);
        }

        @Test
        @DisplayName("removes the price level when the last order at it is cancelled")
        void removesEmptyLevel() {
            OrderId id = nextId();
            book.submit(Order.limit(id, AAPL, Side.BUY, Price.of("100.00"), 100));
            buy("99.00", 100);

            book.cancel(id);

            assertThat(book.priceLevelCount(Side.BUY)).isEqualTo(1);
            assertThat(book.bestBid()).contains(Price.of("99.00"));
        }

        @Test
        @DisplayName("cancelling an unknown order is empty, not an error")
        void unknownCancelIsEmpty() {
            // An ordinary race: the order may have filled a moment earlier.
            assertThat(book.cancel(OrderId.of("NEVER-EXISTED"))).isEmpty();
        }

        @Test
        @DisplayName("cancelling twice is harmless")
        void doubleCancelIsHarmless() {
            OrderId id = nextId();
            book.submit(Order.limit(id, AAPL, Side.BUY, Price.of("100.00"), 100));

            assertThat(book.cancel(id)).isPresent();
            assertThat(book.cancel(id)).isEmpty();
        }

        @Test
        @DisplayName("a filled order can no longer be cancelled")
        void filledOrderCannotBeCancelled() {
            OrderId id = nextId();
            book.submit(Order.limit(id, AAPL, Side.SELL, Price.of("100.00"), 100));
            buy("100.00", 100);

            assertThat(book.cancel(id)).isEmpty();
        }

        @Test
        @DisplayName("a partially filled order can be cancelled for its remainder")
        void cancelsPartiallyFilledRemainder() {
            OrderId id = nextId();
            book.submit(Order.limit(id, AAPL, Side.SELL, Price.of("100.00"), 100));
            buy("100.00", 30);

            assertThat(book.cancel(id)).isPresent();
            assertThat(book.bestAsk()).isEmpty();
        }
    }

    @Nested
    @DisplayName("depth")
    class Depth {

        @Test
        @DisplayName("reports aggregated levels, best price first")
        void reportsAggregatedLevels() {
            sell("102.00", 300);
            sell("100.00", 100);
            sell("100.00", 50);
            sell("101.00", 200);

            List<OrderBook.DepthEntry> depth = book.depth(Side.SELL, 10);

            assertThat(depth).extracting(OrderBook.DepthEntry::price).containsExactly(
                    Price.of("100.00"), Price.of("101.00"), Price.of("102.00"));
            assertThat(depth).extracting(OrderBook.DepthEntry::quantity)
                    .containsExactly(150L, 200L, 300L);
            assertThat(depth.get(0).orderCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("bids are reported highest first")
        void bidsDescend() {
            buy("98.00", 100);
            buy("100.00", 100);
            buy("99.00", 100);

            assertThat(book.depth(Side.BUY, 10)).extracting(OrderBook.DepthEntry::price)
                    .containsExactly(Price.of("100.00"), Price.of("99.00"), Price.of("98.00"));
        }

        @Test
        @DisplayName("is capped at the requested number of levels")
        void capsAtRequestedLevels() {
            for (int i = 1; i <= 20; i++) {
                sell(String.valueOf(100 + i), 100);
            }

            assertThat(book.depth(Side.SELL, 5)).hasSize(5);
        }

        @Test
        @DisplayName("rejects a non-positive level count")
        void rejectsBadLevelCount() {
            assertThatThrownBy(() -> book.depth(Side.BUY, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("the book is never crossed after a submission")
        void neverCrossed() {
            sell("100.00", 100);
            buy("105.00", 100);   // would cross, so matches instead

            assertThat(book.isCrossed()).isFalse();
            assertThat(book.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("an aggressive order consumes liquidity rather than sitting across it")
        void aggressiveOrderDoesNotRestAcrossTheSpread() {
            sell("100.00", 50);

            buy("101.00", 100);

            // 50 traded; the remaining 50 rests at 101, which is now the best bid with no
            // ask to cross.
            assertThat(book.bestBid()).contains(Price.of("101.00"));
            assertThat(book.bestAsk()).isEmpty();
            assertThat(book.isCrossed()).isFalse();
        }

        @Test
        @DisplayName("rejects an order for a different instrument")
        void rejectsWrongInstrument() {
            Order wrong = Order.limit(nextId(), InstrumentId.of("MSFT"), Side.BUY,
                    Price.of("100"), 100);

            assertThatThrownBy(() -> book.submit(wrong))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("but this book is for AAPL");
        }

        @Test
        @DisplayName("rejects a duplicate resting order id")
        void rejectsDuplicateId() {
            OrderId id = nextId();
            book.submit(Order.limit(id, AAPL, Side.BUY, Price.of("100.00"), 100));

            assertThatThrownBy(() -> book.submit(
                    Order.limit(id, AAPL, Side.BUY, Price.of("101.00"), 100)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already resting");
        }

        @Test
        @DisplayName("rejects a non-positive order quantity")
        void rejectsNonPositiveQuantity() {
            assertThatThrownBy(() -> Order.limit(nextId(), AAPL, Side.BUY, Price.of("100"), 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
        }

        @Test
        @DisplayName("fill sequence numbers increase monotonically")
        void sequenceIncreases() {
            sell("100.00", 100);
            sell("101.00", 100);

            MatchResult result = buy("101.00", 200);
            List<Fill> fills = result.fills();

            assertThat(fills.get(0).sequence()).isLessThan(fills.get(1).sequence());
        }
    }
}
