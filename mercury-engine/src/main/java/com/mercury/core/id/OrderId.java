package com.mercury.core.id;


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
        value = IdText.require(value, "Order id");
    }

    public static OrderId of(String value) {
        return new OrderId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
