package com.mercury.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.id.TradeId;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TradeIdGeneratorTest {

    @Test
    void everyMintedIdIsUnique() {
        TradeIdGenerator generator = new TradeIdGenerator("TRD-");
        Set<TradeId> ids = new HashSet<>();

        for (int i = 0; i < 50_000; i++) {
            TradeId id = generator.next();
            assertThat(ids.add(id))
                    .as("id %s was minted more than once, at call %d", id, i)
                    .isTrue();
        }
    }

    @Test
    void idsCarryTheStatedPrefix() {
        TradeIdGenerator generator = new TradeIdGenerator("TRD-");

        assertThat(generator.next().value()).startsWith("TRD-");
    }
}
