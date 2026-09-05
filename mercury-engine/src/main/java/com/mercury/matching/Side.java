package com.mercury.matching;

/**
 * Which way an order or a position faces.
 *
 * <p>An earlier version carried a numeric sign, documented as making position arithmetic
 * branch-free. Nothing used it - the position layer that would have does not exist yet - so
 * it is gone. A signed helper can come back when there is arithmetic to simplify; until then
 * it is an unused field with a justification attached, which is worse than nothing.
 */
public enum Side {

    BUY("Buy"),
    SELL("Sell");

    private final String displayName;

    Side(String displayName) {
        this.displayName = displayName;
    }

    public boolean isBuy() {
        return this == BUY;
    }

    public String displayName() {
        return displayName;
    }
}
