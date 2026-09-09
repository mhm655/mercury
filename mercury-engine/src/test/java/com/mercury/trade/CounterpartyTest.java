package com.mercury.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import org.junit.jupiter.api.Test;

class CounterpartyTest {

    private static final CreditLimit LIMIT = new CreditLimit(Money.of("5000000.00", Currency.USD));

    @Test
    void rejectsABlankName() {
        assertThatThrownBy(() -> new Counterparty(CounterpartyId.of("CPTY-ACME"), "  ", LIMIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void twoCounterpartiesWithTheSameIdAreEqual() {
        Counterparty first = new Counterparty(CounterpartyId.of("CPTY-ACME"), "Acme Capital", LIMIT);
        Counterparty renamed = new Counterparty(CounterpartyId.of("CPTY-ACME"), "Acme Capital LLC",
                new CreditLimit(Money.of("1.00", Currency.USD)));

        assertThat(first).isEqualTo(renamed);
        assertThat(first.hashCode()).isEqualTo(renamed.hashCode());
    }

    @Test
    void twoCounterpartiesWithDifferentIdsAreNotEqual() {
        Counterparty acme = new Counterparty(CounterpartyId.of("CPTY-ACME"), "Acme Capital", LIMIT);
        Counterparty globex = new Counterparty(CounterpartyId.of("CPTY-GLOBEX"), "Acme Capital", LIMIT);

        assertThat(acme).isNotEqualTo(globex);
    }
}
