package com.mercury.benchmarks;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.OrderId;
import com.mercury.core.money.Price;
import com.mercury.matching.Order;
import com.mercury.matching.OrderBook;
import com.mercury.matching.Side;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Throughput benchmarks for the matching engine.
 *
 * <h2>Methodology</h2>
 * Each benchmark works on a book of {@value #ORDER_COUNT} orders spread across
 * {@value #PRICE_LEVELS} distinct price levels, which is a realistic shape - real books are
 * deep at few prices, not one order per price.
 *
 * <p>Orders are pre-generated in {@code @Setup} so that object construction and id creation
 * are not measured; only the book operation is. Results are reported per order via
 * {@link OperationsPerInvocation}, so the numbers are directly comparable across benchmarks.
 *
 * <p>Fresh state is built per invocation where the operation is destructive (cancellation
 * and matching consume the book). Invocation-level setup is unreliable for very short
 * operations, but each batch here runs for milliseconds, well above that threshold.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class OrderBookBenchmark {

    static final int ORDER_COUNT = 100_000;
    static final int PRICE_LEVELS = 1_000;

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");

    /** Pre-built orders, so allocation is excluded from the measurement. */
    private List<Order> restingOrders;
    private List<OrderId> orderIds;

    /** A book already populated, rebuilt before each destructive benchmark. */
    private OrderBook populatedBook;

    @Setup(Level.Trial)
    public void generateOrders() {
        restingOrders = BenchmarkOrders.buildLadder(AAPL, Side.BUY, ORDER_COUNT, PRICE_LEVELS);
        orderIds = restingOrders.stream().map(Order::id).toList();
    }

    @Setup(Level.Invocation)
    public void rebuildBook() {
        populatedBook = new OrderBook(AAPL);
        for (Order order : restingOrders) {
            populatedBook.submit(order);
        }
    }

    /**
     * Inserting orders that never cross, so this measures the resting path alone: a TreeMap
     * lookup for the level plus an O(1) append to its queue.
     */
    @Benchmark
    @OperationsPerInvocation(ORDER_COUNT)
    public OrderBook insertOrders() {
        OrderBook book = new OrderBook(AAPL);
        for (Order order : restingOrders) {
            book.submit(order);
        }
        return book;
    }

    /**
     * Cancelling every resting order.
     *
     * <p>The headline claim: O(1) per cancellation via the id-to-node map and the intrusive
     * unlink, regardless of how deep the queue is or where in it the order sits.
     */
    @Benchmark
    @OperationsPerInvocation(ORDER_COUNT)
    public void cancelOrders(Blackhole blackhole) {
        for (OrderId id : orderIds) {
            blackhole.consume(populatedBook.cancel(id));
        }
    }

    /**
     * One enormous aggressor sweeping the entire book.
     *
     * <p>Measures the matching path in isolation: repeated head-of-level fills, with one
     * TreeMap removal each time a level empties.
     */
    @Benchmark
    @OperationsPerInvocation(ORDER_COUNT)
    public int matchSweep() {
        Order sweeper = Order.market(OrderId.of("SWEEP"), AAPL, Side.SELL,
                (long) ORDER_COUNT * BenchmarkOrders.QUANTITY_PER_ORDER);
        return populatedBook.submit(sweeper).fills().size();
    }

    /**
     * Reading top of book on a populated book.
     *
     * <p>Should be flat regardless of depth, because the best level is cached rather than
     * looked up. If this scales with book size, the cache is broken.
     */
    @Benchmark
    public void topOfBook(Blackhole blackhole) {
        blackhole.consume(populatedBook.bestBid());
        blackhole.consume(populatedBook.bestBidQuantity());
    }

    /** Aggregated depth for the top ten levels, as a display would request it. */
    @Benchmark
    public List<OrderBook.DepthEntry> depthTenLevels() {
        return populatedBook.depth(Side.BUY, 10);
    }

    /** Shared order generation, kept out of the measured path. */
    static final class BenchmarkOrders {

        static final long QUANTITY_PER_ORDER = 100;

        private BenchmarkOrders() {
        }

        /**
         * Builds {@code count} orders spread evenly over {@code levels} distinct prices.
         *
         * <p>Bids are placed below 1000 and asks above it, so a ladder never crosses itself
         * and the benchmark measures insertion rather than matching.
         */
        static List<Order> buildLadder(InstrumentId instrument, Side side, int count, int levels) {
            List<Order> orders = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int tick = i % levels;
                BigDecimal price = side.isBuy()
                        ? BigDecimal.valueOf(1000 - tick)
                        : BigDecimal.valueOf(1000 + tick + 1);
                orders.add(Order.limit(OrderId.of("O-" + i), instrument, side,
                        Price.of(price), QUANTITY_PER_ORDER));
            }
            return List.copyOf(orders);
        }
    }
}
