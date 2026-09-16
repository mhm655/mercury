package com.mercury.benchmarks;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Price;
import com.mercury.core.time.SimulationClock;
import com.mercury.event.EventBus;
import com.mercury.execution.BookConcurrency;
import com.mercury.execution.OrderBookInstruction;
import com.mercury.execution.OrderBookVenue;
import com.mercury.execution.TradeIdGenerator;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.instrument.Stock;
import com.mercury.matching.Side;
import com.mercury.portfolio.InstrumentCatalog;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Twelve threads submitting orders to a venue: what changes when they share one book, and what
 * changes when each book gets its own writer thread.
 *
 * <p>{@code docs/BENCHMARKS.md} section 4 stated the claim this measures - "the concurrency
 * model is single-writer per book, so these figures are the per-book ceiling; parallelism comes
 * from running many instruments' books at once." That was a design claim with no measurement
 * behind it. Two knobs test it:
 *
 * <ul>
 *   <li>{@code instruments}: 1 means every caller wants the same book, so nothing can run in
 *       parallel no matter how it is implemented; 12 means they spread across books.</li>
 *   <li>{@code concurrency}: {@code INLINE} matches on the calling thread under a per-book
 *       lock; {@code THREAD_PER_BOOK} hands a command to the thread that owns the book and
 *       waits for the result.</li>
 * </ul>
 *
 * <p>Each invocation submits a sell and then a buy at the same price, so the two cross and the
 * book stays shallow for the whole run - the measurement is matching throughput, not the cost
 * of an ever-deepening book, and memory does not grow over a long run.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@Threads(12)
@State(Scope.Benchmark)
public class VenueConcurrencyBenchmark {

    private static final CounterpartyId BUYER = CounterpartyId.of("CPTY-BUYER");
    private static final CounterpartyId SELLER = CounterpartyId.of("CPTY-SELLER");
    private static final SimulationClock CLOCK = SimulationClock.fixedAt(LocalDate.of(2026, 3, 2));
    private static final Price PRICE = Price.of("100.00");

    @Param({"1", "12"})
    public int instruments;

    @Param({"INLINE", "THREAD_PER_BOOK"})
    public BookConcurrency concurrency;

    private OrderBookVenue venue;
    private List<InstrumentId> tradable;
    private final AtomicInteger nextCallerIndex = new AtomicInteger();

    /** Which instrument one caller thread trades - fixed per thread, as a real client would be. */
    @State(Scope.Thread)
    public static class Caller {

        private InstrumentId instrumentId;

        @Setup(Level.Trial)
        public void assignInstrument(VenueConcurrencyBenchmark benchmark) {
            int index = benchmark.nextCallerIndex.getAndIncrement();
            this.instrumentId = benchmark.tradable.get(index % benchmark.tradable.size());
        }
    }

    @Setup(Level.Trial)
    public void setUp() {
        List<FinancialInstrument> catalog = new ArrayList<>();
        tradable = new ArrayList<>();
        for (int i = 0; i < instruments; i++) {
            Stock stock = Stock.of("SYM" + i, Currency.USD);
            catalog.add(stock);
            tradable.add(stock.id());
        }
        venue = new OrderBookVenue(new TradeIdGenerator("TRD-"), InstrumentCatalog.of(catalog),
                EventBus.ignoring(), concurrency);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        venue.close();
    }

    @Benchmark
    @OperationsPerInvocation(2)
    public void submitCrossingPair(Caller caller, Blackhole blackhole) {
        blackhole.consume(venue.execute(OrderBookInstruction.limit(
                caller.instrumentId, Side.SELL, PRICE, 10, SELLER), CLOCK));
        blackhole.consume(venue.execute(OrderBookInstruction.limit(
                caller.instrumentId, Side.BUY, PRICE, 10, BUYER), CLOCK));
    }
}
