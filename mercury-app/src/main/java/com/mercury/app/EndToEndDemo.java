package com.mercury.app;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.execution.CounterpartyDirectory;
import com.mercury.execution.ExecutionRouter;
import com.mercury.execution.NegotiationResult;
import com.mercury.execution.OrderBookInstruction;
import com.mercury.execution.OrderBookVenue;
import com.mercury.execution.OtcInstruction;
import com.mercury.execution.OtcNegotiationVenue;
import com.mercury.execution.TradeIdGenerator;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.marketdata.Scenario;
import com.mercury.matching.Side;
import com.mercury.portfolio.CashAccount;
import com.mercury.portfolio.CostBasisMethod;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.portfolio.PnlStatement;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioLedger;
import com.mercury.portfolio.PortfolioValuation;
import com.mercury.risk.CounterpartyExposureLimit;
import com.mercury.risk.SensitivityCalculator;
import com.mercury.simulation.MonteCarloRiskResult;
import com.mercury.simulation.MonteCarloVaRCalculator;
import com.mercury.trade.CreditLimit;
import com.mercury.trade.Counterparty;
import com.mercury.trade.Trade;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The whole engine as one story: orders become trades, trades become a book, the book is
 * valued, and the value is risked.
 *
 * <p>The other runnables each prove one milestone in isolation - {@code Main}'s report starts
 * from trades declared by hand, and the lifecycle demo stops at booking. Nothing showed the
 * pieces connected, so a reader had to take on trust that the trades a venue produces are the
 * trades a valuation can use. This runs them in sequence over the same instruments and market
 * {@code DemoScenario} builds, starting from cash.
 *
 * <pre>
 *   java -jar mercury-app/target/mercury.jar walkthrough
 * </pre>
 */
public final class EndToEndDemo {

    private static final CounterpartyId MERCURY_BOOK = CounterpartyId.of("CPTY-MERCURY");
    private static final CounterpartyId MARKET_MAKER = CounterpartyId.of("CPTY-MARKETMAKER");
    private static final CounterpartyId ACME = CounterpartyId.of("CPTY-ACME");

    private EndToEndDemo() {
    }

