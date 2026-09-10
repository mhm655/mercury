package com.mercury.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.trade.CreditLimit;
import com.mercury.trade.Counterparty;
import org.junit.jupiter.api.Test;

class CounterpartyExposureLimitTest {

    private final Counterparty acme = new Counterparty(CounterpartyId.of("CPTY-ACME"), "Acme Capital",
            new CreditLimit(Money.of("1000000.00", Currency.USD)));

    private final RiskLimit limit = new CounterpartyExposureLimit();

    @Test
    void approvesExposureUnderTheMaximum() {
        LimitCheckResult result = limit.check(acme, Money.of("999999.99", Currency.USD));

        assertThat(result.isApproved()).isTrue();
        assertThat(result.breaches()).isEmpty();
    }

    @Test
    void approvesExposureExactlyAtTheMaximum() {
        LimitCheckResult result = limit.check(acme, Money.of("1000000.00", Currency.USD));

        assertThat(result.isApproved()).isTrue();
    }

    @Test
    void breachesExposureOverTheMaximum() {
        LimitCheckResult result = limit.check(acme, Money.of("1000000.01", Currency.USD));

        assertThat(result.isBreached()).isTrue();
        assertThat(result.breaches()).hasSize(1);
        LimitBreach breach = result.breaches().get(0);
        assertThat(breach.counterparty()).isEqualTo(acme.id());
        assertThat(breach.projectedExposure()).isEqualTo(Money.of("1000000.01", Currency.USD));
        assertThat(breach.maximum()).isEqualTo(Money.of("1000000.00", Currency.USD));
    }
}
