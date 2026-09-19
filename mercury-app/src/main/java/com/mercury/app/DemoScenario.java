package com.mercury.app;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.Frequency;
import com.mercury.core.time.HolidayCalendar;
import com.mercury.core.time.SimulationClock;
import com.mercury.core.time.Tenor;
import com.mercury.curve.CurveBootstrapper;
import com.mercury.curve.CurveInstrument;
import com.mercury.curve.DepositQuote;
import com.mercury.curve.ParSwapQuote;
import com.mercury.curve.YieldCurve;
import com.mercury.event.EventBus;
import com.mercury.event.SynchronousEventBus;
import com.mercury.execution.CounterpartyDirectory;
import com.mercury.execution.ExecutionRouter;
import com.mercury.execution.OrderBookInstruction;
import com.mercury.execution.OrderBookVenue;
import com.mercury.execution.OtcInstruction;
import com.mercury.execution.OtcNegotiationVenue;
import com.mercury.execution.TradeIdGenerator;
import com.mercury.instrument.Bond;
import com.mercury.instrument.EuropeanOption;
import com.mercury.instrument.FloatingRateIndex;
import com.mercury.instrument.FxForward;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.instrument.InterestRateSwap;
import com.mercury.instrument.Stock;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.marketdata.MarketShock;
import com.mercury.marketdata.Scenario;
import com.mercury.matching.Side;
import com.mercury.portfolio.CashAccount;
import com.mercury.portfolio.CostBasisMethod;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.portfolio.LedgerKeeper;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioLedger;
import com.mercury.portfolio.PortfolioValuationService;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.model.BlackScholesModel;
import com.mercury.pricing.model.DiscountedCashflowModel;
import com.mercury.pricing.model.SpotPriceModel;
import com.mercury.pricing.model.SwapModel;
import com.mercury.risk.CounterpartyExposureLimit;
import com.mercury.risk.SensitivityCalculator;
import com.mercury.trade.CreditLimit;
import com.mercury.trade.Counterparty;
import com.mercury.trade.TradeExecuted;
import java.time.LocalDate;
import java.util.List;

/**
 * A fixed scenario: instruments, a market, a portfolio, and the services to value it.
 *
 * <h2>This is the composition root</h2>
 * Everything is wired by hand. There is no framework, no annotation, no classpath scanning -
 * the engine is assembled by calling constructors, which is what proves it needs none of
 * those things. When the Spring module arrives it will build the same objects from
 * configuration; the engine will not know or care.
 *
 * <h2>Fixed on purpose</h2>
 * Every input here is a constant, including the valuation date. Nothing reads a clock or a
 * random seed, so the scenario produces byte-identical output on every run and on every
 * machine. That is what lets {@code GoldenMasterTest} assert against a committed expected
 * report and catch a regression anywhere in the stack - market data, pricing, valuation or
 * risk - with a single comparison.
 */
public final class DemoScenario {

    public static final InstrumentId AAPL = InstrumentId.of("AAPL");
    public static final InstrumentId MSFT = InstrumentId.of("MSFT");
    public static final InstrumentId AAPL_CALL = InstrumentId.of("AAPL-C-200");
    public static final InstrumentId AAPL_PUT = InstrumentId.of("AAPL-P-180");
    public static final InstrumentId CORP_BOND = InstrumentId.of("CORP-5Y");
    public static final InstrumentId EUR_FORWARD = InstrumentId.of("FWD-EURUSD");
    public static final InstrumentId SWAP = InstrumentId.of("IRS-5Y");
    public static final InstrumentId EUR_BOND = InstrumentId.of("BUND-3Y");

    /** Fixed, so the scenario never depends on when it is run. */
    public static final LocalDate VALUATION_DATE = LocalDate.of(2024, 6, 28);

    /**
     * What the book started with, before it held anything.
     *
     * <p>Named rather than buried in {@link #ledger()} because it is what makes the whole
     * report checkable: a book that began as cash and nothing else must have made exactly
     * {@code net asset value - opening cash}, so total profit and loss has an answer that owes
     * nothing to the P&amp;L code.
     */
    public static final Money OPENING_CASH = Money.of("1000000", Currency.USD);

