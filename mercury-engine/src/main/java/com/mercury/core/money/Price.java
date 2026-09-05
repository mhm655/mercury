package com.mercury.core.money;

import com.mercury.core.MercuryException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A strictly positive per-unit price, in the currency of the instrument being priced.
 *
 * <h2>Why this deliberately carries no currency</h2>
 * An instrument already knows its own currency, so a price that repeated it would
 * duplicate state and create a second place for the two to disagree. It also matters for
 * the order book: price is the key of the bid and ask maps and is compared on every
 * insert and every match, so it stays as small and as cheap to compare as possible.
 *
 * <p>{@link Money} is the type that carries currency. {@code Price} is a bare number that
 * only means something next to the instrument it belongs to.
 *
 * <h2>Why strictly positive</h2>
 * A resting order at a non-positive price is not a real order, and a book that accepts
 * one produces nonsense on the first match. Rejecting at construction means matching
 * never has to defend against it. This does mean the type cannot express negative
 * interest rates or negative oil futures - those are rate and cashflow concepts, carried
 * by other types, not order-book prices.
 *
 * <p>Instances are immutable and thread-safe.
 */
public record Price(BigDecimal value) implements Comparable<Price> {

    /** Decimal places retained; normalised so {@code equals} and map keys behave. */
    public static final int SCALE = 8;

    public Price {
        Objects.requireNonNull(value, "value");
        if (value.signum() <= 0) {
            throw new NonPositivePriceException(value);
        }
        value = value.setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    public static Price of(BigDecimal value) {
        return new Price(value);
    }

    public static Price of(long value) {
        return new Price(BigDecimal.valueOf(value));
    }

    public static Price of(String value) {
        return new Price(new BigDecimal(value));
    }

    /** Attaches a currency, turning a per-unit price into a monetary amount. */
    public Money toMoney(Currency currency) {
        return Money.of(value, currency);
    }

    public boolean isGreaterThan(Price other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Price other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Price other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.stripTrailingZeros().toPlainString();
    }

    /** Raised when a price is zero or negative. */
    public static final class NonPositivePriceException extends MercuryException {
        public NonPositivePriceException(BigDecimal value) {
            super("Price must be strictly positive, but was " + value.toPlainString()
                    + ". Negative rates and negative cashflows are modelled by other types; "
                    + "an order-book price is never zero or below.");
        }
    }
}
