package com.mercury.core.id;

import java.util.Objects;

/**
 * Identifies an executed trade, from booking through to settlement.
 *
 * <p>Examples: {@code TRD-000456}.
 *
 * <p>See {@link DomainId} for why these are distinct types rather than {@code String}.
 * Immutable and thread-safe.
 */
public record TradeId(String value) implements DomainId {

    public TradeId {
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Trade id must not be blank");
        }
    }

    public static TradeId of(String value) {
        return new TradeId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
