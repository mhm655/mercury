package com.mercury.execution;

import com.mercury.core.MercuryException;
import com.mercury.core.id.CounterpartyId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.ValuationResult;
import com.mercury.trade.Trade;
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
 */
public final class OtcNegotiationVenue implements ExecutionVenue {

    private final PricingService pricingService;
    private final MarketDataSnapshot market;
    private final InstrumentCatalog instruments;
    private final TradeIdGenerator tradeIdGenerator;
    private final CounterpartyId ownBook;

    public OtcNegotiationVenue(PricingService pricingService, MarketDataSnapshot market,
                               InstrumentCatalog instruments, TradeIdGenerator tradeIdGenerator,
                               CounterpartyId ownBook) {
        this.pricingService = Objects.requireNonNull(pricingService, "pricingService");
        this.market = Objects.requireNonNull(market, "market");
        this.instruments = Objects.requireNonNull(instruments, "instruments");
        this.tradeIdGenerator = Objects.requireNonNull(tradeIdGenerator, "tradeIdGenerator");
        this.ownBook = Objects.requireNonNull(ownBook, "ownBook");
    }

    @Override
    public List<Trade> execute(ExecutionInstruction instruction, SimulationClock clock) {
        if (!(instruction instanceof OtcInstruction otc)) {
            throw new IllegalArgumentException(
                    "OtcNegotiationVenue only executes OtcInstruction, but received "
                            + instruction.getClass().getSimpleName());
        }
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
        if (!otc.spread().equals(BasisPoints.ZERO)) {
            double unspreadNotional = priced.value() * quantity;
            Money withoutSpread = Money.fromModelValue(buy ? unspreadNotional : -unspreadNotional,
                    currency);
            if (consideration.equals(withoutSpread)) {
                throw new SpreadHadNoEffectException(instrument, priced, otc.spread());
            }
        }

        Quantity delta = buy ? otc.quantity() : Quantity.of(otc.quantity().value().negate());

        Trade trade = Trade.newTrade(tradeIdGenerator.next(), instrument.id(), ownBook, delta,
                consideration, clock.today(), Optional.empty(), Optional.of(otc.counterparty()));
        return List.of(trade.transitionTo(TradeStatus.VALIDATED, "priced on request", clock)
                .transitionTo(TradeStatus.BOOKED, "booked to the ledger", clock)
                .transitionTo(TradeStatus.EXECUTED, "negotiated against " + otc.counterparty(), clock));
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
