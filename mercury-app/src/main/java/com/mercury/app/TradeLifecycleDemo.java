package com.mercury.app;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.event.EventBus;
import com.mercury.event.SynchronousEventBus;
import com.mercury.execution.ExecutionRouter;
import com.mercury.execution.OrderBookInstruction;
import com.mercury.execution.OtcInstruction;
import com.mercury.execution.OtcNegotiationVenue;
import com.mercury.matching.Fill;
import com.mercury.matching.MatchResult;
import com.mercury.matching.Order;
import com.mercury.matching.OrderBook;
import com.mercury.matching.Side;
import com.mercury.execution.CounterpartyDirectory;
import com.mercury.execution.NegotiationResult;
import com.mercury.portfolio.CashAccount;
import com.mercury.portfolio.CostBasisMethod;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.portfolio.PortfolioLedger;
import com.mercury.trade.CreditLimit;
import com.mercury.trade.Counterparty;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeExecuted;
import com.mercury.trade.TradeLifecycleEvent;
import com.mercury.trade.TradeSettlementBook;
import java.time.LocalDate;
import java.util.List;

/**
 * A second, milestone-specific runnable - not the project's entrypoint - that proves M8's and
 * M9's architecture visibly rather than only through tests.
 *
 * <p>{@code Main} is still the one command the README leads with; this exists because an
 * entire milestone provable only by reading test files works against the project's own
 * "verify in under a minute" evidence-first ethos (see README, "Evidence, not claims").
 * It runs against the same instruments and market {@code DemoScenario} already builds -
 * nothing here is a new scenario, and {@code Main}'s golden-master output is untouched.
 *
 * <p>Section 5 is M9's: a counterparty credit limit rejecting a trade that would breach it,
 * with the breach printed rather than inferred - see {@code OtcNegotiationVenue}. Sections 4
 * and 5 both settle through a {@link TradeSettlementBook} (M19) rather than the two hand-written
 * {@code transitionTo(CONFIRMED).transitionTo(SETTLED)} calls this file used to make - the real
 * consumer {@code docs/KNOWN_GAPS.md}'s "Settlement scheduling" entry was waiting for.
 *
 * <pre>
 *   mvn -q -DskipTests package
 *   java -jar mercury-app/target/mercury.jar lifecycle
 * </pre>
 */
public final class TradeLifecycleDemo {

    private static final CounterpartyId BUYER = CounterpartyId.of("CPTY-BUYSIDE");
    private static final CounterpartyId SELLER = CounterpartyId.of("CPTY-MARKETMAKER");
    private static final CounterpartyId ACME = CounterpartyId.of("CPTY-ACME");
    private static final CounterpartyId TINY_LIMIT = CounterpartyId.of("CPTY-TINYLIMIT");
    private static final CounterpartyId MERCURY_BOOK = CounterpartyId.of("CPTY-MERCURY");

    private TradeLifecycleDemo() {
    }

