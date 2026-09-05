package com.mercury.benchmarks;

import com.mercury.core.id.InstrumentId;
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
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The benchmark that justifies the data structure.
 *
 * <p>{@code OrderBook}'s documentation claims cancellation is O(1) while the obvious
 * {@link NaiveOrderBook} is O(n). A complexity table is a claim; this measures it.
 *
 * <p>Both implementations cancel a single order from the <em>middle</em> of a book of
 * varying size. The middle matters: an order at the front is cheap to remove from any
 * structure, and it is the interior case that separates an intrusive linked list from an
 * array. It is also the realistic case - in live markets most orders are cancelled rather
 * than filled, and rarely from the front of the queue.
 *
 * <p>The expected shape is flat for the indexed book and linear for the naive one. What is
 * genuinely worth reporting is the crossover: at small sizes the array is likely to
 * <em>win</em>, because a contiguous scan is cache-friendly while pointer-chasing is not.
 * Finding where that stops being true is the point of running this rather than assuming it.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class CancellationScalingBenchmark {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");

    @Param({"100", "1000", "10000", "50000"})
    public int bookSize;

    private List<Order> orders;
    private OrderId victim;

    private OrderBook indexedBook;
    private NaiveOrderBook naiveBook;

    @Setup(Level.Trial)
    public void generateOrders() {
        orders = OrderBookBenchmark.BenchmarkOrders.buildLadder(
                AAPL, Side.BUY, bookSize, Math.max(1, bookSize / 100));
        // Deliberately the middle of the book, not the front.
        victim = orders.get(bookSize / 2).id();
    }

    @Setup(Level.Invocation)
    public void rebuildBooks() {
        indexedBook = new OrderBook(AAPL);
        naiveBook = new NaiveOrderBook();
        for (Order order : orders) {
            indexedBook.submit(order);
            naiveBook.add(order);
        }
    }

    /** Expected flat: a hash lookup plus four pointer writes, whatever the book size. */
    @Benchmark
    public void indexedCancel(Blackhole blackhole) {
        blackhole.consume(indexedBook.cancel(victim));
    }

    /** Expected linear: scan to find the order, then shift the tail of the array down. */
    @Benchmark
    public void naiveCancel(Blackhole blackhole) {
        blackhole.consume(naiveBook.cancel(victim));
    }

    /** Expected flat: the best level is cached. */
    @Benchmark
    public void indexedBestBid(Blackhole blackhole) {
        blackhole.consume(indexedBook.bestBid());
    }

    /** Expected linear: every query walks the whole side. */
    @Benchmark
    public void naiveBestBid(Blackhole blackhole) {
        blackhole.consume(naiveBook.bestBid());
    }
}
