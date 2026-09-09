package com.mercury.instrument;

import com.mercury.core.id.InstrumentId;

/**
 * Implemented by derivatives whose value depends on another instrument.
 *
 * <p>The underlying is referenced <em>by identity</em>, not held as an object. Two reasons:
 *
 * <ul>
 *   <li>An instrument definition stays a small immutable value rather than the root of an
 *       object graph, which matters when definitions are shared across Monte Carlo
 *       workers.</li>
 *   <li>Market data is keyed by {@link InstrumentId}, so a pricer that needs the
 *       underlying's spot price looks it up in the snapshot it was given - which keeps
 *       pricing a pure function of instrument plus market data, with no hidden traversal
 *       into shared mutable state.</li>
 * </ul>
 *
 * The cost is that a reference can dangle: nothing here guarantees the underlying exists.
 * That is checked where instruments are registered, not on every construction.
 *
 * <h2>No reader yet</h2>
 * One implementor at M7, and {@code BlackScholesModel} reaches for {@code underlyingId()} on
 * the concrete {@link EuropeanOption} rather than through this interface - so the abstraction
 * currently buys nothing that the concrete type would not. It is kept because a second
 * derivative type makes it load-bearing immediately, and deleted if M8 comes and goes without
 * one. Naming that here is cheaper than leaving the next reader to work it out.
 */
public interface HasUnderlying {

    /** Identity of the instrument this derivative is written on. */
    InstrumentId underlyingId();
}
