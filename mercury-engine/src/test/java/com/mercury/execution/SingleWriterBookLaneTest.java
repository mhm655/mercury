package com.mercury.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.InstrumentId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link SingleWriterBookLane#submit}, in isolation from {@code OrderBookVenue} - no order book,
 * no trades, just the queue-and-return-immediately property M17 adds. {@link #run} is already
 * covered through {@code OrderBookVenueTest}'s {@code SingleWriter} suite; this file is
 * {@code submit}'s.
 */
class SingleWriterBookLaneTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");

    @Test
    void returnsBeforeAPriorSlowCommandFinishes() throws InterruptedException {
        SingleWriterBookLane lane = new SingleWriterBookLane(AAPL);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondRan = new CountDownLatch(1);

        lane.submit(() -> {
            firstStarted.countDown();
            await(releaseFirst);
        });
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // The writer is still blocked in the first command. If submit() waited for its turn to
        // run rather than just to be queued, this call would not return until releaseFirst
        // counts down - it must return well within the timeout regardless.
        long before = System.nanoTime();
        lane.submit(secondRan::countDown);
        long elapsedMillis = (System.nanoTime() - before) / 1_000_000;
        assertThat(elapsedMillis).isLessThan(1_000);
        assertThat(secondRan.getCount()).isEqualTo(1); // not run yet - still behind the first

        releaseFirst.countDown();
        assertThat(secondRan.await(5, TimeUnit.SECONDS)).isTrue();
        lane.close();
    }

    @Test
    void submittedWorkRunsExactlyOnceInOrder() throws InterruptedException {
        SingleWriterBookLane lane = new SingleWriterBookLane(AAPL);
        List<Integer> ran = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(5);

        for (int i = 0; i < 5; i++) {
            int value = i;
            lane.submit(() -> {
                ran.add(value);
                done.countDown();
            });
        }

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ran).containsExactly(0, 1, 2, 3, 4);
        lane.close();
    }

    @Test
    void aClosedLaneRejectsFurtherSubmissions() {
        SingleWriterBookLane lane = new SingleWriterBookLane(AAPL);
        lane.close();
        lane.close(); // idempotent

        assertThatThrownBy(() -> lane.submit(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void workQueuedBeforeCloseStillRuns() throws InterruptedException {
        SingleWriterBookLane lane = new SingleWriterBookLane(AAPL);
        CountDownLatch ran = new CountDownLatch(1);

        lane.submit(ran::countDown);
        lane.close();

        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
