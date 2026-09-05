package com.mercury.instrument;

import java.time.LocalDate;

/**
 * Implemented by instruments that expire or redeem on a known date.
 *
 * <p>Bonds, forwards, options and swaps all have one. Equities do not - a share has no
 * maturity, and modelling that as an {@code Optional<LocalDate>} on every instrument would
 * push an empty case onto every caller. A capability interface lets the question be asked
 * only of instruments for which it has an answer.
 *
 * <p>Used for time-bucketing risk, for ageing instruments as the simulation clock advances,
 * and for identifying positions that should be removed once they expire.
 */
public interface Maturing {

    /**
     * The date this instrument redeems, settles or expires.
     *
     * <p>For an option this is its expiry; the concept is the same - the last date on which
     * the contract can produce a cashflow.
     */
    LocalDate maturityDate();

    /** True if the instrument has expired as of {@code valuationDate}. */
    default boolean hasMaturedAsOf(LocalDate valuationDate) {
        return !maturityDate().isAfter(valuationDate);
    }
}
