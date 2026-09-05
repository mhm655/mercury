package com.mercury.matching;

import com.mercury.core.id.OrderId;
import java.util.List;
import java.util.Objects;

/**
 * What happened when an order was submitted.
 *
 * <p>Returned as a value rather than signalled by exception or callback: an order that
 * finds no liquidity, or that fills only partially, is an ordinary business outcome and not
 * an error. The same reasoning applies here as to risk-limit rejections - see
 * {@code MercuryException}.
 *
 * <p>Immutable and thread-safe.
 */
public record MatchResult(
        OrderId orderId,
        OrderStatus status,
        List<Fill> fills,
        long filledQuantity,
        long restingQuantity) {

    public MatchResult {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(fills, "fills");
        fills = List.copyOf(fills);

        if (filledQuantity < 0 || restingQuantity < 0) {
            throw new IllegalArgumentException(
                    "Quantities must not be negative: filled=" + filledQuantity
                            + " resting=" + restingQuantity);
        }
        long fillTotal = fills.stream().mapToLong(Fill::quantity).sum();
        if (fillTotal != filledQuantity) {
            throw new IllegalArgumentException(
                    "Filled quantity " + filledQuantity + " disagrees with the sum of its fills "
                            + fillTotal + "; a match result must account for every unit it claims");
        }
    }

    /** True if any quantity traded. */
    public boolean hasFills() {
        return !fills.isEmpty();
    }

    /** True if the order is now queued in the book. */
    public boolean isResting() {
        return restingQuantity > 0;
    }

    /** The volume-weighted average price of this order's executions, or empty if none. */
    public java.util.Optional<java.math.BigDecimal> averageFillPrice() {
        if (fills.isEmpty()) {
            return java.util.Optional.empty();
        }
        java.math.BigDecimal notional = java.math.BigDecimal.ZERO;
        for (Fill fill : fills) {
            notional = notional.add(
                    fill.price().value().multiply(java.math.BigDecimal.valueOf(fill.quantity())));
        }
        return java.util.Optional.of(notional.divide(
                java.math.BigDecimal.valueOf(filledQuantity), 8, java.math.RoundingMode.HALF_EVEN));
    }

    @Override
    public String toString() {
        return "%s: %s, filled %d, resting %d".formatted(
                orderId, status.displayName(), filledQuantity, restingQuantity);
    }
}
