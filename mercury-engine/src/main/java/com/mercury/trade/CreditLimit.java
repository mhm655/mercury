package com.mercury.trade;

import com.mercury.core.money.Money;
import java.util.Objects;

/**
 * The maximum exposure Mercury is willing to carry against one {@link Counterparty}.
 *
 * <h2>What this does not decide yet</h2>
 * {@code maximum} is a stated figure and nothing here says what it is measured against.
 * Gross notional, mark-to-market exposure, and potential future exposure are three
 * different numbers for the same book, and choosing between them - along with the
 * pro-forma projection needed to check a proposed trade against whichever one is chosen -
 * is {@code RiskLimit} and breach-checking machinery arriving at M9
 * ({@code docs/DESIGN_PROPOSAL.md} section 10). This type is deliberately just the stated
 * ceiling, so a later reader does not mistake the bare {@link Money} field for a finished
 * design.
 *
 * <p>Immutable and thread-safe.
 */
public record CreditLimit(Money maximum) {

    public CreditLimit {
        Objects.requireNonNull(maximum, "maximum");
        if (!maximum.isPositive()) {
            throw new IllegalArgumentException(
                    "A credit limit must be a positive amount, but was " + maximum);
        }
    }

    @Override
    public String toString() {
        return maximum.toString();
    }
}
