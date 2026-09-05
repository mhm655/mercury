package com.mercury.core.id;

import java.util.Objects;

/**
 * Identifies a counterparty Mercury faces on OTC trades.
 *
 * <p>Examples: {@code CPTY-ACME}.
 *
 * <p>See {@link DomainId} for why these are distinct types rather than {@code String}.
 * Immutable and thread-safe.
 */
public record CounterpartyId(String value) implements DomainId {

    public CounterpartyId {
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Counterparty id must not be blank");
        }
    }

    public static CounterpartyId of(String value) {
        return new CounterpartyId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
