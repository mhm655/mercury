package com.mercury.benchmarks;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.OrderId;
import com.mercury.matching.Order;
import com.mercury.matching.OrderBook;
import com.mercury.matching.Side;
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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The benchmark that justifies the data structure.
 *
 * <p>{@code OrderBook} documents cancellation as O(1) while the obvious
 * {@link NaiveOrderBook} is O(n). A complexity table is a claim; this measures it.
 *
 * <h2>What is measured, and why in batches</h2>
 * Each invocation cancels {@value #CANCELS_PER_INVOCATION} orders drawn from the
 * <em>middle</em> of a book of {@code bookSize}, then the book is rebuilt.
 *
 * <p>The middle matters. An order at the front of a queue is cheap to remove from any
 * structure; it is the interior case that separates an intrusive linked list from an array,
 * and it is the realistic case, since in live markets most orders are cancelled rather than
 * filled and rarely from the front.
 *
 * <p>Batching matters too. A single cancellation takes nanoseconds while rebuilding the book
 * takes milliseconds, and JMH cannot cleanly separate setup from measurement at that ratio.
 * Cancelling a batch that is small relative to the book keeps the book near {@code bookSize}
 * throughout while giving each invocation enough work to measure honestly.
 *
 * <h2>What to expect</h2>
 * Flat for the indexed book, linear for the naive one - but the interesting number is the
 * crossover. At small sizes the array should genuinely win: a contiguous scan is
 * cache-friendly, while pointer-chasing through a tree and a linked list is not. Finding
 * where that stops being true is the reason to run this rather than assume it.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class CancellationScalingBenchmark {

    static final int CANCELS_PER_INVOCATION = 500;

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");

    @Param({"1000", "10000", "50000"})
    public int bookSize;

    private List<Order> orders;

    /** Ids drawn from the middle of the book, so removal is always an interior operation. */
    private List<OrderId> victims;

    private OrderBook indexedBook;
    private NaiveOrderBook naiveBook;

    @Setup(Level.Trial)
    public void generateOrders() {
        orders = OrderBookBenchmark.buildLadder(
                AAPL, Side.BUY, bookSize, Math.max(1, bookSize / 100));

        int start = Math.max(0, (bookSize - CANCELS_PER_INVOCATION) / 2);
        List<OrderId> chosen = new ArrayList<>(CANCELS_PER_INVOCATION);
        for (int i = 0; i < CANCELS_PER_INVOCATION; i++) {
            chosen.add(orders.get((start + i) % bookSize).id());
        }
        victims = List.copyOf(chosen);
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

    /** Expected flat: a hash lookup plus a handful of pointer writes, whatever the size. */
    @Benchmark
    @OperationsPerInvocation(CANCELS_PER_INVOCATION)
    public void indexedCancel(Blackhole blackhole) {
        for (OrderId id : victims) {
            blackhole.consume(indexedBook.cancel(id));
        }
    }

    /** Expected linear: scan to find each order, then shift the tail of the array down. */
    @Benchmark
    @OperationsPerInvocation(CANCELS_PER_INVOCATION)
    public void naiveCancel(Blackhole blackhole) {
        for (OrderId id : victims) {
            blackhole.consume(naiveBook.cancel(id));
        }
    }
}
