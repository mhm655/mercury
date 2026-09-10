package com.mercury.execution;

import com.mercury.core.id.CounterpartyId;
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
        double spreadFactor = otc.side().isBuy()
                ? 1.0 + otc.spread().asDecimal()
                : 1.0 - otc.spread().asDecimal();
        double unitPrice = priced.value() * spreadFactor;
        double notional = unitPrice * otc.quantity().value().doubleValue();

        boolean buy = otc.side().isBuy();
        Quantity delta = buy ? otc.quantity() : Quantity.of(otc.quantity().value().negate());
        Money consideration = Money.fromModelValue(buy ? notional : -notional, currency);

        Trade trade = Trade.newTrade(tradeIdGenerator.next(), instrument.id(), ownBook, delta,
                consideration, clock.today(), Optional.empty(), Optional.of(otc.counterparty()));
        return List.of(trade.transitionTo(TradeStatus.VALIDATED, "priced on request", clock)
                .transitionTo(TradeStatus.BOOKED, "booked to the ledger", clock)
                .transitionTo(TradeStatus.EXECUTED, "negotiated against " + otc.counterparty(), clock));
    }
}
