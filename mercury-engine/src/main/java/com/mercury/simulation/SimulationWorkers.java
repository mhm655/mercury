package com.mercury.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The threads a Monte Carlo run is spread across - or none, for {@link #sequential()}.
 *
 * <h2>Workers change how fast, never what</h2>
 * A run is cut into {@link PathBlocks} whose boundaries and random streams depend only on
 * the seed and the path count. This class decides nothing about either: it only runs the
 * blocks it is handed and returns their results in the order they were handed over, however
 * the threads happened to interleave. So the same seed gives the same figure on
 * {@code sequential()}, on {@code parallel(1)} and on {@code parallel(12)}, to the last bit -
 * which {@code MonteCarloVaRCalculatorTest} and {@code MonteCarloOptionModelTest} assert
 * rather than hope for. `docs/DESIGN_PROPOSAL.md` section 5.6 b) names this as the part of
 * parallel Monte Carlo actually worth getting right.
 *
 * <h2>Why a pool is owned here, not borrowed from ForkJoinPool.commonPool()</h2>
 * The scaling benchmark has to control the worker count exactly - a run "on 6 workers" that
 * silently shared the common pool with whatever else the JVM was doing would measure nothing.
 * Holding a fixed pool also makes the cost of a thread explicit to the caller, who closes it.
 *
 * <p>Thread-safe. {@link #close()} is idempotent; running blocks after it fails.
 */
public final class SimulationWorkers implements AutoCloseable {

    private static final SimulationWorkers SEQUENTIAL = new SimulationWorkers(1, null);

    private final int count;
    private final ExecutorService executor;

    private SimulationWorkers(int count, ExecutorService executor) {
        this.count = count;
        this.executor = executor;
    }

    /**
     * Runs every block on the calling thread, in order. The default wherever no workers are
     * given: deterministic by construction, no threads to create or close, and the right
     * choice for small path counts where handing work to another thread costs more than it
     * saves.
     */
    public static SimulationWorkers sequential() {
        return SEQUENTIAL;
    }

    /**
     * A fixed pool of {@code workers} daemon threads, owned by the returned instance until
     * {@link #close()}.
     *
     * @throws IllegalArgumentException if {@code workers} is not positive
     */
    public static SimulationWorkers parallel(int workers) {
        if (workers <= 0) {
            throw new IllegalArgumentException("workers must be positive, but was " + workers);
        }
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(workers, task -> {
            Thread thread = new Thread(task, "mercury-simulation-" + threadNumber.incrementAndGet());
            // Daemon, so a caller that forgets to close cannot keep the JVM alive after main.
            thread.setDaemon(true);
            return thread;
        });
        return new SimulationWorkers(workers, executor);
    }

    /** How many threads blocks run on; 1 for {@link #sequential()}. */
    public int count() {
        return count;
    }

    /**
     * Runs {@code blocks} and returns their results in the same order, regardless of which
     * finished first. A block that throws rethrows here, unwrapped, once every block has
     * finished - so a {@code MissingMarketDataException} raised on a worker reaches the caller
     * as itself, exactly as it would have on one thread.
     */
    <T> List<T> run(List<Supplier<T>> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        if (executor == null) {
            List<T> results = new ArrayList<>(blocks.size());
            for (Supplier<T> block : blocks) {
                results.add(block.get());
            }
            return results;
        }

        List<Callable<T>> tasks = new ArrayList<>(blocks.size());
        for (Supplier<T> block : blocks) {
            tasks.add(block::get);
        }
        try {
            List<T> results = new ArrayList<>(blocks.size());
            for (Future<T> future : executor.invokeAll(tasks)) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for simulation workers", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("A simulation block failed", cause);
        }
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public String toString() {
        return executor == null ? "SimulationWorkers[sequential]" : "SimulationWorkers[" + count + " threads]";
    }
}
