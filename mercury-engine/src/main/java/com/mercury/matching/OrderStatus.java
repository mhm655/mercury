package com.mercury.matching;

/** The outcome of submitting an order to the book. */
public enum OrderStatus {

    /** No quantity traded; the whole order is queued. */
    RESTING("Resting"),

    /** Part traded immediately; the remainder is queued. */
    PARTIALLY_FILLED_RESTING("Partially filled, resting"),

    /** The entire quantity traded. */
    FILLED("Filled"),

    /** Part traded; the remainder was cancelled rather than queued. */
    PARTIALLY_FILLED_CANCELLED("Partially filled, remainder cancelled"),

    /** Nothing traded and nothing rests - no liquidity, and the order could not queue. */
    CANCELLED("Cancelled");

    private final String displayName;

    OrderStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
