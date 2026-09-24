package com.mercury.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.event.EventBus;
import com.mercury.event.SynchronousEventBus;
import com.mercury.instrument.FxForward;
import com.mercury.instrument.Stock;
import com.mercury.matching.Side;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeExecuted;
import com.mercury.trade.TradeStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderBookVenueTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final CounterpartyId BUYER = CounterpartyId.of("CPTY-BUYER");
    private static final CounterpartyId SELLER = CounterpartyId.of("CPTY-SELLER");
    private static final SimulationClock CLOCK = SimulationClock.fixedAt(LocalDate.of(2026, 3, 2));

    private static OrderBookVenue newVenue() {
        InstrumentCatalog catalog = InstrumentCatalog.of(Stock.of("AAPL", Currency.USD));
        return new OrderBookVenue(new TradeIdGenerator("TRD-"), catalog);
    }

    @Test
    void anUnfilledRestingOrderProducesNoTrades() {
        OrderBookVenue venue = newVenue();

        List<Trade> trades = venue.execute(OrderBookInstruction.limit(
                AAPL, Side.BUY, Price.of("100.00"), 100, BUYER), CLOCK);

        assertThat(trades).isEmpty();
    }

    @Test
    void aCrossProducesOneExecutedTradePerSide() {
        OrderBookVenue venue = newVenue();
        venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 100, SELLER),
                CLOCK);

        List<Trade> trades = venue.execute(
                OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("100.00"), 100, BUYER), CLOCK);

        assertThat(trades).hasSize(2);
        assertThat(trades).allMatch(t -> t.status() == TradeStatus.EXECUTED);
        assertThat(trades).allMatch(t -> t.counterparty().isEmpty());

        Trade buyerTrade = trades.stream().filter(t -> t.owner().equals(BUYER)).findFirst()
                .orElseThrow();
        Trade sellerTrade = trades.stream().filter(t -> t.owner().equals(SELLER)).findFirst()
                .orElseThrow();

        assertThat(buyerTrade.delta()).isEqualTo(Quantity.of(100));
        assertThat(buyerTrade.consideration()).isEqualTo(Money.of("10000.00", Currency.USD));
        assertThat(sellerTrade.delta()).isEqualTo(Quantity.of(-100));
        assertThat(sellerTrade.consideration()).isEqualTo(Money.of("-10000.00", Currency.USD));

        assertThat(buyerTrade.id()).isNotEqualTo(sellerTrade.id());
    }

    @Test
    void aSweepAcrossLevelsProducesOneTradePairPerFillAndReconciles() {
        OrderBookVenue venue = newVenue();
        venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 100, SELLER),
                CLOCK);
        venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("101.00"), 100, SELLER),
                CLOCK);

        List<Trade> trades = venue.execute(
                OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("101.00"), 150, BUYER), CLOCK);

        // Two fills (100 @ 100.00, then 50 @ 101.00), two trades each.
        assertThat(trades).hasSize(4);

        Money buyerTotal = trades.stream().filter(t -> t.owner().equals(BUYER))
                .map(Trade::consideration).reduce(Money.zero(Currency.USD), Money::plus);
        Money sellerTotal = trades.stream().filter(t -> t.owner().equals(SELLER))
                .map(Trade::consideration).reduce(Money.zero(Currency.USD), Money::plus);

        // 100 * 100.00 + 50 * 101.00 = 10000.00 + 5050.00 = 15050.00, both ways.
        assertThat(buyerTotal).isEqualTo(Money.of("15050.00", Currency.USD));
        assertThat(sellerTotal).isEqualTo(Money.of("-15050.00", Currency.USD));
    }

    @Test
    void theFillThenResubmitPatternThatUsedToReuseIdsNowJustWorks() {
        // The exact shape of G-1 (docs/KNOWN_GAPS.md): an order fills completely, then a new
        // one is submitted while the old one is gone from the book. Repeated many times, this
        // used to risk a reused OrderId once client-supplied ids were in play. The venue mints
        // its own, so there is nothing to reuse - proven here by simply running it a lot and
        // getting fully consistent results every time.
        OrderBookVenue venue = newVenue();
        int iterations = 2_000;

        for (int i = 0; i < iterations; i++) {
            venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 10, SELLER),
                    CLOCK);
            List<Trade> trades = venue.execute(
                    OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("100.00"), 10, BUYER), CLOCK);

            assertThat(trades).hasSize(2);
            assertThat(trades).allMatch(t -> t.status() == TradeStatus.EXECUTED);
        }
    }

    @Test
    void aMarketOrderTakesWhateverLiquidityExists() {
        OrderBookVenue venue = newVenue();
        venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 100, SELLER),
                CLOCK);

        List<Trade> trades = venue.execute(
                OrderBookInstruction.market(AAPL, Side.BUY, 100, BUYER), CLOCK);

        assertThat(trades).hasSize(2);
        assertThat(trades).allMatch(t -> t.status() == TradeStatus.EXECUTED);
    }

    @Test
    void anImmediateOrCancelFillsWhatItCanAndRestsNothing() {
        OrderBookVenue venue = newVenue();
        venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 40, SELLER),
                CLOCK);

        List<Trade> trades = venue.execute(OrderBookInstruction.immediateOrCancel(
                AAPL, Side.BUY, Price.of("100.00"), 100, BUYER), CLOCK);

        // Only the 40 that could fill immediately becomes trades; the other 60 is cancelled,
        // not left resting - so it produces no Trade of its own.
        assertThat(trades).hasSize(2);
        Trade buyerTrade = trades.stream().filter(t -> t.owner().equals(BUYER)).findFirst()
                .orElseThrow();
        assertThat(buyerTrade.delta()).isEqualTo(Quantity.of(40));
    }

    @Test
    void anUnknownInstrumentIsRefusedBeforeItCanConsumeRestingLiquidity() {
        // The currency used to be looked up only after matching, so an unknown instrument's
        // sell rested, and the buy that crossed it threw with the sell already filled and both
        // sides' trades lost. It is now refused before an order ever reaches a book.
        InstrumentId unknown = InstrumentId.of("NOT-IN-CATALOG");
        OrderBookVenue venue = newVenue();

        assertThatThrownBy(() -> venue.execute(
                OrderBookInstruction.limit(unknown, Side.BUY, Price.of("100.00"), 100, BUYER), CLOCK))
                .isInstanceOf(InstrumentCatalog.UnknownInstrumentException.class);
    }

    @Test
    void anOverTheCounterInstrumentIsRefused() {
        FxForward forward = FxForward.buy("FWD-EURUSD", CurrencyPair.of(Currency.EUR, Currency.USD),
                "1000000", "1.10", LocalDate.of(2027, 3, 2));
        OrderBookVenue venue = new OrderBookVenue(new TradeIdGenerator("TRD-"), InstrumentCatalog.of(forward));

        assertThatThrownBy(() -> venue.execute(OrderBookInstruction.limit(
                forward.id(), Side.BUY, Price.of("100.00"), 1, BUYER), CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not exchange traded");
    }

    @Test
    void concurrentSubmissionsKeepEveryFillBalanced() throws Exception {
        // OrderBook is single-writer by design; the venue is where concurrent callers - a web
        // tier, say - arrive, so the venue is what has to serialise them. Every unit bought
        // must be a unit sold, and nothing may throw from a corrupted book.
        OrderBookVenue venue = newVenue();
        int threads = 16;
        int ordersPerThread = 500;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<List<Trade>>> results = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            CounterpartyId participant = CounterpartyId.of("CPTY-" + t);
            Side side = t % 2 == 0 ? Side.BUY : Side.SELL;
            results.add(pool.submit(() -> {
                start.await();
                List<Trade> mine = new java.util.ArrayList<>();
                for (int i = 0; i < ordersPerThread; i++) {
                    mine.addAll(venue.execute(OrderBookInstruction.limit(
                            AAPL, side, Price.of("100.00"), 1 + i % 7, participant), CLOCK));
                }
                return mine;
            }));
        }
        start.countDown();

        java.math.BigDecimal bought = java.math.BigDecimal.ZERO;
        java.math.BigDecimal sold = java.math.BigDecimal.ZERO;
        for (java.util.concurrent.Future<List<Trade>> result : results) {
            for (Trade trade : result.get()) {
                if (trade.delta().isLong()) {
                    bought = bought.add(trade.delta().value());
                } else {
                    sold = sold.add(trade.delta().value().negate());
                }
            }
        }
        pool.shutdown();

        assertThat(bought).isPositive().isEqualByComparingTo(sold);
    }

    @Test
    void anOrderTheBookRefusesLeavesNoOwnerBehind() {
        OrderBookVenue venue = newVenue();
        venue.execute(OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("100.00"), Long.MAX_VALUE, BUYER), CLOCK);

        assertThatThrownBy(() -> venue.execute(
                OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("100.00"), 1, BUYER), CLOCK))
                .isInstanceOf(ArithmeticException.class);
        assertThat(venue.trackedOwnerCount()).isEqualTo(1);
    }

    @Test
    void ownersAreForgottenOnceTheirOrdersLeaveTheBook() {
        // The venue remembered the owner of every order it had ever submitted, for its whole
        // lifetime. It only needs them while an order can still be filled.
        OrderBookVenue venue = newVenue();
        for (int i = 0; i < 1_000; i++) {
            venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 10, SELLER), CLOCK);
            venue.execute(OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("100.00"), 10, BUYER), CLOCK);
        }
        venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("101.00"), 10, SELLER), CLOCK);

        assertThat(venue.trackedOwnerCount()).isEqualTo(1);
    }


    @Test
    void everyTradeItMintsIsPublished() {
        // Both sides of the fill, so a subscriber sees the market maker's trade as well as
        // ours - which is why LedgerKeeper filters by owner rather than booking everything.
        SynchronousEventBus bus = new SynchronousEventBus();
        List<TradeExecuted> announced = new ArrayList<>();
        bus.subscribe(TradeExecuted.class, announced::add);
        OrderBookVenue venue = new OrderBookVenue(new TradeIdGenerator("TRD-"),
                InstrumentCatalog.of(Stock.of("AAPL", Currency.USD)), bus);

        venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 100, SELLER), CLOCK);
        List<Trade> trades = venue.execute(
                OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("100.00"), 100, BUYER), CLOCK);

        assertThat(announced).extracting(TradeExecuted::trade).containsExactlyElementsOf(trades);
        assertThat(announced).extracting(event -> event.trade().owner())
                .containsExactlyInAnyOrder(BUYER, SELLER);
    }

    @Test
    void anOrderThatOnlyRestsPublishesNothing() {
        SynchronousEventBus bus = new SynchronousEventBus();
        List<TradeExecuted> announced = new ArrayList<>();
        bus.subscribe(TradeExecuted.class, announced::add);
        OrderBookVenue venue = new OrderBookVenue(new TradeIdGenerator("TRD-"),
                InstrumentCatalog.of(Stock.of("AAPL", Currency.USD)), bus);

        venue.execute(OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("100.00"), 100, BUYER), CLOCK);

        assertThat(announced).isEmpty();
    }

    @Nested
    @DisplayName("single-writer books")
    class SingleWriter {

        private static final InstrumentId MSFT = InstrumentId.of("MSFT");

        private OrderBookVenue threadedVenue(EventBus events) {
            InstrumentCatalog catalog = InstrumentCatalog.of(
                    Stock.of("AAPL", Currency.USD), Stock.of("MSFT", Currency.USD));
            return new OrderBookVenue(new TradeIdGenerator("TRD-"), catalog, events,
                    BookConcurrency.THREAD_PER_BOOK);
        }

        @Test
        @DisplayName("produces exactly the trades the inline venue produces")
        void sameTradesAsInline() {
            // The whole claim of BookConcurrency: it changes who matches, not what matched.
            List<OrderBookInstruction> script = List.of(
                    OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 100, SELLER),
                    OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("101.00"), 100, SELLER),
                    OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("101.00"), 150, BUYER),
                    OrderBookInstruction.market(AAPL, Side.SELL, 50, SELLER));

            List<String> inline = new ArrayList<>();
            OrderBookVenue plain = newVenue();
            script.forEach(instruction -> plain.execute(instruction, CLOCK)
                    .forEach(trade -> inline.add(describe(trade))));

            List<String> threaded = new ArrayList<>();
            try (OrderBookVenue venue = threadedVenue(EventBus.ignoring())) {
                script.forEach(instruction -> venue.execute(instruction, CLOCK)
                        .forEach(trade -> threaded.add(describe(trade))));
            }

            assertThat(threaded).isEqualTo(inline).isNotEmpty();
        }

        /** Trade ids are minted per venue, so compare everything else. */
        private String describe(Trade trade) {
            return trade.instrumentId() + " " + trade.owner() + " " + trade.delta() + " "
                    + trade.consideration() + " " + trade.status();
        }

        @Test
        @DisplayName("each book is matched by one thread, and different books by different ones")
        void oneWriterPerBook() {
            // The single-writer property itself, observed rather than asserted in a comment:
            // every execution is announced from inside the lane that matched it, so the
            // publishing thread is the thread that owned the book.
            SynchronousEventBus bus = new SynchronousEventBus();
            Map<InstrumentId, Set<String>> threadsByInstrument = new ConcurrentHashMap<>();
            bus.subscribe(TradeExecuted.class, event -> threadsByInstrument
                    .computeIfAbsent(event.trade().instrumentId(), id -> ConcurrentHashMap.newKeySet())
                    .add(Thread.currentThread().getName()));

            try (OrderBookVenue venue = threadedVenue(bus)) {
                for (int i = 0; i < 20; i++) {
                    venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 10, SELLER), CLOCK);
                    venue.execute(OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("100.00"), 10, BUYER), CLOCK);
                    venue.execute(OrderBookInstruction.limit(MSFT, Side.SELL, Price.of("400.00"), 10, SELLER), CLOCK);
                    venue.execute(OrderBookInstruction.limit(MSFT, Side.BUY, Price.of("400.00"), 10, BUYER), CLOCK);
                }

                assertThat(threadsByInstrument.get(AAPL)).hasSize(1);
                assertThat(threadsByInstrument.get(MSFT)).hasSize(1);
                assertThat(threadsByInstrument.get(AAPL))
                        .isNotEqualTo(threadsByInstrument.get(MSFT));
                assertThat(threadsByInstrument.get(AAPL).iterator().next())
                        .isNotEqualTo(Thread.currentThread().getName());
            }
        }

        @Test
        @DisplayName("concurrent callers cannot over-fill a resting order")
        void concurrentCallersConserveQuantity() throws InterruptedException {
            int threads = 16;
            try (OrderBookVenue venue = threadedVenue(EventBus.ignoring())) {
                venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 1_000, SELLER), CLOCK);
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                CountDownLatch done = new CountDownLatch(threads);
                List<Trade> filled = Collections.synchronizedList(new ArrayList<>());

                for (int i = 0; i < threads; i++) {
                    pool.execute(() -> {
                        try {
                            filled.addAll(venue.execute(OrderBookInstruction.limit(
                                    AAPL, Side.BUY, Price.of("100.00"), 100, BUYER), CLOCK));
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
                pool.shutdown();

                long bought = filled.stream().filter(trade -> trade.owner().equals(BUYER))
                        .mapToLong(trade -> trade.delta().value().longValue()).sum();
                assertThat(bought).isEqualTo(1_000);
            }
        }

        @Test
        @DisplayName("a rejection surfaces as itself, not as a thread failure")
        void errorsCrossTheThreadUnchanged() {
            try (OrderBookVenue venue = threadedVenue(EventBus.ignoring())) {
                assertThatThrownBy(() -> venue.execute(OrderBookInstruction.limit(
                        InstrumentId.of("UNKNOWN"), Side.BUY, Price.of("1.00"), 1, BUYER), CLOCK))
                        .isInstanceOf(RuntimeException.class);
            }
        }

        @Test
        @DisplayName("a closed venue refuses further executions")
        void closedVenueRefuses() {
            OrderBookVenue venue = threadedVenue(EventBus.ignoring());
            venue.execute(OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("100.00"), 10, BUYER), CLOCK);
            venue.close();
            venue.close();

            assertThatThrownBy(() -> venue.execute(OrderBookInstruction.limit(
                    AAPL, Side.BUY, Price.of("100.00"), 10, BUYER), CLOCK))
                    .isInstanceOf(RejectedExecutionException.class);
        }

        @Nested
        @DisplayName("submit()")
        class Submit {

            @Test
            @DisplayName("a crossing pair submitted, not executed, still trades and publishes")
            void submittedInstructionsEventuallyTradeAndPublish() throws InterruptedException {
                SynchronousEventBus bus = new SynchronousEventBus();
                CountDownLatch bothTradesPublished = new CountDownLatch(2);
                List<TradeExecuted> announced = Collections.synchronizedList(new ArrayList<>());
                bus.subscribe(TradeExecuted.class, event -> {
                    announced.add(event);
                    bothTradesPublished.countDown();
                });

                try (OrderBookVenue venue = threadedVenue(bus)) {
                    venue.submit(OrderBookInstruction.limit(
                            AAPL, Side.SELL, Price.of("100.00"), 100, SELLER), CLOCK);
                    venue.submit(OrderBookInstruction.limit(
                            AAPL, Side.BUY, Price.of("100.00"), 100, BUYER), CLOCK);

                    assertThat(bothTradesPublished.await(5, TimeUnit.SECONDS)).isTrue();
                }
                assertThat(announced).extracting(event -> event.trade().owner())
                        .containsExactlyInAnyOrder(BUYER, SELLER);
            }

            @Test
            @DisplayName("produces the same trades execute() would, just not returned")
            void sameTradesAsExecuteJustNotReturned() throws InterruptedException {
                List<OrderBookInstruction> script = List.of(
                        OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 100, SELLER),
                        OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("100.00"), 150, BUYER),
                        OrderBookInstruction.market(AAPL, Side.SELL, 50, SELLER));

                List<String> inline = new ArrayList<>();
                OrderBookVenue plain = newVenue();
                script.forEach(instruction -> plain.execute(instruction, CLOCK)
                        .forEach(trade -> inline.add(describe(trade))));

                SynchronousEventBus bus = new SynchronousEventBus();
                List<String> submitted = Collections.synchronizedList(new ArrayList<>());
                CountDownLatch allPublished = new CountDownLatch(inline.size());
                bus.subscribe(TradeExecuted.class, event -> {
                    submitted.add(describe(event.trade()));
                    allPublished.countDown();
                });

                try (OrderBookVenue venue = threadedVenue(bus)) {
                    script.forEach(instruction -> venue.submit(instruction, CLOCK));
                    assertThat(allPublished.await(5, TimeUnit.SECONDS)).isTrue();
                }

                assertThat(submitted).isEqualTo(inline).isNotEmpty();
            }

            @Test
            @DisplayName("a closed venue refuses further submissions")
            void closedVenueRefusesSubmit() {
                OrderBookVenue venue = threadedVenue(EventBus.ignoring());
                // A lane exists only once something has traded on it - close() with no lane
                // yet for AAPL would close nothing, and a later submit() would just create a
                // fresh, unclosed one. Trade first, exactly as closedVenueRefuses() does.
                venue.submit(OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("100.00"), 10, BUYER), CLOCK);
                venue.close();

                assertThatThrownBy(() -> venue.submit(OrderBookInstruction.limit(
                        AAPL, Side.BUY, Price.of("100.00"), 10, BUYER), CLOCK))
                        .isInstanceOf(RejectedExecutionException.class);
            }
        }
    }
}
