package com.mercury.core;

/**
 * Base type for every domain error Mercury raises.
 *
 * <p>These are deliberately <em>unchecked</em>. Every subclass represents a programming
 * error or a violated domain invariant - adding money in two different currencies,
 * constructing a bond whose maturity precedes its issue date - not a recoverable
 * condition a caller could sensibly retry or work around. Forcing callers to declare
 * {@code throws} on arithmetic would add ceremony everywhere and buy nothing.
 *
 * <p>Genuinely recoverable outcomes are modelled as return values instead. A trade that
 * breaches a risk limit is not an exception: it is a {@code LimitCheckResult} carrying
 * the reason, because rejection is an expected business outcome rather than a defect.
 */
public abstract class MercuryException extends RuntimeException {

    protected MercuryException(String message) {
        super(message);
    }

    protected MercuryException(String message, Throwable cause) {
        super(message, cause);
    }
}
