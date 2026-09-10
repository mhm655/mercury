package com.mercury.app;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.execution.ExecutionRouter;
import com.mercury.execution.OrderBookInstruction;
import com.mercury.execution.OrderBookVenue;
import com.mercury.execution.OtcInstruction;
import com.mercury.execution.OtcNegotiationVenue;
import com.mercury.execution.TradeIdGenerator;
import com.mercury.matching.Fill;
import com.mercury.matching.MatchResult;
import com.mercury.matching.Order;
import com.mercury.matching.OrderBook;
import com.mercury.matching.Side;
import com.mercury.portfolio.CashAccount;
import com.mercury.portfolio.CostBasisMethod;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.portfolio.PortfolioLedger;
import com.mercury.trade.CreditLimit;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeLifecycleEvent;
import com.mercury.trade.TradeStatus;
import java.util.List;

/**
 * A second, milestone-specific runnable - not the project's entrypoint - that proves M8's
 * architecture visibly rather than only through tests.
 *
 * <p>{@code Main} is still the one command the README leads with; this exists because an
 * entire milestone provable only by reading test files works against the project's own
 * "verify in under a minute" evidence-first ethos (see README, "Evidence, not claims").
 * It runs against the same instruments and market {@code DemoScenario} already builds -
 * nothing here is a new scenario, and {@code Main}'s golden-master output is untouched.
 *
 * <pre>
 *   mvn -q -pl mercury-app -am package
 *   java -cp "mercury-app/target/classes;mercury-engine/target/classes" com.mercury.app.TradeLifecycleDemo
 * </pre>
 */
public final class TradeLifecycleDemo {

    private static final CounterpartyId BUYER = CounterpartyId.of("CPTY-BUYSIDE");
    private static final CounterpartyId SELLER = CounterpartyId.of("CPTY-MARKETMAKER");
    private static final CounterpartyId ACME = CounterpartyId.of("CPTY-ACME");
    private static final CounterpartyId MERCURY_BOOK = CounterpartyId.of("CPTY-MERCURY");

    private TradeLifecycleDemo() {
    }

    public static void main(String[] args) {
        InstrumentCatalog catalog = InstrumentCatalog.of(DemoScenario.instruments());
        SimulationClock clock = SimulationClock.fixedAt(DemoScenario.VALUATION_DATE);
        TradeIdGenerator tradeIds = new TradeIdGenerator("TRD-");
        OrderBookVenue orderBookVenue = new OrderBookVenue(tradeIds, catalog);
        OtcNegotiationVenue otcVenue = new OtcNegotiationVenue(
                DemoScenario.pricingService(), DemoScenario.market(), catalog, tradeIds, MERCURY_BOOK);
        ExecutionRouter router = new ExecutionRouter(orderBookVenue, otcVenue);

        System.out.println("=".repeat(78));
        System.out.println("M8 TRADE LIFECYCLE DEMO");
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
        com.mercury.trade.Counterparty acme = new com.mercury.trade.Counterparty(ACME,
                "Acme Capital", new CreditLimit(Money.of("50000000.00", Currency.USD)));
        System.out.println("Counterparty: " + acme);
        List<Trade> otcTrades = router.execute(swap(catalog), new OtcInstruction(
                DemoScenario.SWAP, Side.BUY, Quantity.of(1), acme.id(), BasisPoints.ofPercent(0.10)),
                clock);
        otcTrades.forEach(TradeLifecycleDemo::printTrade);

        System.out.println();
        System.out.println("4. THE FULL STATE MACHINE, AND BOOKING INTO A PORTFOLIO LEDGER");
        System.out.println("-".repeat(78));
        Trade settled = crossed.get(0)
                .transitionTo(TradeStatus.CONFIRMED, "confirmation sent", clock)
                .transitionTo(TradeStatus.SETTLED, "cash and securities exchanged", clock);
        System.out.println("  " + settled.id() + " audit trail:");
        for (TradeLifecycleEvent event : settled.history()) {
            System.out.println("    " + event);
        }
        PortfolioLedger ledger = PortfolioLedger.opening(PortfolioId.of("DEMO-BOOK"), Currency.USD,
                        CostBasisMethod.FIRST_IN_FIRST_OUT, CashAccount.of(Money.of("100000.00", Currency.USD)))
                .book(settled);
        System.out.println("  booked: " + settled.owner() + " now holds "
                + ledger.quantityOf(DemoScenario.AAPL) + " " + DemoScenario.AAPL);
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
