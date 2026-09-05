package com.mercury.core.money;

import com.mercury.core.MercuryException;

/**
 * Thrown when an operation combines {@link Money} amounts in different currencies.
 *
 * <p>Mercury never converts implicitly. Silent coercion inside an arithmetic operator -
 * {@code usdAmount.plus(eurAmount)} quietly returning something - is a real production
 * bug class in trading systems: it produces a number that looks plausible, reconciles
 * against nothing, and is discovered days later. Conversion must be explicit and must
 * name the rate it used.
 */
public final class CurrencyMismatchException extends MercuryException {

    private final Currency left;
    private final Currency right;

    public CurrencyMismatchException(String operation, Currency left, Currency right) {
        super("Cannot %s amounts in different currencies: %s and %s. "
                        .formatted(operation, left.code(), right.code())
                + "Convert explicitly through an FX rate first.");
        this.left = left;
        this.right = right;
    }

    public Currency left() {
        return left;
    }

    public Currency right() {
        return right;
    }
}
