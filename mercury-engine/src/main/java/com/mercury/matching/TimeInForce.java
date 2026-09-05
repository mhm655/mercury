package com.mercury.matching;

/**
 * What happens to the part of an order that cannot be filled immediately.
 *
 * <p>Kept to the two cases the engine genuinely needs. Fill-or-kill, good-till-date and
 * the rest are real order types, but each would add a branch in the matching loop for no
 * additional architectural insight, so they are deliberately absent rather than
 * half-implemented.
 */
public enum TimeInForce {

    /** Any unfilled remainder rests in the book until it trades or is cancelled. */
    GOOD_TILL_CANCEL("Good till cancel"),

    /**
     * Fill whatever is available now; cancel the rest. Market orders are always this,
     * since they cannot rest.
     */
    IMMEDIATE_OR_CANCEL("Immediate or cancel");

    private final String displayName;

    TimeInForce(String displayName) {
        this.displayName = displayName;
    }

    public boolean restsInBook() {
        return this == GOOD_TILL_CANCEL;
    }

    public String displayName() {
        return displayName;
    }
}
