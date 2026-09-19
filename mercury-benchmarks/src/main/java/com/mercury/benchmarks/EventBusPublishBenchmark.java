package com.mercury.benchmarks;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.event.AsynchronousEventBus;
import com.mercury.event.EventBus;
import com.mercury.event.SynchronousEventBus;
import com.mercury.execution.TradeIdGenerator;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeExecuted;
import com.mercury.trade.TradeStatus;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * What {@code publish} costs its caller: {@link SynchronousEventBus} against
 * {@link AsynchronousEventBus}, against a fast subscriber and a slow one.
 *
 * <h2>Why this exists</h2>
 * The README's dead-weight table keeps {@code AsynchronousEventBus} "because the synchronous
 * default is only defensible if the asynchronous alternative actually exists to compare
 * against." Every other claim in {@code docs/BENCHMARKS.md} is a measurement; that one sentence
 * was still just an assertion. This is the benchmark that makes it one.
 *
 * <h2>The two knobs</h2>
 * <ul>
 *   <li>{@code subscriberCost}: {@code FAST} is what a subscriber like {@code LedgerKeeper}
 *       actually costs - booking one trade into an immutable ledger, sub-microsecond.
 *       {@code SLOW} simulates roughly the millisecond-scale cost {@code AsynchronousEventBus}'s
 *       own javadoc names as the reason it exists - "a subscriber that revalues a book in
 *       milliseconds" - via {@link Blackhole#consumeCPU}, not a real revaluation, because no
 *       subscriber in this codebase is actually that slow yet and inventing one only to delete
 *       it would be worse than naming the simulation for what it is.</li>
 *   <li>{@code busType}: {@code SYNCHRONOUS} delivers inline, so this benchmark's number for it
 *       <em>is</em> the subscriber's cost plus dispatch overhead. {@code ASYNCHRONOUS} queues
 *       and returns, so its number is queueing overhead alone, however slow the subscriber is -
 *       that gap, if there is one, is the entire case for the asynchronous bus existing.</li>
 * </ul>
 *
 * <p>Only {@code publish} is timed. Nothing here waits for delivery, so for the asynchronous bus
 * this measures exactly what its javadoc promises the caller - not being held up by a subscriber
 * - and for the synchronous one it measures exactly the cost that promise trades away.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class EventBusPublishBenchmark {

    public enum BusType {
        SYNCHRONOUS, ASYNCHRONOUS
    }

    public enum SubscriberCost {
        /** Roughly what {@code LedgerKeeper.accept} costs: book one trade, nothing else. */
        FAST,
        /** Simulates the millisecond-scale work {@code AsynchronousEventBus} was built for. */
        SLOW
    }

    /** Calibrated so SLOW costs low-single-digit milliseconds on a typical developer machine. */
    private static final long SLOW_TOKENS = 2_000_000L;

    @Param({"SYNCHRONOUS", "ASYNCHRONOUS"})
    public BusType busType;

    @Param({"FAST", "SLOW"})
    public SubscriberCost subscriberCost;

    private EventBus bus;
    private TradeExecuted event;

    @Setup(Level.Trial)
    public void setUp() {
        event = fixtureEvent();

        Consumer<TradeExecuted> subscriber = subscriberCost == SubscriberCost.SLOW
                ? e -> Blackhole.consumeCPU(SLOW_TOKENS)
                : e -> Blackhole.consumeCPU(1L);

        bus = switch (busType) {
            case SYNCHRONOUS -> new SynchronousEventBus();
            case ASYNCHRONOUS -> new AsynchronousEventBus();
        };
        bus.subscribe(TradeExecuted.class, subscriber);
    }

    /**
     * For {@code ASYNCHRONOUS}/{@code SLOW}, this can time out and fail one iteration: a
     * publisher this much faster than its subscriber enqueues far more work in a measurement
     * window than the dispatcher can drain afterwards, and JMH interrupts a {@code close()}
     * that is still blocked joining the dispatcher thread. That is the unbounded-queue gap
     * {@code AsynchronousEventBus}'s own javadoc names, reproduced rather than papered over -
     * see {@code docs/KNOWN_GAPS.md}. Left as a real failure rather than caught, because
     * swallowing it here would hide the exact thing this benchmark exists to show.
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        if (bus instanceof AsynchronousEventBus async) {
            async.close();
        }
    }

    @Benchmark
    public void publish() {
        bus.publish(event);
    }

    private static TradeExecuted fixtureEvent() {
        TradeIdGenerator tradeIds = new TradeIdGenerator("BENCH-TRD-");
        SimulationClock clock = SimulationClock.fixedAt(LocalDate.of(2026, 3, 2));
        Trade trade = Trade.newTrade(tradeIds.next(), InstrumentId.of("AAPL"),
                        CounterpartyId.of("CPTY-OWNER"), Quantity.of(100),
                        Money.of("19540.00", Currency.USD), clock.today(),
                        Optional.empty(), Optional.empty())
                .transitionTo(TradeStatus.VALIDATED, "matched on the order book", clock)
                .transitionTo(TradeStatus.BOOKED, "booked to the ledger", clock)
                .transitionTo(TradeStatus.EXECUTED, "matched in full", clock);
        return new TradeExecuted(trade);
    }
}
