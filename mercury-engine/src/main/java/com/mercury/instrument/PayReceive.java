package com.mercury.instrument;

/**
 * Which side of a swap leg the holder is on.
 *
 * <p>"Paying fixed" and "receiving fixed" produce the same schedule and the same gross
 * amounts, differing only in the sign of the resulting cashflow. The legs apply that by
 * negating when the leg is paid.
 *
 * <p>This enum previously carried a numeric sign, documented as letting cashflows be
 * "flipped by multiplication rather than by branching" - while both legs did in fact branch
 * on the constant, and nothing ever called the accessor. The documentation described a design
 * the code did not implement, so the field is gone and this note records why.
 */
public enum PayReceive {

    /** The holder pays this leg; its cashflows are negative. */
    PAY("Pay"),

    /** The holder receives this leg; its cashflows are positive. */
    RECEIVE("Receive");

    private final String displayName;

    PayReceive(String displayName) {
        this.displayName = displayName;
    }

    public PayReceive opposite() {
        return this == PAY ? RECEIVE : PAY;
    }

    public String displayName() {
        return displayName;
    }
}