    private static final LocalDate EXPIRY = LocalDate.of(2025, 6, 20);
    private static final LocalDate BOND_MATURITY = LocalDate.of(2029, 6, 15);
    private static final LocalDate FORWARD_SETTLEMENT = LocalDate.of(2025, 6, 27);
    private static final LocalDate SWAP_MATURITY = LocalDate.of(2029, 6, 28);
    private static final LocalDate EUR_BOND_MATURITY = LocalDate.of(2027, 6, 28);
    private static final CurrencyPair EURUSD = CurrencyPair.parse("EUR/USD");

    /** Trade dates, in the order the book's history actually happened. */
    private static final LocalDate AAPL_BUY_DATE = LocalDate.of(2024, 2, 15);
    private static final LocalDate MSFT_BUY_DATE = LocalDate.of(2024, 3, 1);
    private static final LocalDate OPTIONS_DATE = LocalDate.of(2024, 5, 2);
    private static final LocalDate AAPL_SELL_DATE = LocalDate.of(2024, 5, 10);
    private static final LocalDate BOND_TRADE_DATE = LocalDate.of(2024, 6, 20);

    /** The book itself - the only participant {@link LedgerKeeper} books trades for. */
    private static final CounterpartyId MERCURY_BOOK = CounterpartyId.of("CPTY-MERCURY");

    /** The other side of every exchange-traded fill below; never itself booked. */
    private static final CounterpartyId MARKET_MAKER = CounterpartyId.of("CPTY-MARKETMAKER");

    /** The other side of every OTC negotiation below - one dealer, one generous credit line. */
    private static final CounterpartyId DEALER = CounterpartyId.of("CPTY-DEALER");

    private DemoScenario() {
    }

    public static List<FinancialInstrument> instruments() {
        return List.of(
                Stock.of("AAPL", Currency.USD),
                Stock.of("MSFT", Currency.USD),
                EuropeanOption.call("AAPL-C-200", AAPL, Price.of("200"), EXPIRY, Currency.USD),
                EuropeanOption.put("AAPL-P-180", AAPL, Price.of("180"), EXPIRY, Currency.USD),
                corporateBond(),
                // Quoted EUR/USD, so it values in USD and belongs in a USD book.
                FxForward.buy("FWD-EURUSD", EURUSD, "500000", "1.09", FORWARD_SETTLEMENT),
                payerSwap(),
                europeanBond());
    }

    /**
     * A euro-denominated government bond - the position that makes this a multi-currency book.
     *
     * <p>It is priced entirely in euros, on the euro curve, and only the finished figure is
     * converted at spot. That ordering is the point: discounting a euro payment at a dollar
     * rate would be wrong by the whole rate differential, and no amount of converting
     * afterwards would fix it.
     *
     * <p>A 2.5% coupon against a euro curve near 3% puts it below par, which is the opposite
     * way round from the dollar bond above it - so the report shows both cases at once.
     */
    private static Bond europeanBond() {
        return Bond.builder()
                .id("BUND-3Y")
                .name("Bund 2.5%")
                .faceValue(Money.of("1000", Currency.EUR))
                .couponRate("0.025")
                .couponFrequency(Frequency.ANNUAL)
                .calendar(HolidayCalendar.weekendsOnly())
                .issueDate(VALUATION_DATE)
                .maturityDate(EUR_BOND_MATURITY)
                .build();
    }

    /**
     * A five-year payer swap struck below the market.
     *
     * <p>Paying 4.00% fixed when the five-year par rate is nearer 4.25% is a good trade, so
     * the position is worth something rather than nothing - which is the point of striking it
     * off market. A swap at par would price to zero and demonstrate only that the arithmetic
     * cancels.
     *
     * <p>It also turns the book around on rates. Everything else here is long fixed income:
     * the bond and the two option legs all lose value when rates rise. A payer swap gains, and
     * on a million of notional it gains more than the rest of the book loses - so the reported
     * DV01 changes sign, which no single position in the portfolio would show on its own.
     */
    private static InterestRateSwap payerSwap() {
        return InterestRateSwap.builder()
                .id("IRS-5Y")
                .notional(Money.of("1000000", Currency.USD))
                .fixedRate("0.04")
                .payingFixed()
                .fixedFrequency(Frequency.SEMI_ANNUAL)
                .index(FloatingRateIndex.usdSofr3M())
                .effectiveDate(VALUATION_DATE)
                .maturityDate(SWAP_MATURITY)
                .build();
    }

