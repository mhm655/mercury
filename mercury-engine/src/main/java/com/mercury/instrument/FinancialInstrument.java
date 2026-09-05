package com.mercury.instrument;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;

/**
 * Anything Mercury can hold a position in and put a value on.
 *
 * <h2>Why this interface is small</h2>
 * It declares only what <em>every</em> instrument needs for operations that treat
 * instruments uniformly:
 *
 * <ul>
 *   <li>{@link #id()} - identity, and the key positions are held under.</li>
 *   <li>{@link #currency()} - the currency its value is naturally expressed in, needed to
 *       build {@code Money} and to bucket FX exposure.</li>
 *   <li>{@link #assetClass()} - exposure reporting by asset class.</li>
 *   <li>{@link #tradability()} - which execution venue the instrument routes to.</li>
 * </ul>
 *
 * Notional, contract multiplier, maturity and coupon are deliberately absent. They do not
 * exist for every instrument, and inventing a common shape for them - a {@code notional()}
 * that returns 1 for a stock, an {@code Optional<LocalDate> maturity()} that is empty for
 * equities - produces an interface whose methods each mean something different depending
 * on the implementation. Those concerns live on capability interfaces
 * ({@link CashflowGenerating}, {@link Maturing}, {@link OptionTerms}, {@link HasUnderlying})
 * that an instrument implements only when the capability genuinely holds.
 *
 * <h2>Why there is no price() method here</h2>
 * Pricing is deliberately external. Putting {@code price()} on the instrument would give
 * each instrument exactly one valuation model, when the requirement is the opposite: an
 * option must be priceable by Black-Scholes <em>and</em> by a binomial tree, and the two
 * cross-checked against each other. It would also make every instrument depend on market
 * data and turn a value object into a service. See {@code DESIGN_PROPOSAL.md} section 5.1.
 *
 * <h2>Why this is not sealed</h2>
 * {@link com.mercury.core.id.DomainId} is sealed because identifiers are a closed set that
 * will not grow. Instruments are the opposite: the architecture's central claim is that a
 * new instrument can be added without modifying existing code, and sealing this interface
 * would make that false by construction. The M15 extensibility proof adds a sixth
 * instrument in a single commit precisely to demonstrate that.
 *
 * <p>Implementations must be immutable and thread-safe. Instrument definitions are read
 * concurrently by every Monte Carlo worker, and a mutable instrument would make a
 * simulation's result depend on thread timing.
 */
public interface FinancialInstrument {

    /** Unique identity. Also the key positions and market data are held under. */
    InstrumentId id();

    /**
     * The currency this instrument's value is naturally expressed in.
     *
     * <p>For an FX forward, which settles two currencies, this is the quote currency of its
     * pair - the currency the deal's value is conventionally measured in. Converting into a
     * portfolio's reporting currency is the portfolio layer's job, not the instrument's.
     */
    Currency currency();

    /** Asset class, for exposure aggregation and risk bucketing. */
    AssetClass assetClass();

    /**
     * Whether this instrument trades on an order book or is negotiated bilaterally.
     *
     * <p>This is what routes an order to the right {@code ExecutionVenue} without any
     * {@code instanceof} check. Swaps and forwards are OTC; running them through a
     * price-time-priority matching engine would be a domain error.
     */
    TradabilityProfile tradability();

    /** Short human-readable description, for blotters and terminal output. */
    String description();
}
