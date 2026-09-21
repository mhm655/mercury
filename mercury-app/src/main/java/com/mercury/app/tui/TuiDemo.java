package com.mercury.app.tui;

import com.mercury.app.DemoScenario;
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
import com.mercury.execution.CounterpartyDirectory;
import com.mercury.execution.ExecutionRouter;
import com.mercury.execution.NegotiationResult;
import com.mercury.execution.OrderBookInstruction;
import com.mercury.execution.OtcInstruction;
import com.mercury.execution.OtcNegotiationVenue;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.matching.Side;
import com.mercury.portfolio.CashAccount;
import com.mercury.portfolio.CostBasisMethod;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.portfolio.LedgerKeeper;
import com.mercury.portfolio.PortfolioLedger;
import com.mercury.risk.SensitivityCalculator;
import com.mercury.simulation.MonteCarloVaRCalculator;
import com.mercury.trade.Counterparty;
import com.mercury.trade.CreditLimit;
import com.mercury.trade.TradeExecuted;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;

/**
 * M16c: a P&amp;L and risk panel, added to M16a's stepping model and M16b's book and blotter
 * panels - scoped by ADR 0007 and the M16 breakdown in {@code docs/DESIGN_PROPOSAL.md} §10.4.
 * Replays the same six order/negotiation instructions {@link com.mercury.app.EndToEndDemo}
 * runs in one pass, but one at a time, redrawing the whole screen after each step.
 *
 * <p>Depth for AAPL and MSFT, the only two instruments that trade on the order book in this
 * scenario, comes from a {@link ShadowBook} each rather than from the real venue: see that
 * class's javadoc for why. The blotter is a {@link Blotter} subscribed to the same
 * {@link TradeExecuted} events {@link com.mercury.portfolio.LedgerKeeper} already is. P&amp;L
 * and risk are a {@link PnlRiskPanel}: valuation, P&amp;L, delta and DV01 recomputed every
 * frame, Monte Carlo VaR only every few frames - see that class's javadoc for the cadence and
 * why.
 *
 * <p>Kept as its own copy of the six instructions rather than sharing
 * {@code EndToEndDemo}'s, the same way {@code DemoScenario.venues()} started as three
 * separate copies before being extracted: two consumers of the same short, fixed sequence
 * is not yet the pressure that justifies pulling it out from under a demo whose own
 * narration interleaves prints between calls. Extract if a third consumer needs it.
 *
 * <pre>
 *   java -jar mercury-app/target/mercury.jar tui
 * </pre>
 */
public final class TuiDemo {

    private static final CounterpartyId MERCURY_BOOK = CounterpartyId.of("CPTY-MERCURY");
    private static final CounterpartyId MARKET_MAKER = CounterpartyId.of("CPTY-MARKETMAKER");
    private static final CounterpartyId ACME = CounterpartyId.of("CPTY-ACME");
    private static final int DEPTH_LEVELS = 3;

    /** VaR recomputes on step 0 and every {@code N}th step after; see {@link PnlRiskPanel}. */
    private static final int VAR_CADENCE_STEPS = 2;
    private static final int VAR_PATH_COUNT = 20_000;
    private static final long VAR_SEED = 7;

    private TuiDemo() {
    }

