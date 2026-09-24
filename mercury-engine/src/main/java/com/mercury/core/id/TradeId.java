package com.mercury.core.id;


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
        value = IdText.require(value, "Trade id");
    }

    public static TradeId of(String value) {
        return new TradeId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
