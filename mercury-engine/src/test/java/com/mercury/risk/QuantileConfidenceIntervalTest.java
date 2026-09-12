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
}