    public static void main(String[] args) {
        InstrumentCatalog catalog = InstrumentCatalog.of(DemoScenario.instruments());
        MarketDataSnapshot market = DemoScenario.market();
        SimulationClock clock = SimulationClock.fixedAt(DemoScenario.VALUATION_DATE);
        TradeIdGenerator tradeIds = new TradeIdGenerator("TRD-");
        OtcNegotiationVenue otcVenue = new OtcNegotiationVenue(DemoScenario.pricingService(), market,
                catalog, tradeIds, MERCURY_BOOK,
                CounterpartyDirectory.of(new Counterparty(ACME, "Acme Capital",
                        new CreditLimit(Money.of("1000000.00", Currency.USD)))),
                new CounterpartyExposureLimit());
        ExecutionRouter router = new ExecutionRouter(new OrderBookVenue(tradeIds, catalog), otcVenue);
        List<Trade> ours = new ArrayList<>();

        heading("1. EXCHANGE-TRADED: THE BOOK LIFTS A MARKET MAKER'S OFFERS");
        router.execute(catalog.require(DemoScenario.AAPL), OrderBookInstruction.limit(
                DemoScenario.AAPL, Side.SELL, Price.of("195.40"), 500, MARKET_MAKER), clock);
        router.execute(catalog.require(DemoScenario.MSFT), OrderBookInstruction.limit(
                DemoScenario.MSFT, Side.SELL, Price.of("412.20"), 100, MARKET_MAKER), clock);
        keepOurs(ours, router.execute(catalog.require(DemoScenario.AAPL), OrderBookInstruction.limit(
                DemoScenario.AAPL, Side.BUY, Price.of("195.50"), 300, MERCURY_BOOK), clock));
        keepOurs(ours, router.execute(catalog.require(DemoScenario.MSFT), OrderBookInstruction.market(
                DemoScenario.MSFT, Side.BUY, 100, MERCURY_BOOK), clock));

        heading("2. OVER THE COUNTER: A BOND FROM ACME, CHECKED AGAINST ITS CREDIT LIMIT");
        NegotiationResult bond = otcVenue.negotiate(new OtcInstruction(
                DemoScenario.CORP_BOND, Side.BUY, Quantity.of(200), ACME, BasisPoints.of(10)), clock);
        keepOurs(ours, bond.trades());
        NegotiationResult tooMuch = otcVenue.negotiate(new OtcInstruction(
                DemoScenario.CORP_BOND, Side.BUY, Quantity.of(1000), ACME, BasisPoints.of(10)), clock);
        System.out.println("  1,000 more units: " + (tooMuch.isRejected() ? "rejected" : "executed"));
        tooMuch.breaches().forEach(breach -> System.out.println("  " + breach));
        System.out.println("  exposure to " + ACME + ": " + otcVenue.exposureTo(ACME));

        heading("3. BOOKED: THOSE TRADES, AND ONLY THOSE, BECOME A LEDGER");
        PortfolioLedger ledger = PortfolioLedger.opening(PortfolioId.of("WALKTHROUGH"), Currency.USD,
                        CostBasisMethod.FIRST_IN_FIRST_OUT,
                        CashAccount.of(Money.of("1000000.00", Currency.USD)))
                .bookAll(ours);
        for (var instrument : ledger.instruments()) {
            System.out.println("  " + pad(instrument.value()) + ledger.quantityOf(instrument)
                    + " at a cost of " + ledger.costBasisOf(instrument));
        }
        System.out.println("  cash                " + ledger.cash().balance(Currency.USD));

        heading("4. VALUED: THE LEDGER PROJECTED TO POSITIONS AND PRICED");
        Portfolio portfolio = ledger.toPortfolio();
        PortfolioValuation valuation = DemoScenario.valuationService()
                .value(portfolio, market, DemoScenario.VALUATION_DATE);
        valuation.lines().forEach(line -> System.out.println("  " + pad(line.instrument().id().value())
                + line.marketValue() + "  (" + line.unitValue().model() + ")"));
        System.out.println("  positions total     " + valuation.totalValue());
        System.out.println("  profit and loss     " + PnlStatement.of(ledger, valuation, market).total());

        heading("5. RISKED: SENSITIVITIES, SCENARIOS AND MONTE CARLO VAR ON THAT SAME BOOK");
        SensitivityCalculator sensitivities = DemoScenario.sensitivityCalculator();
        System.out.println(String.format(Locale.ROOT, "  AAPL delta          %.2f",
                sensitivities.delta(portfolio, DemoScenario.AAPL, market, DemoScenario.VALUATION_DATE)));
        System.out.println(String.format(Locale.ROOT, "  USD DV01            %.2f",
                sensitivities.dv01(portfolio, Currency.USD, market, DemoScenario.VALUATION_DATE)));
        for (Scenario scenario : DemoScenario.scenarios()) {
            System.out.println("  " + pad(scenario.name()) + sensitivities.valueChangeUnder(
                    portfolio, scenario.shock(), market, DemoScenario.VALUATION_DATE));
        }
        MonteCarloRiskResult var = new MonteCarloVaRCalculator(sensitivities, 7).simulate(
                portfolio, DemoScenario.AAPL, 0.0, market.volatility(DemoScenario.AAPL), 1.0 / 365.0,
                20_000, market, DemoScenario.VALUATION_DATE, 0.99);
        System.out.println("  99% 1-day AAPL VaR  " + var.valueAtRisk() + ", 95% interval "
                + var.valueAtRiskConfidenceInterval() + " (20,000 paths, seed " + var.seed() + ")");
    }

    /** Only the book's own side of each execution belongs in its ledger. */
    private static void keepOurs(List<Trade> ours, List<Trade> executed) {
        for (Trade trade : executed) {
            if (trade.owner().equals(MERCURY_BOOK)) {
                ours.add(trade);
                System.out.println("  " + trade.id() + " " + trade.instrumentId() + " " + trade.delta()
                        + " for " + trade.consideration()
                        + trade.counterparty().map(c -> " vs " + c).orElse(" on the order book"));
            }
        }
    }

    private static void heading(String title) {
        System.out.println();
        System.out.println(title);
        System.out.println("-".repeat(78));
    }

    private static String pad(String label) {
        return String.format(Locale.ROOT, "%-20s", label);
    }
}
