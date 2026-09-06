package com.mercury.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Quantity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PositionTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");

    @Test
    @DisplayName("direction is read from the sign, not a separate flag")
    void directionFromSign() {
        assertThat(Position.of(AAPL, 100).isLong()).isTrue();
        assertThat(Position.of(AAPL, -100).isShort()).isTrue();
        assertThat(Position.of(AAPL, 0).isFlat()).isTrue();
    }

    @Test
    @DisplayName("a position is exactly one of long, short or flat")
    void exactlyOneDirection() {
        for (long quantity : new long[] {-500, -1, 0, 1, 500}) {
            Position position = Position.of(AAPL, quantity);
            int trueCount = (position.isLong() ? 1 : 0)
                    + (position.isShort() ? 1 : 0)
                    + (position.isFlat() ? 1 : 0);

            assertThat(trueCount).as("quantity %s", quantity).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("applying a trade is addition, with no branch on current direction")
    void applyingIsAddition() {
        // Selling 150 out of a long 100 flips to short 50, and buying 150 into a short 100
        // flips to long 50 - the same expression either way. That is the point of the sign.
        assertThat(Position.of(AAPL, 100).plus(Quantity.of(-150)).quantity())
                .isEqualTo(Quantity.of(-50));
        assertThat(Position.of(AAPL, -100).plus(Quantity.of(150)).quantity())
                .isEqualTo(Quantity.of(50));
    }

    @Test
    @DisplayName("adding produces a new position rather than mutating")
    void immutable() {
        // What makes the pro-forma projection behind pre-trade risk checks cheap: asking
        // "what if this traded?" builds a new position instead of mutating and rolling back.
        Position original = Position.of(AAPL, 100);

        Position increased = original.plus(Quantity.of(50));

        assertThat(original.quantity()).isEqualTo(Quantity.of(100));
        assertThat(increased.quantity()).isEqualTo(Quantity.of(150));
        assertThat(increased).isNotSameAs(original);
    }

    @Test
    @DisplayName("the instrument reference is preserved across additions")
    void keepsItsInstrument() {
        assertThat(Position.of(AAPL, 100).plus(Quantity.of(50)).instrumentId()).isEqualTo(AAPL);
    }
}
