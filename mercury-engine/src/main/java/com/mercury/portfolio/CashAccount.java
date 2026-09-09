package com.mercury.portfolio;

import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cash balances, one per currency.
 *
 * <h2>Why a balance per currency and not a single figure</h2>
 * Cash is the one thing in a portfolio that genuinely cannot be netted across currencies
 * without a decision. A book long dollars and short euros is not flat, and reporting it as a
 * single converted number would hide a position that has to be funded. So the account holds
 * each currency separately and converts only when asked to report.
 *
 * <p>Balances may go negative. An overdrawn account is a borrowing, which is a real position
 * with a real cost, and refusing to represent it would only mean the debt lived somewhere the
 * ledger could not see.
 *
 * <p>Immutable: {@link #with} returns a new account. A cash balance is the running total of a
 * history, and keeping each state intact is what makes that history replayable.
 */
public final class CashAccount {

    private final Map<Currency, Money> balances;

    private CashAccount(Map<Currency, Money> balances) {
        this.balances = balances;
    }

    /** An account with nothing in it. */
    public static CashAccount empty() {
        return new CashAccount(Map.of());
    }

    /** An opening balance in one currency. */
    public static CashAccount of(Money opening) {
        Objects.requireNonNull(opening, "opening");
        return new CashAccount(Map.of(opening.currency(), opening));
    }

    /**
     * The balance in {@code currency}, or zero if the account has never held any.
     *
     * <p>Zero rather than an exception, unlike market data: a currency an account has never
     * transacted in genuinely has a balance, and it is nothing. That is a fact about the
     * account rather than a gap in it.
     */
    public Money balance(Currency currency) {
        Objects.requireNonNull(currency, "currency");
        return balances.getOrDefault(currency, Money.zero(currency));
    }

    /** A new account with {@code amount} added to its currency's balance. */
    public CashAccount with(Money amount) {
        Objects.requireNonNull(amount, "amount");
        Map<Currency, Money> updated = new LinkedHashMap<>(balances);
        updated.merge(amount.currency(), amount, Money::plus);
        return new CashAccount(Collections.unmodifiableMap(updated));
    }

    /** Every currency the account holds a balance in, in the order they were first seen. */
    public List<Currency> currencies() {
        return List.copyOf(balances.keySet());
    }

    public boolean isEmpty() {
        return balances.values().stream().allMatch(Money::isZero);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CashAccount other && balances.equals(other.balances);
    }

    @Override
    public int hashCode() {
        return balances.hashCode();
    }

    @Override
    public String toString() {
        return balances.isEmpty() ? "no cash" : balances.values().toString();
    }
}
