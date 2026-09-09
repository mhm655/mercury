package com.mercury.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cash balances, kept per currency and never netted across them.
 *
 * <p>The one thing in a portfolio that genuinely cannot be summed without a decision: a book
 * long dollars and short euros is not flat, and a single converted figure would hide a
 * position that has to be funded.
 */
class CashAccountTest {

    @Test
    @DisplayName("a currency never transacted in has a balance of nothing")
    void unseenCurrencyIsZero() {
        // Zero rather than an exception, unlike market data. A currency an account has never
        // held genuinely has a balance, and it is nothing - that is a fact about the account
        // rather than a gap in it.
        assertThat(CashAccount.empty().balance(Currency.JPY))
                .isEqualTo(Money.zero(Currency.JPY));
        assertThat(CashAccount.empty().isEmpty()).isTrue();
        assertThat(CashAccount.empty().currencies()).isEmpty();
    }

    @Test
    @DisplayName("balances accumulate within a currency and stay apart across them")
    void currenciesDoNotMix() {
        CashAccount account = CashAccount.of(Money.of("1000", Currency.USD))
                .with(Money.of("500", Currency.USD))
                .with(Money.of("-2000", Currency.EUR));

        assertThat(account.balance(Currency.USD)).isEqualTo(Money.of("1500.00", Currency.USD));
        assertThat(account.balance(Currency.EUR)).isEqualTo(Money.of("-2000.00", Currency.EUR));
        assertThat(account.currencies()).containsExactly(Currency.USD, Currency.EUR);
    }

    @Test
    @DisplayName("a balance may be negative, because borrowing is a real position")
    void overdraftIsRepresentable() {
        // Refusing to represent a debt would only mean it lived somewhere the ledger could
        // not see.
        CashAccount overdrawn = CashAccount.of(Money.of("100", Currency.USD))
                .with(Money.of("-250", Currency.USD));

        assertThat(overdrawn.balance(Currency.USD).isNegative()).isTrue();
        assertThat(overdrawn.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("adding leaves the original account untouched")
    void withIsPure() {
        CashAccount opening = CashAccount.of(Money.of("1000", Currency.USD));

        CashAccount after = opening.with(Money.of("-400", Currency.USD));

        assertThat(opening.balance(Currency.USD)).isEqualTo(Money.of("1000.00", Currency.USD));
        assertThat(after.balance(Currency.USD)).isEqualTo(Money.of("600.00", Currency.USD));
        assertThat(after).isNotEqualTo(opening);
    }

    @Test
    @DisplayName("an account that nets to nothing is empty even though it has seen trades")
    void nettedToZeroIsEmpty() {
        CashAccount account = CashAccount.of(Money.of("1000", Currency.USD))
                .with(Money.of("-1000", Currency.USD));

        assertThat(account.isEmpty()).isTrue();
        assertThat(account.currencies()).containsExactly(Currency.USD);
    }
}
