package com.mercury.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import org.junit.jupiter.api.Test;

class CreditLimitTest {

    @Test
    void acceptsAPositiveMaximum() {
        CreditLimit limit = new CreditLimit(Money.of("5000000.00", Currency.USD));

        assertThat(limit.maximum()).isEqualTo(Money.of("5000000.00", Currency.USD));
    }

    @Test
    void rejectsZero() {
        assertThatThrownBy(() -> new CreditLimit(Money.zero(Currency.USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void rejectsANegativeMaximum() {
        assertThatThrownBy(() -> new CreditLimit(Money.of("-1.00", Currency.USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
