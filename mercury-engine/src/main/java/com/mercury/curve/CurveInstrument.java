package com.mercury.curve;

import java.time.LocalDate;

/**
 * A market quote a curve must reproduce.
 *
 * <h2>One question, asked of every quote</h2>
 * A bootstrapper does not care what kind of instrument it is fitting. It cares about exactly
 * one thing: given a candidate curve, how far is this quote from being repriced correctly?
 * That is {@link #repricingError}, and it is nearly the whole interface.
 *
 * <p>Keeping it this narrow is what stops {@code CurveBootstrapper} from growing a branch per
 * instrument type. A new quote - a futures contract, a forward rate agreement, a
 * cross-currency basis swap - is a new class and no change to the solver, which is the same
 * open-closed claim the pricing registry makes, applied to a second place in the engine.
 *
 * <h2>Why an error and not a price</h2>
 * The obvious alternative is {@code double priceUnder(YieldCurve)} plus a quoted price to
 * compare against. It would work, and it would push a subtraction into the solver that each
 * quote can do better itself: a deposit compares discount factors, a par swap compares a
 * present value against zero, and a bond would compare a price against par. Those are three
 * different subtractions, and the instrument is the thing that knows which one it means.
 *
 * <p>The sign is not specified and does not need to be. Bisection only needs the error to be
 * continuous and to change sign across the root, which every sensible quote satisfies.
 *
 * <h2>Why the quote names its own pillar date</h2>
 * The second method exists because of a defect. When {@code YieldCurve} keyed pillars by
 * tenor, a 2Y par swap quoted against a reference date of 28 June 2024 paid its final coupon
 * on Monday 29 June 2026, because the nominal date was a Sunday - one day past the pillar the
 * bootstrapper had placed for it. That last payment then interpolated between the 2Y and 5Y
 * points, so solving the 2Y depended on a 5Y that did not exist yet, and the finished curve
 * missed the 2Y swap by 1.5 basis points of present value.
 *
 * <p>A bootstrap step is only well posed if the quote's last cashflow lands exactly on the
 * pillar being solved for. Only the instrument knows where that is - it depends on its
 * schedule, its business-day convention and its calendar - so the instrument is asked.
 */
public interface CurveInstrument {

    /**
     * The date this quote determines: where its final cashflow actually settles.
     *
     * <p>Not the nominal tenor date. See the class javadoc for why the difference matters and
     * what it cost to find out.
     */
    LocalDate pillarDate(LocalDate referenceDate);

    /**
     * How far {@code curve} is from repricing this quote, in whatever units suit the quote.
     * Zero exactly when the curve is consistent with it.
     *
     * <p>Must be a continuous function of the curve's pillar values, and monotonic in the
     * pillar being solved for. Both hold for discounting instruments: raising a zero rate
     * lowers every discount factor beyond it, so the error moves one way only.
     */
    double repricingError(YieldCurve curve);

    /** Short label for diagnostics, so a failed bootstrap can name the quote that failed. */
    String describe();
}
