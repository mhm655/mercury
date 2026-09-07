package com.mercury.instrument;

import com.mercury.core.money.Money;
import java.time.LocalDate;

/**
 * Implemented by an instrument that earns interest continuously between payment dates.
 *
 * <h2>Why this is a capability and not a method on Bond</h2>
 * The accrued figure was already on {@code Bond}, computed and tested, with no consumer
 * anywhere outside its own tests. It needed one: the valuation report prints a bond's
 * <em>dirty</em> value - the present value of everything still to be paid - under the same
 * "UNIT VALUE" heading as a share price and an option premium, and bond markets quote clean.
 * Two of those three numbers mean the same thing and the third does not.
 *
 * <p>The presentation layer therefore has to ask "does this instrument accrue?". It could
 * have asked {@code instanceof Bond}, which is the one thing this project's design rules
 * out: a concrete-type test in a renderer is the first link of the chain that grows a branch
 * per instrument. Asking for a capability instead is the same question posed so that the
 * answer does not have to be enumerated - see ADR 0004.
 *
 * <h2>One implementor, and that is allowed</h2>
 * {@link Bond} is the only implementor today; {@code FixedRateLeg} is the obvious second when
 * swap pricing lands at M6. The interface is not justified by the count. It is justified by
 * being the only way to ask the question without naming the type, and by being a property of
 * the product rather than of bonds specifically - a floating-rate note accrues, and does not
 * implement {@link CashflowGenerating} at all.
 *
 * <p>Note that accrual is genuinely separate from {@code CashflowGenerating}: knowing what an
 * instrument will pay says nothing about how much of the current period the holder has
 * already earned.
 */
public interface AccruingInterest {

    /**
     * Interest earned but not yet paid as of {@code valuationDate} - what a buyer owes the
     * seller on top of the quoted clean price.
     *
     * <p>Zero outside the instrument's life, and zero on a payment date, when the period that
     * had been accruing has just settled.
     */
    Money accruedInterest(LocalDate valuationDate);
}
