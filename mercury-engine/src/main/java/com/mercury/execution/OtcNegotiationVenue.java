package com.mercury.execution;

import com.mercury.core.MercuryException;
import com.mercury.core.id.CounterpartyId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.event.EventBus;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.ValuationResult;
import com.mercury.risk.LimitCheckResult;
import com.mercury.risk.RiskLimit;
import com.mercury.trade.Counterparty;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeExecuted;
import com.mercury.trade.TradeStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The OTC {@link ExecutionVenue}: prices an instrument, applies a spread, and books a
 * bilateral {@link Trade} against a named {@link com.mercury.trade.Counterparty} - the
 * request-for-quote model {@code docs/DESIGN_PROPOSAL.md} section A2.1 describes.
 *
 * <h2>Quote and execution happen in one call</h2>
 * A real RFQ workflow separates a quoted, expiring price from a later accept. This venue
 * collapses the two - it prices and executes atomically - which is a stated simplification
 * in the same spirit as the project's existing "vanilla fixed-float swap, single-curve"
 * simplifications, not an oversight. See {@code docs/KNOWN_GAPS.md}.
 *
 * <h2>The spread is a percentage of the priced mid, and that has a real limit</h2>
 * Scaling the mid by {@code (1 +/- spread)} is the right convention for anything quoted as a
 * clean per-unit price - a stock, a bond, an option premium. It is the wrong convention for
 * a net-present-value instrument like a swap or a forward, whose priced value can be zero or
 * close to it: a real desk quotes those with a spread on the *rate*, not as a fraction of the
 * NPV, precisely because a percentage of a number near zero is a number near zero, regardless
 * of how wide the requested spread was. This venue does not yet have a rate-based spread
 * convention - see {@code docs/KNOWN_GAPS.md} - so rather than silently booking a trade that
 * claims to have applied a spread and has not, {@link #execute} refuses one whenever the
 * spread had no measurable effect on the consideration. Loud beats plausible, the same rule
 * {@code MarketDataSnapshot} and {@code SwapModel} already follow for missing data.
 *
 * <h2>{@code ownBook}</h2>
 * Every {@link Trade} needs an {@code owner} - whose ledger it affects. A CLOB trade's
 * owner is whichever participant's order it came from; an OTC trade is always Mercury's own
 * book on one side and the named counterparty on the other, so {@code ownBook} is supplied
 * once at construction rather than per instruction.
 *
 * <h2>M9: a pre-trade credit check</h2>
 * Every negotiation is checked against {@code riskLimit} before it is allowed to execute -
 * {@code docs/DESIGN_PROPOSAL.md} section A2.6's "evaluate limits against the portfolio as it
 * would be if the trade executed." The projection is cheap because nothing is committed until
 * the check clears: the running gross notional traded against each counterparty, in that
 * counterparty's own {@code CreditLimit} currency, is read to form the projected total, and
 * only written to once a trade actually executes.
 *
 * <p>Only OTC trades are checked. A CLOB fill has no named counterparty to check - see
 * {@code TradabilityProfile} - so a counterparty exposure limit has nothing to apply to there.
 *
 * <h2>M18: the running total lives in a shared {@link ExposureLedger}, not on this venue</h2>
 * Through M17 the running exposure was this venue's own instance state - correct only as long
 * as exactly one {@code OtcNegotiationVenue} ever trades against a given counterparty, which
 * was true of every wiring in this codebase but stopped being guaranteed the moment a second
 * venue instance could exist against overlapping counterparties. {@link ExposureLedger} is
 * that state, extracted so it can be constructed once and shared - see its own javadoc for the
 * full reasoning. This venue still owns the sequence a credit check needs (read, check, mint a
 * trade on approval, commit, publish), all under {@link ExposureLedger#lock()}; the ledger owns
 * only the state and its own {@link ExposureLedger#release}.
 *
 * <h2>Exposure is released on settlement, not held forever</h2>
 * The running total is gross notional, not a netted mark-to-market figure (see
 * {@code docs/KNOWN_GAPS.md}) - but a trade that has actually settled is no longer a
 * counterparty risk anything carries, so {@link ExposureLedger#release} exists to take it back
 * out. Nothing calls it automatically: {@code docs/KNOWN_GAPS.md}'s "Settlement scheduling"
 * entry already establishes that driving a trade to {@code SETTLED} is a caller's explicit
 * action, and releasing the exposure it created follows the same rule rather than inventing a
 * different one. Without this, the limit would be a lifetime trading-volume cap rather than
 * anything resembling live credit exposure - every counterparty would eventually exhaust it
 * permanently regardless of how healthy the relationship actually is.
 *
 * <p>A breach does not throw. {@link #negotiate} returns a {@link NegotiationResult} carrying
 * either the executed trade or the breaches that stopped it - {@code MercuryException}'s own
 * javadoc names this as the shape a risk-limit breach should take, an expected business outcome
 * rather than a defect. {@link #execute}, the shared {@link ExecutionVenue} method, forwards
 * only {@link NegotiationResult#trades()} - empty on a breach - exactly as
 * {@code OrderBookVenue.execute} forwards only {@code MatchResult.fills()} and drops
 * {@code selfTradePrevented()}.
 */
public final class OtcNegotiationVenue implements ExecutionVenue<OtcInstruction> {

    private final PricingService pricingService;
    private final MarketDataSnapshot market;
    private final InstrumentCatalog instruments;
    private final TradeIdGenerator tradeIdGenerator;
    private final CounterpartyId ownBook;
    private final CounterpartyDirectory counterparties;
    private final RiskLimit riskLimit;
    private final EventBus events;
    private final ExposureLedger exposureLedger;

    /** A venue nothing listens to, with exposure private to it; every execution is still
     *  returned to the caller. */
    public OtcNegotiationVenue(PricingService pricingService, MarketDataSnapshot market,
                               InstrumentCatalog instruments, TradeIdGenerator tradeIdGenerator,
                               CounterpartyId ownBook, CounterpartyDirectory counterparties,
                               RiskLimit riskLimit) {
        this(pricingService, market, instruments, tradeIdGenerator, ownBook, counterparties,
                riskLimit, EventBus.ignoring());
    }

    /**
     * @param events every executed negotiation is published here as a {@link TradeExecuted};
     *               a rejected one publishes nothing, because nothing executed
     */
    public OtcNegotiationVenue(PricingService pricingService, MarketDataSnapshot market,
                               InstrumentCatalog instruments, TradeIdGenerator tradeIdGenerator,
                               CounterpartyId ownBook, CounterpartyDirectory counterparties,
                               RiskLimit riskLimit, EventBus events) {
        this(pricingService, market, instruments, tradeIdGenerator, ownBook, counterparties,
                riskLimit, events, new ExposureLedger());
    }

    /**
     * @param exposureLedger where the running counterparty exposure this venue checks against
     *                       lives - construct one and pass the same instance to every venue
     *                       that should share a counterparty's limit against it (M18); the
     *                       other constructors give each venue a private one, which is correct
     *                       as long as only one venue ever trades against a given counterparty
     */
    public OtcNegotiationVenue(PricingService pricingService, MarketDataSnapshot market,
                               InstrumentCatalog instruments, TradeIdGenerator tradeIdGenerator,
                               CounterpartyId ownBook, CounterpartyDirectory counterparties,
                               RiskLimit riskLimit, EventBus events, ExposureLedger exposureLedger) {
        this.events = Objects.requireNonNull(events, "events");
        this.pricingService = Objects.requireNonNull(pricingService, "pricingService");
        this.market = Objects.requireNonNull(market, "market");
        this.instruments = Objects.requireNonNull(instruments, "instruments");
        this.tradeIdGenerator = Objects.requireNonNull(tradeIdGenerator, "tradeIdGenerator");
        this.ownBook = Objects.requireNonNull(ownBook, "ownBook");
        this.counterparties = Objects.requireNonNull(counterparties, "counterparties");
        this.riskLimit = Objects.requireNonNull(riskLimit, "riskLimit");
        this.exposureLedger = Objects.requireNonNull(exposureLedger, "exposureLedger");
    }

    @Override
    public List<Trade> execute(OtcInstruction otc, SimulationClock clock) {
        return negotiate(otc, clock).trades();
    }

    /**
     * Prices and negotiates {@code otc}, checking the projected exposure it would create
     * against {@code riskLimit} before anything executes.
     *
     * @throws SpreadHadNoEffectException if a nonzero requested spread had no measurable effect
     * @throws CounterpartyDirectory.UnknownCounterpartyException if {@code otc} names a
     *         counterparty this venue does not know
     */
    public NegotiationResult negotiate(OtcInstruction otc, SimulationClock clock) {
        Objects.requireNonNull(otc, "otc");
        Objects.requireNonNull(clock, "clock");

        FinancialInstrument instrument = instruments.require(otc.instrumentId());
        ValuationResult priced = pricingService.price(instrument, market, clock.today());
        Currency currency = priced.currency();

        // Buyer pays above the priced mid, seller receives below it - the spread this venue
        // exists to apply. Formed entirely in the model (double) domain and crossed into
        // Money exactly once, per ADR 0001: rounding the unit price to the currency's minor
        // units before multiplying by quantity would scale a rounding error by the trade
        // size instead of rounding the final total once.
        boolean buy = otc.side().isBuy();
        double quantity = otc.quantity().value().doubleValue();
        double spreadFactor = buy ? 1.0 + otc.spread().asDecimal() : 1.0 - otc.spread().asDecimal();
        double notional = priced.value() * spreadFactor * quantity;
        Money consideration = Money.fromModelValue(buy ? notional : -notional, currency);

        // A spread that had no measurable effect - almost always because the priced mid was
        // zero or close enough to it that scaling it by a percentage rounds away to nothing -
        // is refused rather than silently booked. See the class javadoc.
        // Compared by value, not equals(BasisPoints.ZERO): record equality on a double uses
        // Double.compare, which treats -0.0 as a different spread from 0.0.
        if (otc.spread().value() != 0.0) {
            double unspreadNotional = priced.value() * quantity;
            Money withoutSpread = Money.fromModelValue(buy ? unspreadNotional : -unspreadNotional,
                    currency);
            if (consideration.equals(withoutSpread)) {
                throw new SpreadHadNoEffectException(instrument, priced, otc.spread());
            }
        }

        Counterparty counterparty = counterparties.require(otc.counterparty());
        Currency limitCurrency = counterparty.creditLimit().maximum().currency();
        // A risk check, not a ledger fact: converting an already-rounded consideration into
        // the limit's currency here (rather than forming the product once, as
        // PortfolioValuationService does for the ledger) is fine - a cent of double-rounding
        // does not change which side of a credit limit a trade lands on.
        double exposureInLimitCurrency =
                consideration.abs().amount().doubleValue() * market.fxRate(currency, limitCurrency);
        Money tradeExposure = Money.fromModelValue(exposureInLimitCurrency, limitCurrency);
        Quantity delta = buy ? otc.quantity() : Quantity.of(otc.quantity().value().negate());

        synchronized (exposureLedger.lock()) {
            Money projectedExposure = exposureTo(counterparty.id()).plus(tradeExposure);
            LimitCheckResult check = riskLimit.check(counterparty, projectedExposure);
            if (check.isBreached()) {
                return NegotiationResult.rejected(check.breaches());
            }

            Trade trade = Trade.newTrade(tradeIdGenerator.next(), instrument.id(), ownBook, delta,
                    consideration, clock.today(),
                    Optional.of(SettlementConvention.settlementDateFor(clock.today())),
                    Optional.of(otc.counterparty()));
            trade = trade.transitionTo(TradeStatus.VALIDATED, "priced on request", clock)
                    .transitionTo(TradeStatus.BOOKED, "booked to the ledger", clock)
                    .transitionTo(TradeStatus.EXECUTED, "negotiated against " + otc.counterparty(), clock);

            exposureLedger.commit(counterparty.id(), projectedExposure, trade, tradeExposure);

            // Announced under the same lock that committed the exposure, so a subscriber can
            // never see a trade the venue has not yet counted against its counterparty's
            // limit. A rejected negotiation publishes nothing: nothing executed.
            events.publish(new TradeExecuted(trade));
            return NegotiationResult.executed(trade);
        }
    }

    /**
     * The running gross notional traded against {@code counterparty} so far, in that
     * counterparty's own {@code CreditLimit} currency - zero if nothing has executed against
     * it yet. Forwards to this venue's {@link ExposureLedger}, shared or private depending on
     * which constructor built this venue.
     *
     * <p>Exists so a caller can ask "how much room is left" without attempting a trade first.
     * Read-only: this never itself checks or mutates anything, so calling it has no effect on
     * a later {@link #negotiate}.
     *
     * @throws CounterpartyDirectory.UnknownCounterpartyException if this venue does not know
     *         {@code counterparty}
     */
    public Money exposureTo(CounterpartyId counterparty) {
        Objects.requireNonNull(counterparty, "counterparty");
        Currency limitCurrency = counterparties.require(counterparty).creditLimit().maximum().currency();
        return exposureLedger.exposureTo(counterparty, limitCurrency);
    }

    /**
     * Takes {@code trade}'s recorded contribution back out of the running exposure it added
     * when it executed - forwards to this venue's {@link ExposureLedger}; see that class's
     * javadoc for the trust model and why the amount released comes from its own records
     * rather than from {@code trade.consideration()}. A {@link TradeStatus#CANCELLED} trade
     * releases too: it will never settle, so accepting only {@code SETTLED} would leave a
     * withdrawn trade counted against the limit for good.
     *
     * @throws IllegalArgumentException if {@code trade} is neither {@link TradeStatus#SETTLED}
     *         nor {@link TradeStatus#CANCELLED}, was never recorded as exposure by this venue's
     *         own {@link #negotiate}, is not a continuation of the trade it executed, or has
     *         already been released
     */
    public void release(Trade trade) {
        exposureLedger.release(trade);
    }

    /** How many trades' exposure is still counted. Package-private, for tests of boundedness. */
    int openExposureCount() {
        return exposureLedger.openExposureCount();
    }

    /**
     * Raised when a requested nonzero spread produced no measurable change to the
     * consideration - see the class javadoc for why this is refused rather than booked.
     */
    public static final class SpreadHadNoEffectException extends MercuryException {

        SpreadHadNoEffectException(FinancialInstrument instrument, ValuationResult priced,
                                   BasisPoints spread) {
            super("A spread of " + spread + " on " + instrument.id() + " had no effect on the "
                    + "consideration: the priced mid (" + priced.value() + " " + priced.currency().code()
                    + ") is too close to zero for a percentage-of-mid spread to move it. This is "
                    + "expected for a net-present-value instrument like a swap or forward - a real "
                    + "spread on one is quoted on the rate, not as a fraction of the NPV, and this "
                    + "venue does not yet support that convention (see docs/KNOWN_GAPS.md). "
                    + "Booking the trade anyway would silently claim a spread that was never applied.");
        }
    }
}
