package com.mercury.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An exact monetary amount in a single currency.
 *
 * <h2>Where this type belongs, and where it does not</h2>
 * Mercury splits its arithmetic deliberately (see {@code docs/DESIGN_PROPOSAL.md}
 * section A2.2):
 *
 * <ul>
 *   <li><b>{@code Money} is for ledger facts</b> - cash balances, trade consideration,
 *       realised P&amp;L, settlement amounts. These must reconcile to the cent and
 *       round-trip exactly.</li>
 *   <li><b>{@code double} is for model output</b> - Black-Scholes prices, Greeks,
 *       discount factors, Monte Carlo paths. Those are approximations to a handful of
 *       significant figures; {@code BigDecimal} there would be an order of magnitude
 *       slower and <em>falsely</em> precise.</li>
 * </ul>
 *
 * Model results cross into this type at exactly one boundary, and round exactly once.
 *
 * <h2>Scale normalisation</h2>
 * Every instance is normalised on construction to its currency's
 * {@linkplain Currency#minorUnits() minor units} - two places for USD, none for JPY.
 * That is not cosmetic. {@link BigDecimal#equals(Object)} is scale-sensitive
 * ({@code 1.50} does not equal {@code 1.5}), so without normalisation this record's
 * generated {@code equals} and {@code hashCode} would be subtly wrong and two amounts
 * that are the same money would fail to match. Normalising at the boundary makes the
 * record's default equality correct by construction.
 *
 * <h2>Arithmetic</h2>
 * Addition and subtraction of two normalised same-currency amounts are exact - no
 * rounding occurs or can occur. Multiplication and division round once, HALF_EVEN
 * (banker's rounding, which unlike HALF_UP does not accumulate an upward bias across
 * many operations). Multi-step arithmetic should therefore be done in {@link BigDecimal}
 * or {@code double} and converted here once, rather than chained through {@code Money}
 * where each step would round.
 *
 * <p>Instances are immutable and thread-safe.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    /**
     * Rounding used wherever an operation cannot be exact.
     *
     * <p>HALF_EVEN rather than HALF_UP: repeatedly rounding halves upward introduces a
     * systematic upward drift across a large book of trades, which is precisely the kind
     * of error that shows up as an unexplained reconciliation break.
     */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = amount.setScale(currency.minorUnits(), ROUNDING);
    }

    // ---------------------------------------------------------------- factories

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    /**
     * Preferred factory for literals: {@code Money.of("1234.56", Currency.USD)}.
     *
     * <p>There is deliberately no {@code of(double, Currency)}. A {@code double} cannot
     * represent {@code 0.10} exactly, and accepting one here would quietly reintroduce
     * the imprecision this type exists to eliminate. Model values that genuinely start
     * life as {@code double} convert through {@link #fromModelValue}, which names what
     * is happening.
     */
    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money of(long amount, Currency currency) {
        return new Money(BigDecimal.valueOf(amount), currency);
    }

    /**
     * Builds an amount from a count of minor units - cents, pence, whole yen.
     * {@code ofMinor(150, USD)} is {@code 1.50}; {@code ofMinor(150, JPY)} is {@code 150}.
     */
    public static Money ofMinor(long minorUnits, Currency currency) {
        return new Money(BigDecimal.valueOf(minorUnits, currency.minorUnits()), currency);
    }

    /**
     * The single, explicit boundary where a pricing or risk model's {@code double}
     * result becomes a ledger amount.
     *
     * <p>Named rather than overloaded so that every crossing of the
     * {@code double -> BigDecimal} boundary is greppable, and so nobody converts by
     * accident. Rejects NaN and infinity, which are how a broken model most often
     * announces itself.
     */
    public static Money fromModelValue(double modelValue, Currency currency) {
        if (!Double.isFinite(modelValue)) {
            throw new IllegalArgumentException(
                    "Model produced a non-finite value (" + modelValue + "); cannot convert to "
                            + currency.code() + ". This indicates a broken pricing or risk calculation.");
        }
        return new Money(BigDecimal.valueOf(modelValue), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    // --------------------------------------------------------------- arithmetic

    /** Exact: both operands already share this currency's scale. */
    public Money plus(Money other) {
        requireSameCurrency(other, "add");
        return new Money(amount.add(other.amount), currency);
    }

    /** Exact: both operands already share this currency's scale. */
    public Money minus(Money other) {
        requireSameCurrency(other, "subtract");
        return new Money(amount.subtract(other.amount), currency);
    }

    /** Rounds once, HALF_EVEN. Typically price x quantity. */
    public Money multipliedBy(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor");
        return new Money(amount.multiply(factor), currency);
    }

    /** Exact for whole-number factors within scale. */
    public Money multipliedBy(long factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    /** Rounds once, HALF_EVEN. */
    public Money dividedBy(BigDecimal divisor) {
        Objects.requireNonNull(divisor, "divisor");
        if (divisor.signum() == 0) {
            throw new ArithmeticException("Division of " + this + " by zero");
        }
        return new Money(amount.divide(divisor, currency.minorUnits(), ROUNDING), currency);
    }

    public Money negated() {
        return new Money(amount.negate(), currency);
    }

    public Money abs() {
        return new Money(amount.abs(), currency);
    }

    // -------------------------------------------------------------- comparisons

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    /**
     * Orders two amounts in the same currency.
     *
     * <p>This is a <em>partial</em> order: comparing across currencies throws
     * {@link CurrencyMismatchException} rather than inventing an ordering, so sorting a
     * mixed-currency collection fails immediately and loudly instead of producing a
     * meaningless result. Comparing USD to EUR without naming an FX rate is not a
     * question with an answer, and a type that pretends otherwise is worse than one that
     * refuses.
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other, "compare");
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other, String operation) {
        Objects.requireNonNull(other, "other");
        if (currency != other.currency) {
            throw new CurrencyMismatchException(operation, currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.code();
    }
}
