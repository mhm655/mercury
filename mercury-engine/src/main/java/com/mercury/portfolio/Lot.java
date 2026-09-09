package com.mercury.portfolio;

import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One acquisition: a quantity, what it cost, and when it was taken on.
 *
 * <h2>Cost is the negative of the cash that moved</h2>
 * Buying ten shares for 1,000 is a lot of {@code +10} at a cost of {@code +1,000}, and the
 * cash account falls by 1,000. Selling ten short for 1,000 is a lot of {@code -10} at a cost
 * of {@code -1,000}, and the cash account rises.
 *
 * <p>That sign convention is what lets one formula close either kind of position:
 *
 * <pre>
 *   realised = cash from the closing trade - cost basis consumed
 * </pre>
 *
 * Closing a long bought at 100 by selling at 110 gives {@code 110 - 100}. Closing a short sold
 * at 100 by buying at 90 gives {@code -90 - (-100)}. Both are a profit of ten, from the same
 * line of arithmetic - which is worth more than it looks, because short P&amp;L written as its
 * own special case is where sign errors live.
 *
 * <h2>Total cost, not unit cost</h2>
 * The lot stores what was actually paid rather than a price per unit. A per-unit figure would
 * have to be rounded to the currency's minor units, and multiplying that back by the quantity
 * reintroduces exactly the error ADR 0001 exists to prevent: an average unit cost of 195.5033
 * stored as 195.50 is 3.30 out across a thousand shares.
 *
 * <p>Splitting a lot therefore splits its cost, and the two halves are constructed to sum back
 * to the original - so no rounding is ever created or lost by a partial disposal.
 *
 * <p>Immutable and thread-safe.
 */
public record Lot(Quantity quantity, Money cost, LocalDate acquired) {

    public Lot {
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(acquired, "acquired");
        if (quantity.isZero()) {
            throw new IllegalArgumentException(
                    "A lot of nothing carries no cost and closes no position; acquiring zero "
                            + "is not a trade.");
        }
        if (quantity.isLong() && cost.isNegative()) {
            throw new IllegalArgumentException(
                    "A long lot of " + quantity + " cannot have been acquired for " + cost
                            + ". Cost is the negative of the cash that moved, so buying costs a "
                            + "positive amount and selling short costs a negative one.");
        }
        if (quantity.isShort() && cost.isPositive()) {
            throw new IllegalArgumentException(
                    "A short lot of " + quantity + " cannot have a cost of " + cost
                            + ". Selling short brings cash in, which this convention records as "
                            + "a negative cost.");
        }
    }

    @Override
    public String toString() {
        return "%s @ %s on %s".formatted(quantity, cost, acquired);
    }
}
