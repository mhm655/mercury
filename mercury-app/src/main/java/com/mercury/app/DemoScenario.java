package com.mercury.app;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.time.Frequency;
import com.mercury.core.time.HolidayCalendar;
import com.mercury.core.time.SimulationClock;
import com.mercury.core.time.Tenor;
import com.mercury.curve.CurveBootstrapper;
import com.mercury.curve.CurveInstrument;
import com.mercury.curve.DepositQuote;
import com.mercury.curve.ParSwapQuote;
import com.mercury.curve.YieldCurve;
import com.mercury.instrument.Bond;
import com.mercury.instrument.EuropeanOption;
import com.mercury.instrument.FloatingRateIndex;
import com.mercury.instrument.FxForward;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.instrument.InterestRateSwap;
import com.mercury.instrument.Stock;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioValuationService;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.model.BlackScholesModel;
import com.mercury.pricing.model.DiscountedCashflowModel;
import com.mercury.pricing.model.SpotPriceModel;
import com.mercury.pricing.model.SwapModel;
import com.mercury.risk.SensitivityCalculator;
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

    private static final LocalDate EXPIRY = LocalDate.of(2025, 6, 20);
    private static final LocalDate BOND_MATURITY = LocalDate.of(2029, 6, 15);
    private static final LocalDate FORWARD_SETTLEMENT = LocalDate.of(2025, 6, 27);
    private static final LocalDate SWAP_MATURITY = LocalDate.of(2029, 6, 28);
    private static final LocalDate EUR_BOND_MATURITY = LocalDate.of(2027, 6, 28);
    private static final CurrencyPair EURUSD = CurrencyPair.parse("EUR/USD");

    private DemoScenario() {
    }

    /** A clock frozen at the valuation date, injected rather than read. */
    public static SimulationClock clock() {
        return SimulationClock.fixedAt(VALUATION_DATE);
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
        return Portfolio.builder(PortfolioId.of("US-EQUITY-BOOK"), Currency.USD)
                .position(AAPL, 1_000)
                .position(MSFT, 250)
                .position(AAPL_CALL, -5)
                .position(AAPL_PUT, 8)
                .position(CORP_BOND, 250)
                .position(EUR_FORWARD, 1)
                .position(SWAP, 1)
                .position(EUR_BOND, 200)
                .build();
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
                List.of(EURUSD),
                List.of(Currency.USD, Currency.EUR),
                List.of(Tenor.years(1), Tenor.years(2), Tenor.years(5), Tenor.years(10)));
    }
}
