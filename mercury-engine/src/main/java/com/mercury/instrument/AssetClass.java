package com.mercury.instrument;

/**
 * The broad risk family an instrument belongs to.
 *
 * <p>Used for exposure aggregation and for deciding which market shocks are relevant: an
 * equity scenario moves {@link #EQUITY} positions, a rate shock moves {@link #RATES}. A
 * closed set fixed by market structure, so an enum rather than an extension point.
 *
 * <p>Note this is a property of the instrument, not of its risk. A European option on a
 * stock is {@link #EQUITY} even though it also has interest-rate and volatility
 * sensitivity - the Greeks describe that, and one instrument having several sensitivities
 * is exactly why risk is computed by revaluation rather than read off a label.
 */
public enum AssetClass {

    /** Shares and equity derivatives. */
    EQUITY("Equity"),

    /** Interest-rate products: bonds, swaps, deposits. */
    RATES("Rates"),

    /** Foreign exchange: spot, forwards, swaps. */
    FX("FX"),

    /** Credit-sensitive products. Reserved; no instrument uses it yet. */
    CREDIT("Credit");

    private final String displayName;

    AssetClass(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
