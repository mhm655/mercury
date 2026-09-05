package com.mercury.instrument;

import com.mercury.core.money.Price;
import java.time.LocalDate;

/**
 * The contract terms common to every option, whatever its exercise style.
 *
 * <p>Separated from any particular option class so that generic machinery - moneyness
 * reporting, expiry bucketing, volatility surface lookups - can work against the terms
 * without knowing whether it holds a European, American or Bermudan option.
 *
 * <p>This is the interface that makes the M15 extensibility proof cheap. Adding an
 * American option means adding a class that implements {@code FinancialInstrument},
 * {@link HasUnderlying} and this interface, plus a binomial pricing model. Everything that
 * consumes option terms generically keeps working, untouched.
 */
public interface OptionTerms {

    /** The strike price, in the option's currency. */
    Price strike();

    /** The last date the option can be exercised. */
    LocalDate expiryDate();

    /** Call or put. */
    OptionType optionType();

    /**
     * Units of the underlying per contract. Listed equity options are conventionally 100.
     *
     * <p>Kept off {@code FinancialInstrument} because it is meaningless for a stock or a
     * swap; a common {@code multiplier()} returning 1 for those would be an interface
     * method that means something different for each implementation.
     */
    int contractMultiplier();

    /** Time to expiry in years under ACT/365F - the convention Black-Scholes assumes. */
    default double yearsToExpiry(LocalDate valuationDate) {
        return com.mercury.core.time.DayCountConvention.ACT_365F
                .yearFraction(valuationDate, expiryDate());
    }

    /** The payoff if exercised now against {@code spot}. */
    default double intrinsicValue(double spot) {
        return optionType().intrinsicValue(spot, strike().value().doubleValue());
    }
}
