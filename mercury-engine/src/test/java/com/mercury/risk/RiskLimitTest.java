package com.mercury.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.trade.CreditLimit;
import com.mercury.trade.Counterparty;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskLimitTest {

    private final Counterparty acme = new Counterparty(CounterpartyId.of("CPTY-ACME"), "Acme Capital",
            new CreditLimit(Money.of("1000000.00", Currency.USD)));

    @Test
    void noneNeverBreaches() {
        LimitCheckResult result = RiskLimit.none().check(acme, Money.of("999999999.00", Currency.USD));

        assertThat(result.isApproved()).isTrue();
    }

    @Test
    void andCombinesBreachesFromBothSides() {
        RiskLimit alwaysBreaches = (counterparty, projectedExposure) -> new LimitCheckResult(
                List.of(new LimitBreach("stub", counterparty.id(), projectedExposure, Money.zero(Currency.USD))));
        RiskLimit combined = new CounterpartyExposureLimit().and(alwaysBreaches);

        LimitCheckResult result = combined.check(acme, Money.of("1000000.01", Currency.USD));

        assertThat(result.breaches()).hasSize(2);
    }

    @Test
    void compositeOfNoLimitsIsNone() {
        RiskLimit composite = RiskLimit.composite(List.of());

        assertThat(composite.check(acme, Money.of("999999999.00", Currency.USD)).isApproved()).isTrue();
    }

    @Test
    void compositeStopsAtNoBreachWhenNoPartFires() {
        RiskLimit composite = RiskLimit.composite(List.of(new CounterpartyExposureLimit(), RiskLimit.none()));

        assertThat(composite.check(acme, Money.of("500.00", Currency.USD)).isApproved()).isTrue();
    }
}
