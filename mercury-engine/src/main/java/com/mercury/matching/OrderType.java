package com.mercury.matching;

/**
 * Whether an order names a price limit.
 *
 * <p>The distinction is not cosmetic: a limit order can rest in the book because it says
 * what it is willing to pay, whereas a market order cannot rest at all. Having no price,
 * it has no place in a price-ordered book, so any unfilled remainder is cancelled rather
 * than queued.
 */
public enum OrderType {

    /** Executes only at the stated limit price or better. May rest in the book. */
    LIMIT,

    /**
     * Executes against whatever liquidity exists, at any price. Never rests: an unfilled
     * remainder is cancelled, because there is no price at which to queue it.
     */
    MARKET;


}
