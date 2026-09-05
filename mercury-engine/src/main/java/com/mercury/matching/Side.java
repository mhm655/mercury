package com.mercury.matching;

/**
 * Which way an order or a position faces.
 *
 * <p>Carries a sign so that applying a fill to a position is arithmetic rather than a
 * branch: a buy adds quantity, a sell subtracts it, and one expression covers both.
 */
public enum Side {

    BUY("Buy", 1),
    SELL("Sell", -1);

    private final String displayName;
    private final int sign;

    Side(String displayName, int sign) {
        this.displayName = displayName;
        this.sign = sign;
    }

    /** {@code +1} for a buy, {@code -1} for a sell. */
    public int sign() {
        return sign;
    }

    /** The side an order must be to trade against this one. */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }

    public boolean isBuy() {
        return this == BUY;
    }

    public boolean isSell() {
        return this == SELL;
    }

    public String displayName() {
        return displayName;
    }
}