    public static void main(String[] args) {
        InstrumentCatalog catalog = InstrumentCatalog.of(DemoScenario.instruments());
        SimulationClock clock = SimulationClock.fixedAt(DemoScenario.VALUATION_DATE);

        // Neither this nor EndToEndDemo books the market maker's own fills - LedgerKeeper only
        // books trades owned by MERCURY_BOOK, so the resting side of every cross is announced
        // on the bus and ignored, exactly as EndToEndDemo already relies on.
        EventBus events = new SynchronousEventBus();
        LedgerKeeper ourBook = new LedgerKeeper(MERCURY_BOOK, PortfolioLedger.opening(
                PortfolioId.of("WALKTHROUGH"), Currency.USD, CostBasisMethod.FIRST_IN_FIRST_OUT,
                CashAccount.of(Money.of("1000000.00", Currency.USD))));
        Blotter blotter = new Blotter(MERCURY_BOOK);
        events.subscribe(TradeExecuted.class, ourBook);
        events.subscribe(TradeExecuted.class, blotter);

        DemoScenario.Venues venues = DemoScenario.venues(catalog, MERCURY_BOOK,
                CounterpartyDirectory.of(new Counterparty(ACME, "Acme Capital",
                        new CreditLimit(Money.of("1000000.00", Currency.USD)))), events);
        ExecutionRouter router = venues.router();
        OtcNegotiationVenue otcVenue = venues.otc();

        ShadowBook aaplBook = new ShadowBook(DemoScenario.AAPL);
        ShadowBook msftBook = new ShadowBook(DemoScenario.MSFT);

        MarketDataSnapshot market = DemoScenario.market();
        SensitivityCalculator sensitivities = DemoScenario.sensitivityCalculator();
        PnlRiskPanel riskPanel = new PnlRiskPanel(DemoScenario.valuationService(), sensitivities,
                new MonteCarloVaRCalculator(sensitivities, VAR_SEED), DemoScenario.AAPL,
                market.volatility(DemoScenario.AAPL), VAR_PATH_COUNT, VAR_CADENCE_STEPS);

        ScenarioStepper stepper = new ScenarioStepper(
                steps(catalog, router, otcVenue, clock, aaplBook, msftBook, blotter));

        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            printFrame(stepper, aaplBook, msftBook, blotter, riskPanel, ourBook, market);
            while (stepper.hasNext()) {
                in.readLine();
                stepper.advance();
                printFrame(stepper, aaplBook, msftBook, blotter, riskPanel, ourBook, market);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The whole screen, redrawn from scratch every call. Deliberately thin: everything it
     * prints is already assembled by {@link BookDepthView}, {@link PnlRiskPanel}, or held by
     * {@link Blotter}, so the only thing this method itself decides is layout and colour.
     */
    private static void printFrame(ScenarioStepper stepper, ShadowBook aaplBook,
                                   ShadowBook msftBook, Blotter blotter, PnlRiskPanel riskPanel,
                                   LedgerKeeper ourBook, MarketDataSnapshot market) {
        StringBuilder frame = new StringBuilder(AnsiScreen.CLEAR_AND_HOME);
        frame.append(AnsiScreen.BOLD).append("MERCURY - TERMINAL WALKTHROUGH").append(AnsiScreen.RESET)
                .append('\n');
        frame.append("=".repeat(78)).append('\n').append('\n');

        frame.append(AnsiScreen.CYAN).append("ORDER BOOK").append(AnsiScreen.RESET).append('\n');
        BookDepthView.render("AAPL", aaplBook.book(), DEPTH_LEVELS)
                .forEach(line -> frame.append(line).append('\n'));
        frame.append('\n');
        BookDepthView.render("MSFT", msftBook.book(), DEPTH_LEVELS)
                .forEach(line -> frame.append(line).append('\n'));
        frame.append('\n');

        frame.append(AnsiScreen.CYAN).append("BLOTTER (Mercury's own trades)")
                .append(AnsiScreen.RESET).append('\n');
        List<String> rows = blotter.rows();
        if (rows.isEmpty()) {
            frame.append("  (no trades yet)\n");
        } else {
            rows.forEach(row -> frame.append("  ").append(row).append('\n'));
        }
        frame.append('\n');

        frame.append(AnsiScreen.CYAN).append("P&L AND RISK").append(AnsiScreen.RESET).append('\n');
        riskPanel.render(ourBook.ledger(), market, DemoScenario.VALUATION_DATE, stepper.stepsRun())
                .forEach(line -> frame.append(line).append('\n'));
        frame.append('\n').append("-".repeat(78)).append('\n');

        if (stepper.hasNext()) {
            frame.append(String.format(Locale.ROOT, "[%d/%d] %s -- press Enter to run this step",
                    stepper.stepsRun() + 1, stepper.stepCount(), stepper.peek().label()))
                    .append('\n');
        } else {
            frame.append("All ").append(stepper.stepCount()).append(" steps replayed.\n");
        }

        System.out.print(frame);
    }

    /**
     * The same six instructions {@link com.mercury.app.EndToEndDemo} runs in one pass: four
     * exchange-traded fills, then two OTC negotiations against Acme, the second of which its
     * credit limit rejects. Every exchange-traded instruction is mirrored into its instrument's
     * {@link ShadowBook} right after the real venue call, so the two never drift apart.
     */
    private static List<ScenarioStep> steps(InstrumentCatalog catalog, ExecutionRouter router,
                                            OtcNegotiationVenue otcVenue, SimulationClock clock,
                                            ShadowBook aaplBook, ShadowBook msftBook,
                                            Blotter blotter) {
        return List.of(
                new ScenarioStep("Market maker offers 500 AAPL @ 195.40", () -> {
                    OrderBookInstruction instruction = OrderBookInstruction.limit(
                            DemoScenario.AAPL, Side.SELL, Price.of("195.40"), 500, MARKET_MAKER);
                    router.execute(catalog.require(DemoScenario.AAPL), instruction, clock);
                    aaplBook.mirror(instruction);
                }),
                new ScenarioStep("Market maker offers 100 MSFT @ 412.20", () -> {
                    OrderBookInstruction instruction = OrderBookInstruction.limit(
                            DemoScenario.MSFT, Side.SELL, Price.of("412.20"), 100, MARKET_MAKER);
                    router.execute(catalog.require(DemoScenario.MSFT), instruction, clock);
                    msftBook.mirror(instruction);
                }),
                new ScenarioStep("Mercury buys 300 AAPL @ 195.50 (limit)", () -> {
                    OrderBookInstruction instruction = OrderBookInstruction.limit(
                            DemoScenario.AAPL, Side.BUY, Price.of("195.50"), 300, MERCURY_BOOK);
                    router.execute(catalog.require(DemoScenario.AAPL), instruction, clock);
                    aaplBook.mirror(instruction);
                }),
                new ScenarioStep("Mercury buys 100 MSFT (market)", () -> {
                    OrderBookInstruction instruction = OrderBookInstruction.market(
                            DemoScenario.MSFT, Side.BUY, 100, MERCURY_BOOK);
                    router.execute(catalog.require(DemoScenario.MSFT), instruction, clock);
                    msftBook.mirror(instruction);
                }),
                new ScenarioStep("Negotiate 200 CORP-5Y with Acme", () ->
                        otcVenue.negotiate(new OtcInstruction(DemoScenario.CORP_BOND, Side.BUY,
                                Quantity.of(200), ACME, BasisPoints.of(10)), clock)),
                new ScenarioStep("Negotiate 1,000 more CORP-5Y with Acme (expect: credit breach)",
                        () -> {
                            NegotiationResult result = otcVenue.negotiate(new OtcInstruction(
                                    DemoScenario.CORP_BOND, Side.BUY, Quantity.of(1000), ACME,
                                    BasisPoints.of(10)), clock);
                            if (result.isRejected()) {
                                result.breaches().forEach(
                                        breach -> blotter.recordRejection(breach.toString()));
                            }
                        }));
    }
}