    /** A five-year 4.5% semi-annual bond, quoted per 1,000 of face. */
    private static Bond corporateBond() {
        return Bond.builder()
                .id("CORP-5Y")
                .name("Acme 4.5%")
                .faceValue(Money.of("1000", Currency.USD))
                .couponRate("0.045")
                .couponFrequency(Frequency.SEMI_ANNUAL)
                .calendar(HolidayCalendar.weekendsOnly())
                .issueDate(LocalDate.of(2024, 6, 15))
                .maturityDate(BOND_MATURITY)
                .build();
    }

    /**
     * A market with everything the pricers need, and nothing they do not.
     *
     * <p>Both discount curves are <b>bootstrapped from quotes</b> rather than typed in. That
     * is the M5b capability made visible: nobody in this file states a five-year zero rate,
     * because nobody in a market ever does. What is quoted is deposits and swap rates, and the
     * curve is whatever term structure reprices all of them at once.
     */
    public static MarketDataSnapshot market() {
        return MarketDataSnapshot.builder(VALUATION_DATE)
                .spot(AAPL, 195.50)
                .spot(MSFT, 412.25)
                .volatility(AAPL, 0.28)
                .curve(Currency.USD, usdCurve())
                .curve(Currency.EUR, eurCurve())
                .fxRate(EURUSD, 1.0725)
                .build();
    }

    /**
     * The USD market in mid-2024: inverted at the front, recovering further out.
     *
     * <p>Deposits out to a year, par swaps beyond it, which is how the market actually quotes
     * the two ends of a curve. The shape matters for the demo - short rates above long ones
     * means the option, which expires in a year, discounts at nearly 5% while the five-year
     * bond discounts nearer 4.2%. Under the flat 4.5% this scenario used before M5b, both were
     * wrong in opposite directions.
     */
    private static YieldCurve usdCurve() {
        return CurveBootstrapper.bootstrap(VALUATION_DATE, List.<CurveInstrument>of(
                DepositQuote.of(Tenor.months(3), 0.0533),
                DepositQuote.of(Tenor.months(6), 0.0524),
                DepositQuote.of(Tenor.years(1), 0.0500),
                ParSwapQuote.of(Tenor.years(2), 0.0460),
                ParSwapQuote.of(Tenor.years(5), 0.0425),
                ParSwapQuote.of(Tenor.years(10), 0.0430),
                ParSwapQuote.of(Tenor.years(30), 0.0420)));
    }

    /** The euro curve, lower and flatter, which is what makes the forward points positive. */
    private static YieldCurve eurCurve() {
        return CurveBootstrapper.bootstrap(VALUATION_DATE, List.<CurveInstrument>of(
                DepositQuote.of(Tenor.months(6), 0.0365),
                ParSwapQuote.of(Tenor.years(2), 0.0305),
                ParSwapQuote.of(Tenor.years(10), 0.0300)));
    }

    /**
     * A long equity book with an options overlay, two bonds in different currencies, an FX
     * forward and a payer swap - all five instrument types the engine models, in one
     * portfolio, settling in two currencies.
     */
    public static Portfolio portfolio() {
        return ledger().toPortfolio();
    }

