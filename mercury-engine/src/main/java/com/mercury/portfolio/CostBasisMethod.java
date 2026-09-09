package com.mercury.portfolio;

import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * How a disposal decides which acquisitions it is closing.
 *
 * <h2>Why this is a real choice and not a detail</h2>
 * Buy 100 shares at 50, then 100 more at 90, then sell 100 at 100. The realised profit is
 * 5,000 under FIFO, 1,000 under LIFO, and 3,000 under average cost. Same trades, same market,
 * three different answers - all correct, because which lot you sold is a matter of policy
 * rather than of fact.
 *
 * <p>Tax authorities mandate one; accounting standards permit others; a desk may run one for
 * reporting and another for tax. So the method is a parameter of the book rather than a
 * constant of the code, and every disposal records which one produced it.
 *
 * <h2>An enum with behaviour</h2>
 * The same reasoning as {@code DayCountConvention} - see ADR 0002. The set is closed, each
 * member is a pure function over the same inputs, and an enum gives exhaustive switching, cheap
 * equality, and a name that round-trips through configuration.
 *
 * <h2>Cost is conserved</h2>
 * Every member splits a partially consumed lot so that the cost taken and the cost left behind
 * sum <em>exactly</em> to the cost that was there before. Rounding is done once, on the part
 * taken, and the remainder is the subtraction rather than a second rounding. A method that
 * rounded both halves independently would leak a cent per disposal into nowhere.
 */
public enum CostBasisMethod {

    /**
     * First in, first out: the oldest holdings are sold first.
     *
     * <p>The default in most jurisdictions, and the one that realises the most gain in a rising
     * market - the oldest lot is usually the cheapest.
     */
    FIRST_IN_FIRST_OUT("FIFO") {
        @Override
        Consumed consume(List<Lot> lots, Quantity amount) {
            return consumeInOrder(lots, amount, true);
        }
    },

    /**
     * Last in, first out: the most recent holdings are sold first.
     *
     * <p>Defers gain in a rising market, which is why several tax regimes disallow it.
     */
    LAST_IN_FIRST_OUT("LIFO") {
        @Override
        Consumed consume(List<Lot> lots, Quantity amount) {
            return consumeInOrder(lots, amount, false);
        }
    },

    /**
     * Every holding is treated as having cost the same weighted average.
     *
     * <p>Deliberately loses acquisition dates: after an averaging disposal the position is a
     * single lot, because that is what average cost <em>means</em> - the individual purchases
     * stop being distinguishable. Keeping the dates and averaging only the money would suggest
     * a lot structure the method does not have.
     */
    AVERAGE_COST("Average") {
        @Override
        Consumed consume(List<Lot> lots, Quantity amount) {
            Quantity total = totalQuantity(lots);
            Money totalCost = totalCost(lots);

            BigDecimal fraction = amount.value().abs()
                    .divide(total.value().abs(), MathContext.DECIMAL128);
            Money taken = totalCost.multipliedBy(fraction);

            Quantity left = Quantity.of(total.value().subtract(amount.value()));
            if (left.isZero()) {
                return new Consumed(totalCost, List.of());
            }
            // The remainder is a subtraction, not a second rounding, so cost is conserved.
            Lot remaining = new Lot(left, totalCost.minus(taken), latestDate(lots));
            return new Consumed(taken, List.of(remaining));
        }
    };

    private final String displayName;

    CostBasisMethod(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Removes {@code amount} from {@code lots} and reports what it cost.
     *
     * <p>{@code amount} carries the same sign as the lots - a long position is reduced by a
     * positive amount - and its magnitude must not exceed the position's. Both are the
     * caller's responsibility; {@link PositionLots} is the caller, and checks.
     */
    abstract Consumed consume(List<Lot> lots, Quantity amount);

    /** What a disposal took, and what it left behind. */
    record Consumed(Money cost, List<Lot> remaining) {
    }

    private static Consumed consumeInOrder(List<Lot> lots, Quantity amount, boolean fromOldest) {
        List<Lot> ordered = new ArrayList<>(lots);
        if (!fromOldest) {
            ordered = ordered.reversed();
        }

        Money cost = Money.zero(lots.get(0).cost().currency());
        BigDecimal outstanding = amount.value().abs();
        List<Lot> remaining = new ArrayList<>(ordered.size());

        for (Lot lot : ordered) {
            BigDecimal lotSize = lot.quantity().value().abs();
            if (outstanding.signum() == 0) {
                remaining.add(lot);
            } else if (outstanding.compareTo(lotSize) >= 0) {
                cost = cost.plus(lot.cost());
                outstanding = outstanding.subtract(lotSize);
            } else {
                BigDecimal fraction = outstanding.divide(lotSize, MathContext.DECIMAL128);
                Money taken = lot.cost().multipliedBy(fraction);
                cost = cost.plus(taken);

                BigDecimal leftSize = lotSize.subtract(outstanding);
                Quantity left = Quantity.of(
                        lot.quantity().isShort() ? leftSize.negate() : leftSize);
                remaining.add(new Lot(left, lot.cost().minus(taken), lot.acquired()));
                outstanding = BigDecimal.ZERO;
            }
        }
        // Put the survivors back in acquisition order, so the next disposal sees the same
        // ordering this one did regardless of which end it consumed from.
        return new Consumed(cost, fromOldest ? List.copyOf(remaining)
                : List.copyOf(remaining.reversed()));
    }

    private static Quantity totalQuantity(List<Lot> lots) {
        return lots.stream().map(Lot::quantity).reduce(Quantity.ZERO, Quantity::plus);
    }

    private static Money totalCost(List<Lot> lots) {
        Money zero = Money.zero(lots.get(0).cost().currency());
        return lots.stream().map(Lot::cost).reduce(zero, Money::plus);
    }

    private static java.time.LocalDate latestDate(List<Lot> lots) {
        return lots.stream()
                .map(Lot::acquired)
                .max(java.time.LocalDate::compareTo)
                .orElseThrow(() -> new IllegalStateException("no lots"));
    }
}
