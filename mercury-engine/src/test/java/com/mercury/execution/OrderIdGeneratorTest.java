package com.mercury.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.id.OrderId;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Direct test of the G-1 fix: unlike the matching book's state space, a monotonic counter
 * has no interesting states for a property test to explore, so a large plain loop proves
 * the invariant that matters - every id it mints is distinct - just as well.
 */
class OrderIdGeneratorTest {

    @Test
    void everyMintedIdIsUnique() {
        OrderIdGenerator generator = new OrderIdGenerator("OB-");
        Set<OrderId> ids = new HashSet<>();

        for (int i = 0; i < 50_000; i++) {
            OrderId id = generator.next();
            assertThat(ids.add(id))
                    .as("id %s was minted more than once, at call %d", id, i)
                    .isTrue();
        }
    }

    @Test
    void idsCarryTheStatedPrefix() {
        OrderIdGenerator generator = new OrderIdGenerator("OB-");

        assertThat(generator.next().value()).startsWith("OB-");
    }
}
