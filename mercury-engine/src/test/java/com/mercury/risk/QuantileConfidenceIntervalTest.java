package com.mercury.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import org.junit.jupiter.api.Test;

class QuantileConfidenceIntervalTest {

    @Test
    void acceptsALowerBoundBelowTheUpperBound() {
        QuantileConfidenceInterval interval = new QuantileConfidenceInterval(
                Money.of("100.00", Currency.USD), Money.of("200.00", Currency.USD));

        assertThat(interval.lowerBound()).isEqualTo(Money.of("100.00", Currency.USD));
        assertThat(interval.upperBound()).isEqualTo(Money.of("200.00", Currency.USD));
    }

    @Test
    void acceptsEqualBoundsAsADegeneratePoint() {
        QuantileConfidenceInterval interval = new QuantileConfidenceInterval(
                Money.of("150.00", Currency.USD), Money.of("150.00", Currency.USD));

        assertThat(interval.lowerBound()).isEqualTo(interval.upperBound());
    }

    @Test
    void rejectsALowerBoundAboveTheUpperBound() {
        assertThatThrownBy(() -> new QuantileConfidenceInterval(
                Money.of("200.00", Currency.USD), Money.of("100.00", Currency.USD)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringShowsBothBounds() {
        QuantileConfidenceInterval interval = new QuantileConfidenceInterval(
                Money.of("100.00", Currency.USD), Money.of("200.00", Currency.USD));

        assertThat(interval.toString()).isEqualTo("[100.00 USD, 200.00 USD]");
    }

    @Test
    void marksABoundTheSampleRanOutBefore() {
        // Ten scenarios at 90% put the estimate at rank 1, so the severe end of the band
        // asks for a rank below the sample and is clamped back onto the estimate itself.
        // Unmarked it reads as "the loss is at most 200", which is the one thing it does
        // not say.
        QuantileConfidenceInterval interval = new QuantileConfidenceInterval(
                Money.of("100.00", Currency.USD), Money.of("200.00", Currency.USD),
                false, true);

        assertThat(interval.isSampleLimited()).isTrue();
        assertThat(interval.toString()).isEqualTo("[100.00 USD, >=200.00 USD]");
    }

    @Test
    void marksTheMildEndToo() {
        QuantileConfidenceInterval interval = new QuantileConfidenceInterval(
                Money.of("100.00", Currency.USD), Money.of("200.00", Currency.USD),
                true, false);

        assertThat(interval.isSampleLimited()).isTrue();
        assertThat(interval.toString()).isEqualTo("[<=100.00 USD, 200.00 USD]");
    }

    @Test
    void anIntervalInsideItsSampleIsNotMarked() {
        QuantileConfidenceInterval interval = new QuantileConfidenceInterval(
                Money.of("100.00", Currency.USD), Money.of("200.00", Currency.USD));

        assertThat(interval.isSampleLimited()).isFalse();
        assertThat(interval.toString()).isEqualTo("[100.00 USD, 200.00 USD]");
    }
}
