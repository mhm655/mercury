package com.mercury.instrument;

/**
 * Which side of a swap leg the holder is on.
 *
 * <p>Carries a sign so that a leg's cashflows can be built once and flipped by
 * multiplication rather than by branching. "Paying fixed" and "receiving fixed" produce
 * the same schedule and the same amounts, differing only in sign.
 */
public enum PayReceive {

    /** The holder pays this leg; its cashflows are negative. */
    PAY("Pay", -1),

    /** The holder receives this leg; its cashflows are positive. */
    RECEIVE("Receive", 1);

    private final String displayName;
    private final int sign;

    PayReceive(String displayName, int sign) {
        this.displayName = displayName;
        this.sign = sign;
    }

    /** {@code -1} for pay, {@code +1} for receive. */
    public int sign() {
        return sign;
    }

    public PayReceive opposite() {
        return this == PAY ? RECEIVE : PAY;
    }

    public String displayName() {
        return displayName;
    }
}
