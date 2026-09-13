package com.mercury.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SimulationWorkersTest {

    @Test
    void sequentialRunsOnTheCallingThreadInOrder() {
        Thread caller = Thread.currentThread();
        List<Supplier<Boolean>> blocks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            blocks.add(() -> Thread.currentThread() == caller);
        }

        assertThat(SimulationWorkers.sequential().run(blocks)).containsOnly(true);
        assertThat(SimulationWorkers.sequential().count()).isEqualTo(1);
    }

    @Test
    void parallelReturnsResultsInSubmissionOrderNotCompletionOrder() {
        // The first block is held until every other block has finished, so completion order
        // is the reverse of submission order for it - and the results must not care.
        int blockCount = 16;
        CountDownLatch othersDone = new CountDownLatch(blockCount - 1);
        List<Supplier<Integer>> blocks = new ArrayList<>();
        blocks.add(() -> {
            await(othersDone);
            return 0;
        });
        for (int i = 1; i < blockCount; i++) {
            int index = i;
            blocks.add(() -> {
                othersDone.countDown();
                return index;
            });
        }

        try (SimulationWorkers workers = SimulationWorkers.parallel(4)) {
            List<Integer> results = workers.run(blocks);

            assertThat(results).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        }
    }

    @Test
    void parallelActuallyUsesSeveralThreads() {
        // Four blocks that each wait for all four to have started can only finish if four
        // threads run them at once - on one thread this would time out instead.
        int workerCount = 4;
        CountDownLatch allStarted = new CountDownLatch(workerCount);
        List<Supplier<String>> blocks = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            blocks.add(() -> {
                allStarted.countDown();
                await(allStarted);
                return Thread.currentThread().getName();
            });
        }

        try (SimulationWorkers workers = SimulationWorkers.parallel(workerCount)) {
            assertThat(Set.copyOf(workers.run(blocks))).hasSize(workerCount);
            assertThat(workers.count()).isEqualTo(workerCount);
        }
    }

    @Test
    void aBlockThatThrowsRethrowsTheOriginalExceptionUnwrapped() {
        List<Supplier<Integer>> blocks = List.of(() -> 1, () -> {
            throw new IllegalStateException("block two failed");
        });

        try (SimulationWorkers workers = SimulationWorkers.parallel(2)) {
            assertThatThrownBy(() -> workers.run(blocks))
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage("block two failed");
        }
    }

    @Test
    void rejectsANonPositiveWorkerCount() {
        assertThatThrownBy(() -> SimulationWorkers.parallel(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0");
    }

    @Test
    void closedWorkersRefuseFurtherBlocks() {
        SimulationWorkers workers = SimulationWorkers.parallel(2);
        workers.close();
        workers.close();

        assertThatThrownBy(() -> workers.run(List.of(() -> 1)))
                .isInstanceOf(RejectedExecutionException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out - blocks are not running concurrently");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
