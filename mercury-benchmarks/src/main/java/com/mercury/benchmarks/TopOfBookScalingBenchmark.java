package com.mercury.benchmarks;

import com.mercury.core.id.InstrumentId;
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
 * How the cost of reading top of book scales with depth.
 *
 * <p>Reading the best bid is the single most frequent operation against a live book -
 * every incoming order consults it, and every market-data tick publishes it. The indexed
 * book caches the best level so the read is O(1); the naive book has nothing to cache
 * against, so every read walks the whole side.
 *
 * <p>These benchmarks are read-only, so the book is built once per trial. That is the
 * reason they live here rather than beside the cancellation benchmarks, which consume the
 * book and need it rebuilt per invocation: JMH applies {@code @Setup} to every benchmark
 * in a state class, and a nanosecond read behind a millisecond rebuild cannot be measured.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class TopOfBookScalingBenchmark {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");

    @Param({"1000", "10000", "50000"})
    public int bookSize;

    private OrderBook indexedBook;
    private NaiveOrderBook naiveBook;

    @Setup(Level.Trial)
    public void buildBooks() {
        List<Order> orders = OrderBookBenchmark.buildLadder(
                AAPL, Side.BUY, bookSize, Math.max(1, bookSize / 100));

        indexedBook = new OrderBook(AAPL);
        naiveBook = new NaiveOrderBook();
        for (Order order : orders) {
            indexedBook.submit(order);
            naiveBook.add(order);
        }
    }

    /** Expected flat: the best level reference is maintained on every mutation. */
    @Benchmark
    public void indexedBestBid(Blackhole blackhole) {
        blackhole.consume(indexedBook.bestBid());
    }

    /** Expected linear: nothing is ordered, so finding the maximum means visiting everything. */
    @Benchmark
    public void naiveBestBid(Blackhole blackhole) {
        blackhole.consume(naiveBook.bestBid());
    }
}
