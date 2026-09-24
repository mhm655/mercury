package com.mercury.core.id;


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
        value = IdText.require(value, "Counterparty id");
    }

    public static CounterpartyId of(String value) {
        return new CounterpartyId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
