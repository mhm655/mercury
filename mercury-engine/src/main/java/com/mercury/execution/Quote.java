package com.mercury.execution;

import com.mercury.core.money.Money;
import com.mercury.instrument.FinancialInstrument;
import java.time.Instant;
import java.util.Objects;

/**
 * A priced, time-limited offer to trade - the frozen output of
 * {@link OtcNegotiationVenue#quote}, later either {@link OtcNegotiationVenue#accept accepted}
 * at exactly this price or left to expire.
 *
 * <p>Carries everything {@link OtcNegotiationVenue#accept} needs to execute without re-pricing:
 * the instruction it was quoted for, the priced instrument, the consideration and trade-exposure
 * figures computed at quote time, and the instant it stops being honourable. Re-pricing at
 * accept time would defeat the entire point of quoting - a quote's price is frozen the moment
 * it is given, which is what {@link OtcNegotiationVenue#accept} relies on rather than calling
 * the pricing service again.
 *
 * <p>No credit or exposure check has happened yet - that is deferred entirely to
 * {@link OtcNegotiationVenue#accept}. See that method's javadoc for why.
 *
 * <p>Immutable and thread-safe.
 */
public record Quote(OtcInstruction instruction, FinancialInstrument instrument, Money consideration,
                     Money tradeExposure, Instant expiresAt) {

    public Quote {
        Objects.requireNonNull(instruction, "instruction");
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(consideration, "consideration");
        Objects.requireNonNull(tradeExposure, "tradeExposure");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /**
     * True once {@code now} has reached or passed {@link #expiresAt}. Right at the boundary
     * counts as expired - a quote is honourable strictly before its expiry instant, not up to
     * and including it.
     */
    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(expiresAt);
    }
}
