package com.mercury.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Cuts a run of {@code pathCount} paths into fixed-size blocks, each with its own random
 * stream split deterministically from the run seed.
 *
 * <h2>The two ways to get this wrong</h2>
 * A shared generator across threads is the obvious one: {@code java.util.Random} contends on
 * its atomic seed, and even a thread-safe generator hands each draw to whichever thread asks
 * first, so the path that receives a given number changes from run to run.
 *
 * <p>The quieter one is splitting per <em>worker</em>. It is reproducible for a fixed worker
 * count, and a different figure at every other worker count - so a VaR computed on the
 * laptop could not be reproduced on the server. Here the block boundaries and each block's
 * stream depend only on the seed and the path count; how many threads later run the blocks
 * is invisible to the numbers.
 *
 * <h2>Why split, and why eagerly</h2>
 * {@link SplittableRandom#split()} yields a generator statistically independent of its
 * parent, and is deterministic given the parent's state. It also advances the parent, so the
 * splits must happen in one fixed order on one thread - which is why {@link #of} does them all
 * up front, before any block is handed to a worker, rather than letting each task split its
 * own stream on arrival.
 *
 * <h2>Block size</h2>
 * {@value #BLOCK_SIZE} paths: large enough that per-task overhead (a split, a submission, a
 * list) is noise beside the work, small enough that even a 50,000-path run gives twelve
 * workers something each to do. A constant rather than a parameter, because changing it
 * changes every simulated figure for a given seed - it is part of what a seed means.
 */
final class PathBlocks {

    static final int BLOCK_SIZE = 4_096;

    private PathBlocks() {
    }

    /** One block: {@code size} paths drawn from {@code rng}, which no other block shares. */
    record Block(int size, SplittableRandom rng) {
    }

    /**
     * @throws IllegalArgumentException if {@code pathCount} is not positive
     */
    static List<Block> of(int pathCount, long seed) {
        if (pathCount <= 0) {
            throw new IllegalArgumentException("pathCount must be positive, but was " + pathCount);
        }
        SplittableRandom root = new SplittableRandom(seed);
        List<Block> blocks = new ArrayList<>(pathCount / BLOCK_SIZE + 1);
        for (int start = 0; start < pathCount; start += BLOCK_SIZE) {
            blocks.add(new Block(Math.min(BLOCK_SIZE, pathCount - start), root.split()));
        }
        return blocks;
    }
}
