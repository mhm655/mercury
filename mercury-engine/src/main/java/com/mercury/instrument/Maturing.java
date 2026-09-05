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
 * <h2>Precisely which date this is</h2>
 * <b>The final payment date: the last date on which this instrument can produce a
 * cashflow.</b> Where a schedule rolls dates to business days, this is the <em>adjusted</em>
 * date, not the unadjusted contractual one.
 *
 * <p>Defining it loosely is not harmless. A bond maturing on a Saturday has a contractual
 * maturity of that Saturday but pays on the following Monday. If {@code maturityDate()}
 * returned the Saturday, then on that day the instrument would report itself matured while
 * still owing its principal - and any caller filtering out matured positions would silently
 * drop a position that still owes money. That defect existed here and is what prompted this
 * definition; {@code MaturityConsistencyTest} now enforces it across every instrument.
 *
 * <p>Instruments that also implement {@link CashflowGenerating} must therefore satisfy:
 * {@code cashflows(maturityDate())} is empty. Nothing can still be owed on or after the day
 * the instrument matures.
 *
 * <p>Where the unadjusted contractual date is separately meaningful, an instrument exposes it
 * under its own name - see {@link Bond#contractualMaturityDate()}.
 */
public interface Maturing {

    /**
     * The final payment date - the last date this instrument can produce a cashflow,
     * adjusted for business days where a schedule applies.
     *
     * <p>For an option this is its expiry; the concept is the same.
     */
    LocalDate maturityDate();

    /**
     * True if the instrument has no cashflows left as of {@code valuationDate}.
     *
     * <p>Inclusive of the maturity date itself: a payment made on that date has settled by
     * the end of it, and {@link Cashflow#isFutureAsOf} draws the boundary the same way.
     */
    default boolean hasMaturedAsOf(LocalDate valuationDate) {
        return !maturityDate().isAfter(valuationDate);
    }
}
