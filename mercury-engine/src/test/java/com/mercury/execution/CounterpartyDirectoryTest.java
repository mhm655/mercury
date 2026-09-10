package com.mercury.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.trade.CreditLimit;
import com.mercury.trade.Counterparty;
import org.junit.jupiter.api.Test;

class CounterpartyDirectoryTest {

    private final Counterparty acme = new Counterparty(CounterpartyId.of("CPTY-ACME"), "Acme Capital",
            new CreditLimit(Money.of("1000000.00", Currency.USD)));

    @Test
    void resolvesARegisteredCounterparty() {
        CounterpartyDirectory directory = CounterpartyDirectory.of(acme);

        assertThat(directory.require(acme.id())).isEqualTo(acme);
    }

    @Test
    void rejectsDuplicateIds() {
        Counterparty impostor = new Counterparty(acme.id(), "Impostor Capital",
                new CreditLimit(Money.of("1.00", Currency.USD)));

        assertThatThrownBy(() -> CounterpartyDirectory.of(acme, impostor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("share the id");
    }

    @Test
    void throwsOnAnUnknownCounterparty() {
        CounterpartyDirectory directory = CounterpartyDirectory.of(acme);

        assertThatThrownBy(() -> directory.require(CounterpartyId.of("CPTY-GHOST")))
                .isInstanceOf(CounterpartyDirectory.UnknownCounterpartyException.class)
                .hasMessageContaining("CPTY-GHOST");
    }
}
