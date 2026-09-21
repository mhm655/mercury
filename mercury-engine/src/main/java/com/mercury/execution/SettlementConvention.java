package com.mercury.execution;

import java.time.LocalDate;

/**
 * The one settlement lag both venues use, so a reader never has to ask why a CLOB trade and an
 * OTC trade settle on different schedules - they do not, on purpose.
 *
 * <h2>T+2 calendar days, not business days</h2>
 * Regular-way settlement in most cash markets is a fixed number of days after trade date - two,
 * historically, in the markets this engine's conventions are modelled on. Rolled against a
 * {@code HolidayCalendar} the way a bond's coupon schedule already is would be the more
 * complete answer, but this milestone is about the scheduler - whether a trade due to settle
 * actually gets there automatically ({@code docs/KNOWN_GAPS.md}'s "Settlement scheduling"
 * entry) - not about which calendar convention decides the date. Calendar days is a stated
 * simplification, the same shape as this codebase's existing single-curve and vanilla-swap
 * ones, not an oversight.
 *
 * <p>Before M19, neither {@link OrderBookVenue} nor {@link OtcNegotiationVenue} ever populated
 * {@code Trade.settlementDate()} at all - every trade carried {@code Optional.empty()}. A
 * settlement scheduler has nothing to schedule against an empty date, so giving every minted
 * trade a real one is this milestone's prerequisite, not a separate concern bolted on beside
 * it.
 */
final class SettlementConvention {

    private static final int LAG_DAYS = 2;

    private SettlementConvention() {
    }

    /** {@code tradeDate}, {@value #LAG_DAYS} calendar days forward. */
    static LocalDate settlementDateFor(LocalDate tradeDate) {
        return tradeDate.plusDays(LAG_DAYS);
    }
}
