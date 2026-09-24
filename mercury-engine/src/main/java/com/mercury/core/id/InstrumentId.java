package com.mercury.core.id;


/**
 * Identifies an instrument (a stock, bond, option, forward or swap).
 *
 * <p>Examples: {@code AAPL, US0378331005, EURUSD-FWD-3M}.
 *
 * <p>See {@link DomainId} for why these are distinct types rather than {@code String}.
 * Immutable and thread-safe.
 */
public record InstrumentId(String value) implements DomainId {

    public InstrumentId {
        value = IdText.require(value, "Instrument id");
    }

    public static InstrumentId of(String value) {
        return new InstrumentId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
