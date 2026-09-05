package com.mercury.core.id;

/**
 * Marker for Mercury's typed identifiers.
 *
 * <h2>Why not just use String</h2>
 * Every identifier in this system is a string underneath, which means without distinct
 * types they are all mutually assignable. A method
 * {@code book(String portfolioId, String instrumentId)} accepts its arguments in either
 * order, compiles cleanly, and fails at runtime - or worse, silently books against the
 * wrong thing. Distinct record types make that a compile error.
 *
 * <p>The secondary benefit is readability: {@code Map<InstrumentId, Position>} says what
 * it holds, where {@code Map<String, Position>} says only that something is keyed by
 * something.
 *
 * <p>The cost is a handful of near-identical records. That is a deliberate trade - they
 * are a few lines each, they never change, and they eliminate an entire class of bug.
 *
 * <p>Implementations are immutable value types; two identifiers of the same type with the
 * same value are equal, and identifiers of different types are never equal regardless of
 * value.
 */
public sealed interface DomainId
        permits InstrumentId, OrderId, TradeId, PortfolioId, CounterpartyId {

    /** The underlying identifier text. */
    String value();
}
