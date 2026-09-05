package com.mercury.core.id;

import java.util.Objects;

/**
 * Identifies a single order resting in, or passing through, an order book.
 *
 * <p>Examples: {@code ORD-000123}.
 *
 * <p>See {@link DomainId} for why these are distinct types rather than {@code String}.
 * Immutable and thread-safe.
 */
public record OrderId(String value) implements DomainId {

    public OrderId {
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Order id must not be blank");
        }
    }

    public static OrderId of(String value) {
        return new OrderId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