    /**
     * How the book got to where it is: eight trades, one of them closed at a profit - traded
     * through the same venues {@link com.mercury.execution.ExecutionRouter} routes every other
     * order in this codebase to, not declared as ledger facts by hand.
     *
     * <h2>M14: the golden-master book trades itself</h2>
     * Through M13 this method called {@code PortfolioLedger.buy}/{@code sell}/{@code trade}
     * directly - a parallel set of positions that happened to match what a venue would have
     * produced, rather than the output of one. That made the golden master a report about a
     * book nobody actually traded. It now runs the same eight economic events as real
     * instructions - limit orders crossing on {@link OrderBookVenue} for everything exchange
     * traded, negotiations against a named dealer on {@link OtcNegotiationVenue} for everything
     * over the counter - and lets a {@link LedgerKeeper} subscribed to the trade bus assemble
     * the ledger from what actually executed. The report downstream of this is now a
     * reproducible record of a simulation, not a fixture.
     *
     * <p>The exchange-traded five (both AAPL clips, MSFT, the corporate bond, the euro bond)
     * still trade at exactly the hand-picked prices this scenario always used: a fill executes
     * at the *resting* order's price, so a market maker resting at that exact price and Mercury
     * crossing it produces that exact trade, deterministically, every run.
     *
     * <p>The AAPL line is deliberately not a single purchase. Twelve hundred were bought in
     * February and two hundred sold in May, which realises 2,400 of profit and leaves the
     * thousand still held - so the report has something to show in both P&amp;L columns rather
     * than a realised figure that is always zero.
     *
     * <h2>What changed: the four OTC trades are no longer free</h2>
     * The two options and the swap and forward used to be booked at a hand-picked premium (the
     * options) or at exactly zero (the swap and forward, "because that is what they cost" at
     * inception). {@link OtcNegotiationVenue} does not accept a caller-supplied price - it
     * prices the instrument itself and applies a spread - so these four now cost whatever
     * {@link #pricingService()} actually says they are worth against {@link #market()}, at zero
     * spread. For the swap and forward that is no longer zero: both are deliberately struck off
     * market (see {@link #payerSwap()}), and a real desk does not hand over an off-market
     * instrument for nothing - it charges the value it is walking in with. That value now shows
     * up as cash paid rather than as profit that appeared for free, which is the more honest of
     * the two stories and the reason the P&amp;L this book reports moved when this method
     * stopped hand-waving it.
     *
     * <h2>One stated simplification</h2>
     * The two options are negotiated on {@link #OPTIONS_DATE}, seven weeks before
     * {@link #VALUATION_DATE}, but {@link #market()} is a single snapshot with no history - it
     * has one spot, one volatility, one pair of curves, dated for the valuation date. Pricing an
     * option "as of" an earlier trade date therefore reuses the valuation date's market data
     * against an earlier day count, exactly as pricing a bond bought mid-scenario already does.
     * A market that remembers every date it was ever asked about is a historical data loader
     * this engine deliberately does not have (see {@code docs/KNOWN_GAPS.md}); a single
     * snapshot standing in for "the market" throughout one demo's history is the same
     * simplification the fixed valuation date already makes, applied consistently rather than
     * only where it was convenient.
     */
    public static PortfolioLedger ledger() {
        InstrumentCatalog catalog = InstrumentCatalog.of(instruments());
        EventBus events = new SynchronousEventBus();
        LedgerKeeper keeper = new LedgerKeeper(MERCURY_BOOK, PortfolioLedger.opening(
                PortfolioId.of("US-EQUITY-BOOK"), Currency.USD, CostBasisMethod.FIRST_IN_FIRST_OUT,
                CashAccount.of(OPENING_CASH)));
        events.subscribe(TradeExecuted.class, keeper);

        TradeIdGenerator tradeIds = new TradeIdGenerator("TRD-");
        OrderBookVenue orderBook = new OrderBookVenue(tradeIds, catalog, events);
        OtcNegotiationVenue otc = new OtcNegotiationVenue(pricingService(), market(), catalog,
                tradeIds, MERCURY_BOOK, CounterpartyDirectory.of(new Counterparty(DEALER,
                        "Demo Dealer", new CreditLimit(Money.of("10000000.00", Currency.USD)))),
                new CounterpartyExposureLimit(), events);
        ExecutionRouter router = new ExecutionRouter(orderBook, otc);
        SimulationClock.Advancing clock = SimulationClock.advancing(AAPL_BUY_DATE);

        cross(router, catalog, AAPL, Side.SELL, Price.of("180.00"), 1_200, clock);

        clock.advanceTo(MSFT_BUY_DATE);
        cross(router, catalog, MSFT, Side.SELL, Price.of("430.00"), 250, clock);

        clock.advanceTo(OPTIONS_DATE);
        otc.negotiate(new OtcInstruction(AAPL_CALL, Side.SELL, Quantity.of(5), DEALER,
                BasisPoints.ZERO), clock);
        otc.negotiate(new OtcInstruction(AAPL_PUT, Side.BUY, Quantity.of(8), DEALER,
                BasisPoints.ZERO), clock);

        clock.advanceTo(AAPL_SELL_DATE);
        cross(router, catalog, AAPL, Side.BUY, Price.of("192.00"), 200, clock);

        clock.advanceTo(BOND_TRADE_DATE);
        cross(router, catalog, CORP_BOND, Side.SELL, Price.of("998.00"), 250, clock);

        clock.advanceTo(VALUATION_DATE);
        otc.negotiate(new OtcInstruction(EUR_FORWARD, Side.BUY, Quantity.of(1), DEALER,
                BasisPoints.ZERO), clock);
        otc.negotiate(new OtcInstruction(SWAP, Side.BUY, Quantity.of(1), DEALER,
                BasisPoints.ZERO), clock);
        cross(router, catalog, EUR_BOND, Side.SELL, Price.of("990.00"), 200, clock);

        return keeper.ledger();
    }

