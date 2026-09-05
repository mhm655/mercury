package com.mercury.benchmarks;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.OrderId;
import com.mercury.core.money.Price;
import com.mercury.matching.Order;
import com.mercury.matching.OrderBook;
import com.mercury.matching.Side;
import java.math.BigDecimal;
import java.util.ArrayList;
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
 * Non-destructive matching-engine benchmarks: operations that leave the book unchanged, plus
 * insertion into a fresh book.
 *
 * <h2>Why this is split from {@link DestructiveOrderBookBenchmark}</h2>
 * Cancellation and matching consume the book, so they need it rebuilt before every
 * invocation. JMH applies {@code @Setup} to every benchmark in the same state class, so
 * mixing them would rebuild a {@value #ORDER_COUNT}-order book before each nanosecond-scale
 * top-of-book read - setup dwarfing the measurement by six orders of magnitude and making
 * the run effectively never finish. Splitting the two kinds of benchmark into separate state
 * classes is the fix.
 *
 * <h2>Methodology</h2>
 * The book holds {@value #ORDER_COUNT} orders across {@value #PRICE_LEVELS} distinct prices,
 * which is a realistic shape: real books are deep at few prices rather than one order per
 * price. Orders are pre-built in trial setup so allocation and id creation are excluded, and
 * results are reported per order via {@link OperationsPerInvocation} so the figures are
 * directly comparable.
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

    static final InstrumentId AAPL = InstrumentId.of("AAPL");

    private List<Order> restingOrders;

    /** Populated once and never mutated by the benchmarks in this class. */
    private OrderBook populatedBook;

    @Setup(Level.Trial)
    public void setUp() {
        restingOrders = buildLadder(AAPL, Side.BUY, ORDER_COUNT, PRICE_LEVELS);
        populatedBook = new OrderBook(AAPL);
        for (Order order : restingOrders) {
            populatedBook.submit(order);
        }
    }

    /**
     * Inserting orders that never cross, measuring the resting path alone: a TreeMap lookup
     * for the level plus an O(1) append to its queue.
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
     * Reading top of book.
     *
     * <p>Should be flat regardless of depth, because the best level is cached rather than
     * looked up. If this scales with book size, the cache is broken.
     */
    @Benchmark
    public void topOfBook(Blackhole blackhole) {
        blackhole.consume(populatedBook.bestBid());
        blackhole.consume(populatedBook.bestBidQuantity());
    }

    /** Aggregated depth for the top ten levels, as a market-data display would request it. */
    @Benchmark
    public List<OrderBook.DepthEntry> depthTenLevels() {
        return populatedBook.depth(Side.BUY, 10);
    }

    static final long QUANTITY_PER_ORDER = 100;

    /**
     * Builds {@code count} orders spread evenly over {@code levels} distinct prices.
     *
     * <p>Bids sit below 1000 and asks above it, so a ladder never crosses itself and
     * insertion benchmarks measure resting rather than matching.
     */
    static List<Order> buildLadder(InstrumentId instrument, Side side, int count, int levels) {
        List<Order> orders = new ArrayList<>(count);
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
