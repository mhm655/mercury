package com.mercury.benchmarks;

import com.mercury.core.id.OrderId;
import com.mercury.matching.Order;
import com.mercury.matching.OrderBook;
import com.mercury.matching.Side;
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
 * Benchmarks for operations that consume the book, and therefore need it rebuilt before each
 * invocation.
 *
 * <p>Separate from {@link OrderBookBenchmark} because JMH applies {@code @Setup} to every
 * benchmark sharing a state class. Rebuilding a 100,000-order book before a nanosecond-scale
 * read would leave the setup dwarfing the measurement; keeping the two kinds apart means each
 * gets the setup level it actually needs.
 *
 * <p>Invocation-level setup is unreliable for very short operations - JMH cannot cleanly
 * separate setup from measurement below roughly a millisecond. Both benchmarks here process
 * 100,000 orders per invocation and run for milliseconds, comfortably above that floor.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class DestructiveOrderBookBenchmark {

    private List<Order> restingOrders;
    private List<OrderId> orderIds;

    private OrderBook book;

    @Setup(Level.Trial)
    public void generateOrders() {
        restingOrders = OrderBookBenchmark.buildLadder(
                OrderBookBenchmark.AAPL, Side.BUY,
                OrderBookBenchmark.ORDER_COUNT, OrderBookBenchmark.PRICE_LEVELS);
        orderIds = restingOrders.stream().map(Order::id).toList();
    }

    @Setup(Level.Invocation)
    public void rebuildBook() {
        book = new OrderBook(OrderBookBenchmark.AAPL);
        for (Order order : restingOrders) {
            book.submit(order);
        }
    }

    /**
     * Cancelling every resting order.
     *
     * <p>The headline claim: O(1) per cancellation via the id-to-node map and the intrusive
     * unlink, regardless of queue depth or the order's position within it.
     */
    @Benchmark
    @OperationsPerInvocation(OrderBookBenchmark.ORDER_COUNT)
    public void cancelOrders(Blackhole blackhole) {
        for (OrderId id : orderIds) {
            blackhole.consume(book.cancel(id));
        }
    }

    /**
     * One enormous aggressor sweeping the entire book.
     *
     * <p>Measures the matching path in isolation: repeated head-of-level fills, with a single
     * TreeMap removal each time a level empties.
     */
    @Benchmark
    @OperationsPerInvocation(OrderBookBenchmark.ORDER_COUNT)
    public int matchSweep() {
        Order sweeper = Order.market(OrderId.of("SWEEP"), OrderBookBenchmark.AAPL, Side.SELL,
                (long) OrderBookBenchmark.ORDER_COUNT * OrderBookBenchmark.QUANTITY_PER_ORDER);
        return book.submit(sweeper).fills().size();
    }
}
