package com.mercury.core.money;

import com.mercury.core.MercuryException;
import java.util.Objects;

/**
 * An ordered pair of currencies, quoted {@code BASE/QUOTE}.
 *
 * <p>The rate for a pair is the number of units of {@code quote} that buy one unit of
 * {@code base}: EUR/USD at 1.08 means one euro costs 1.08 dollars. Getting this
 * direction backwards is the most common FX bug there is, so the convention is stated
 * here, encoded in the field names, and tested.
 *
 * <p>Direction is part of identity: {@code EUR/USD} and {@code USD/EUR} are different
 * pairs whose rates are reciprocals, not the same pair. Market data is keyed by pair, so
 * conflating them would silently return an inverted rate.
 */
public record CurrencyPair(Currency base, Currency quote) {

    public CurrencyPair {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(quote, "quote");
        if (base == quote) {
            throw new IdenticalCurrenciesException(base);
        }
    }

    public static CurrencyPair of(Currency base, Currency quote) {
        return new CurrencyPair(base, quote);
    }

    /** Parses the market convention {@code "EUR/USD"}. */
    public static CurrencyPair parse(String text) {
        Objects.requireNonNull(text, "text");
        int slash = text.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException(
                    "Currency pair must be written BASE/QUOTE, e.g. \"EUR/USD\", but was: " + text);
        }
        return new CurrencyPair(
                Currency.valueOf(text.substring(0, slash).trim().toUpperCase()),
                Currency.valueOf(text.substring(slash + 1).trim().toUpperCase()));
    }

    /**
     * The pair quoted the other way round. The inverse pair's rate is the reciprocal of
     * this one's; this method only flips the currencies, it does not touch rates.
     */
    public CurrencyPair inverse() {
        return new CurrencyPair(quote, base);
    }

    /** True if this pair references {@code currency} on either leg. */
    public boolean involves(Currency currency) {
        return base == currency || quote == currency;
    }

    @Override
    public String toString() {
        return base.code() + "/" + quote.code();
    }

    /** Raised when a pair is built from a single currency, which has no meaningful rate. */
    public static final class IdenticalCurrenciesException extends MercuryException {
        public IdenticalCurrenciesException(Currency currency) {
            super("A currency pair needs two different currencies, but both legs were "
                    + currency.code() + ". The rate of a currency against itself is always 1 "
                    + "and is not modelled as a pair.");
        }
    }
}
