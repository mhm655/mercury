package com.mercury.pricing;

import java.util.Objects;

/**
 * Names one pricing model, so an instrument can have several and a caller can choose.
 *
 * <p>A typed name rather than a bare {@code String} for the usual reason: it cannot be passed
 * where an instrument id or a currency code belongs. It is also the key that makes the
 * registry two-dimensional - instrument type, then model - which is what lets a European
 * option be priced by Black-Scholes today and cross-checked against a binomial tree later
 * without either model knowing about the other.
 */
public record ModelName(String value) {

    public ModelName {
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Model name must not be blank");
        }
    }

    public static ModelName of(String value) {
        return new ModelName(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
