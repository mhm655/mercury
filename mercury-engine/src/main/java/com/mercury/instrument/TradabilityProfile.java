package com.mercury.instrument;

/**
 * How an instrument reaches the market.
 *
 * <p>This exists to settle a genuine domain distinction that the naive design gets wrong.
 * A central limit order book matches anonymous orders by price-time priority - which is
 * how equities trade, and is not how swaps, FX forwards or OTC options trade. Those are
 * negotiated bilaterally with a named counterparty, at a price quoted on request.
 *
 * <p>Routing on this property means an order reaches the right venue through ordinary
 * polymorphism rather than an {@code instanceof} chain over instrument types, and it means
 * adding a new instrument does not require touching the routing logic - the new instrument
 * simply declares which kind of thing it is.
 *
 * <p>See {@code DESIGN_PROPOSAL.md} section A2.1.
 */
public enum TradabilityProfile {

    /**
     * Trades on an order book: anonymous, price-time priority, partial fills, no
     * counterparty selection. Equities and listed bonds.
     */
    EXCHANGE_TRADED("Exchange traded"),

    /**
     * Negotiated bilaterally against a named counterparty. Priced on request rather than
     * matched, and carries counterparty credit exposure that an exchange-traded position
     * does not.
     */
    OVER_THE_COUNTER("Over the counter");

    private final String displayName;

    TradabilityProfile(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** True if orders in this instrument belong in a matching engine. */
    public boolean isExchangeTraded() {
        return this == EXCHANGE_TRADED;
    }

    /** True if executing this instrument creates counterparty exposure. */
    public boolean hasCounterpartyRisk() {
        return this == OVER_THE_COUNTER;
    }
}
