package com.mercury.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PathBlocksTest {

    @Test
    void blocksCoverEveryPathExactlyOnceWithOnlyTheLastOneShort() {
        int pathCount = 2 * PathBlocks.BLOCK_SIZE + 5;

        List<PathBlocks.Block> blocks = PathBlocks.of(pathCount, 1);

        assertThat(blocks).extracting(PathBlocks.Block::size)
                .containsExactly(PathBlocks.BLOCK_SIZE, PathBlocks.BLOCK_SIZE, 5);
    }

    @Test
    void fewerPathsThanOneBlockMakeOneBlock() {
        assertThat(PathBlocks.of(1, 1)).extracting(PathBlocks.Block::size).containsExactly(1);
    }

    @Test
    void theSameSeedSplitsTheSameStreams() {
        List<PathBlocks.Block> first = PathBlocks.of(3 * PathBlocks.BLOCK_SIZE, 99);
        List<PathBlocks.Block> second = PathBlocks.of(3 * PathBlocks.BLOCK_SIZE, 99);

        for (int i = 0; i < first.size(); i++) {
            assertThat(first.get(i).rng().nextLong()).isEqualTo(second.get(i).rng().nextLong());
        }
    }

    @Test
    void everyBlockDrawsFromADifferentStream() {
        // Blocks sharing a stream would make "200,000 paths" really a few thousand paths
        // repeated - the error would look like convergence and be nothing of the kind.
        List<PathBlocks.Block> blocks = PathBlocks.of(4 * PathBlocks.BLOCK_SIZE, 7);

        assertThat(blocks.stream().map(block -> block.rng().nextLong()).distinct()).hasSize(4);
    }

    @Test
    void rejectsANonPositivePathCount() {
        assertThatThrownBy(() -> PathBlocks.of(0, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
