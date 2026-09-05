package com.mercury.core.id;

import java.util.Objects;

/**
 * Identifies a portfolio of positions and cash.
 *
 * <p>Examples: {@code GLOBAL-MACRO}.
 *
 * <p>See {@link DomainId} for why these are distinct types rather than {@code String}.
 * Immutable and thread-safe.
 */
public record PortfolioId(String value) implements DomainId {

    public PortfolioId {
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Portfolio id must not be blank");
        }
    }

    public static PortfolioId of(String value) {
        return new PortfolioId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