    /**
     * The market maker rests {@code makerSide} at exactly {@code price}, and Mercury crosses it
     * in full with an immediate market order on the other side - the only way to make a fill
     * execute at a hand-picked price on a venue that otherwise prices by matching, not by
     * instruction. Never books the market maker's own side: {@link LedgerKeeper} only books
     * trades owned by {@link #MERCURY_BOOK}, so the resting order's fill is announced and
     * ignored, exactly as {@link EndToEndDemo} already relies on for the market maker's side of
     * every exchange-traded fill.
     */
    private static void cross(ExecutionRouter router, InstrumentCatalog catalog,
                              InstrumentId instrumentId, Side makerSide, Price price,
                              long quantity, SimulationClock clock) {
        FinancialInstrument instrument = catalog.require(instrumentId);
        router.execute(instrument, OrderBookInstruction.limit(
                instrumentId, makerSide, price, quantity, MARKET_MAKER), clock);
        router.execute(instrument, OrderBookInstruction.market(
                instrumentId, makerSide.isBuy() ? Side.SELL : Side.BUY, quantity, MERCURY_BOOK),
                clock);
    }

    /**
     * Every model the demo needs.
     *
     * <p>Five instrument types, four models - the discounted-cashflow model is registered
     * twice, once per cashflow-bearing instrument, and prices bonds in two currencies through
     * the same registration. The swap arriving at M6 cost exactly one
     * line here and changed nothing else in this file beyond adding the instrument and the
     * position, which is the open-closed claim behaving as advertised on a real addition
     * rather than on a test fixture.
     */
    public static PricingService pricingService() {
        return PricingService.builder()
                .register(new SpotPriceModel())
                .register(new BlackScholesModel())
                .register(new DiscountedCashflowModel<>(Bond.class))
                .register(new DiscountedCashflowModel<>(FxForward.class))
                .register(new SwapModel())
                .build();
    }

    public static PortfolioValuationService valuationService() {
        return new PortfolioValuationService(
                pricingService(), InstrumentCatalog.of(instruments()));
    }

    public static SensitivityCalculator sensitivityCalculator() {
        return new SensitivityCalculator(valuationService());
    }

    /**
     * What the report measures risk against: the two equity underlyings, the euro exposure the
     * forward creates, and both discount curves the book discounts on.
     *
     * <p>Listed rather than derived - see {@link RiskFactors}. Before M5 this was the two
     * equities alone, which described the portfolio accurately at the time and stopped doing so
     * the moment a bond and an FX forward were added to it.
     */
    public static RiskFactors riskFactors() {
        return new RiskFactors(
                List.of(AAPL, MSFT),
                List.of(AAPL),
                List.of(EURUSD),
                List.of(Currency.USD, Currency.EUR),
                List.of(Tenor.years(1), Tenor.years(2), Tenor.years(5), Tenor.years(10)));
    }

    /**
     * The three named scenarios M11 asks for - listed here rather than in the engine, for the
     * same reason {@link #riskFactors()} is: which scenarios a book is reported against is a
     * reporting decision, not an engine constant.
     */
    public static List<Scenario> scenarios() {
        return List.of(
                // Unchanged from the single hardcoded stress shock M4-M10 printed, so this
                // scenario's golden-master number does not move - see README.md for the
                // worked explanation of why it comes to -86,093.00.
                Scenario.of("Market Crash",
                        "equities -30%, volatility +50%, FX -10%, rates +150bp",
                        MarketShock.scaleAllSpots(0.70),
                        MarketShock.scaleAllVolatilities(1.50),
                        MarketShock.scaleAllFxRates(0.90),
                        MarketShock.bumpAllRates(BasisPoints.of(150))),
                // Isolates the DV01 exposure the RISK section already reports, as its own
                // scenario instead of blended into Market Crash's four factors.
                Scenario.of("Rate Shock",
                        "rates +200bp across every currency",
                        MarketShock.bumpAllRates(BasisPoints.of(200))),
                // The emerging-market pattern: a currency collapses while local rates spike to
                // defend it. Hits both EUR-denominated positions in the book (the forward and
                // the bond) in economically opposite-signed ways - genuinely distinct from
                // Market Crash's blended -10% FX leg, not a smaller copy of it.
                Scenario.of("Currency Crisis",
                        "EUR/USD -20%, EUR rates +300bp",
                        MarketShock.scaleFxRate(EURUSD, 0.80),
                        MarketShock.bumpRate(Currency.EUR, BasisPoints.of(300))));
    }
}