    public static void main(String[] args) {
        InstrumentCatalog catalog = InstrumentCatalog.of(DemoScenario.instruments());
        SimulationClock clock = SimulationClock.fixedAt(DemoScenario.VALUATION_DATE);
        Counterparty acme = new Counterparty(ACME, "Acme Capital",
                new CreditLimit(Money.of("50000000.00", Currency.USD)));
        Counterparty tinyLimit = new Counterparty(TINY_LIMIT, "Tiny Capital",
                new CreditLimit(Money.of("5000.00", Currency.USD)));
        CounterpartyDirectory counterparties = CounterpartyDirectory.of(acme, tinyLimit);
        // This demo still books each trade it prints from the list router.execute() returns,
        // the way it always has - but a real bus is wired now (M19) so a TradeSettlementBook
        // can subscribe and settle trades automatically, rather than EventBus.ignoring() with
        // nothing to subscribe.
        EventBus events = new SynchronousEventBus();
        TradeSettlementBook settlementBook = new TradeSettlementBook();
        events.subscribe(TradeExecuted.class, settlementBook);
        DemoScenario.Venues venues = DemoScenario.venues(catalog, MERCURY_BOOK, counterparties, events);
        ExecutionRouter router = venues.router();
        OtcNegotiationVenue otcVenue = venues.otc();

        System.out.println("=".repeat(78));
        System.out.println("M8/M9 TRADE LIFECYCLE DEMO");
        System.out.println("=".repeat(78));

        System.out.println();
        System.out.println("1. TWO PARTICIPANTS CROSS ON THE ORDER BOOK");
        System.out.println("-".repeat(78));
        router.execute(instrument(catalog), OrderBookInstruction.limit(
                DemoScenario.AAPL, Side.SELL, Price.of("195.00"), 100, SELLER), clock);
        List<Trade> crossed = router.execute(instrument(catalog), OrderBookInstruction.limit(
                DemoScenario.AAPL, Side.BUY, Price.of("195.00"), 100, BUYER), clock);
        crossed.forEach(TradeLifecycleDemo::printTrade);

        System.out.println();
        System.out.println("2. SELF-TRADE PREVENTION (G-2), AT THE MATCHING LAYER");
        System.out.println("-".repeat(78));
        System.out.println("The same participant resting on both sides of a crossing price:");
        OrderBook book = new OrderBook(DemoScenario.AAPL);
        Order restingSell = Order.limit(com.mercury.core.id.OrderId.of("DEMO-1"), DemoScenario.AAPL,
                Side.SELL, Price.of("195.00"), 100, BUYER);
        book.submit(restingSell);
        MatchResult stpResult = book.submit(Order.limit(com.mercury.core.id.OrderId.of("DEMO-2"),
                DemoScenario.AAPL, Side.BUY, Price.of("195.00"), 100, BUYER));
        System.out.println("  fills: " + stpResult.fills().size()
                + " (none - the pairing was blocked, not silently skipped)");
        stpResult.selfTradePrevented().forEach(
                stp -> System.out.println("  " + stp));

        System.out.println();
        System.out.println("3. AN OTC NEGOTIATION AGAINST A NAMED COUNTERPARTY");
        System.out.println("-".repeat(78));
        System.out.println("Counterparty: " + acme);
        List<Trade> otcTrades = router.execute(swap(catalog), new OtcInstruction(
                DemoScenario.SWAP, Side.BUY, Quantity.of(1), acme.id(), BasisPoints.ofPercent(0.10)),
                clock);
        otcTrades.forEach(TradeLifecycleDemo::printTrade);

        System.out.println();
        System.out.println("4. THE FULL STATE MACHINE, SETTLED AUTOMATICALLY (M19), THEN BOOKED");
        System.out.println("-".repeat(78));
        // Every trade above executed on the same fixed clock, so they share one settlement
        // date - settleDueBy sweeps all of them, not just the one this section showcases. That
        // is TradeSettlementBook actually working, not a narrower demo than the hand-written
        // version it replaces: through M18 only crossed.get(0) was ever walked to SETTLED here,
        // by hand, and every other trade in this demo stayed EXECUTED forever.
        LocalDate settlementDay = crossed.get(0).settlementDate().orElseThrow();
        List<Trade> settledToday = settlementBook.settleDueBy(settlementDay, clock);
        Trade settled = settledToday.stream()
                .filter(trade -> trade.id().equals(crossed.get(0).id()))
                .findFirst().orElseThrow();
        System.out.println("  advanced to " + settlementDay + ": " + settledToday.size()
                + " trade(s) due settled automatically, not walked to SETTLED by hand");
        System.out.println("  " + settled.id() + " audit trail:");
        for (TradeLifecycleEvent event : settled.history()) {
            System.out.println("    " + event);
        }
        PortfolioLedger ledger = PortfolioLedger.opening(PortfolioId.of("DEMO-BOOK"), Currency.USD,
                        CostBasisMethod.FIRST_IN_FIRST_OUT, CashAccount.of(Money.of("100000.00", Currency.USD)))
                .book(settled);
        System.out.println("  booked: " + settled.owner() + " now holds "
                + ledger.quantityOf(DemoScenario.AAPL) + " " + DemoScenario.AAPL);

        System.out.println();
        System.out.println("5. A RISK LIMIT BREACH (M9), AT THE OTC NEGOTIATION VENUE");
        System.out.println("-".repeat(78));
        System.out.println("Counterparty: " + tinyLimit);
        OtcInstruction fitsUnderTheLimit = new OtcInstruction(
                DemoScenario.CORP_BOND, Side.BUY, Quantity.of(4), TINY_LIMIT, BasisPoints.ZERO);
        NegotiationResult approved = otcVenue.negotiate(fitsUnderTheLimit, clock);
        System.out.println("  4 units: " + (approved.isRejected() ? "rejected" : "executed")
                + " - " + approved.trades().get(0).consideration());

        OtcInstruction breachesTheLimit = new OtcInstruction(
                DemoScenario.CORP_BOND, Side.BUY, Quantity.of(4), TINY_LIMIT, BasisPoints.ZERO);
        NegotiationResult rejected = otcVenue.negotiate(breachesTheLimit, clock);
        System.out.println("  4 more units: " + (rejected.isRejected() ? "rejected" : "executed")
                + " (no trade produced, not a silent no-op)");
        rejected.breaches().forEach(breach -> System.out.println("  " + breach));

        System.out.println("  exposure to " + TINY_LIMIT + ": " + otcVenue.exposureTo(TINY_LIMIT)
                + " (queryable without attempting a trade)");
        LocalDate bondSettlementDay = approved.trades().get(0).settlementDate().orElseThrow();
        Trade firstBondTradeSettled = settlementBook.settleDueBy(bondSettlementDay, clock).stream()
                .filter(trade -> trade.id().equals(approved.trades().get(0).id()))
                .findFirst().orElseThrow();
        otcVenue.release(firstBondTradeSettled);
        System.out.println("  " + firstBondTradeSettled.id() + " settled automatically and released: "
                + "exposure to " + TINY_LIMIT + " now " + otcVenue.exposureTo(TINY_LIMIT));
        NegotiationResult retried = otcVenue.negotiate(breachesTheLimit, clock);
        System.out.println("  same 4 units, retried: " + (retried.isRejected() ? "rejected" : "executed")
                + " - exposure is released on settlement, not held forever");
    }

    private static com.mercury.instrument.FinancialInstrument instrument(InstrumentCatalog catalog) {
        return catalog.require(DemoScenario.AAPL);
    }

    private static com.mercury.instrument.FinancialInstrument swap(InstrumentCatalog catalog) {
        return catalog.require(DemoScenario.SWAP);
    }

    private static void printTrade(Trade trade) {
        System.out.println("  " + trade.id() + " " + trade.owner() + " " + trade.status()
                + " delta=" + trade.delta() + " consideration=" + trade.consideration()
                + trade.counterparty().map(c -> " vs " + c).orElse(""));
    }
}
