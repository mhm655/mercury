package com.mercury.instrument;

import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.Schedule;

/**
 * One side of a swap: a notional, a payment schedule, and a direction.
 *
 * <p>A leg is not itself a {@link FinancialInstrument}. It cannot be held, traded or
 * valued on its own - it exists only as part of a swap. Modelling it as a separate type
 * anyway is composition doing real work: a swap <em>has</em> two legs rather than
 * <em>being</em> a special kind of leg, and the vanilla fixed-float swap here becomes a
 * basis swap (two floating legs) or a cross-currency swap by changing which legs are
 * composed, not by subclassing.
 *
 * <p>The interface deliberately stops short of cashflows. {@link FixedRateLeg} knows its
 * amounts and implements {@link CashflowGenerating}; {@link FloatingRateLeg} cannot, because
 * its coupons depend on rates that have to be projected from a curve. That asymmetry is the
 * whole reason legs are separate types rather than one class with a nullable rate field.
 *
 * <p>Implementations must be immutable and thread-safe.
 */
public interface SwapLeg {

    /** The notional this leg accrues on. Always positive; direction is {@link #payReceive()}. */
    Money notional();

    /** Payment timetable. */
    Schedule schedule();

    /** Whether the holder pays or receives this leg. */
    PayReceive payReceive();

    /** How this leg accrues interest. */
    DayCountConvention dayCount();

    /** The leg's currency, taken from its notional. */
    default Currency currency() {
        return notional().currency();
    }
}
