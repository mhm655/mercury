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
import com.mercury.instrument.Bond;
import com.mercury.instrument.EuropeanOption;
import com.mercury.instrument.FxForward;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.instrument.Stock;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioValuationService;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.model.BlackScholesModel;
import com.mercury.pricing.model.DiscountedCashflowModel;
import com.mercury.pricing.model.SpotPriceModel;
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

    /** Fixed, so the scenario never depends on when it is run. */
    public static final LocalDate VALUATION_DATE = LocalDate.of(2024, 6, 28);

    private static final LocalDate EXPIRY = LocalDate.of(2025, 6, 20);
    private static final LocalDate BOND_MATURITY = LocalDate.of(2029, 6, 15);
    private static final LocalDate FORWARD_SETTLEMENT = LocalDate.of(2025, 6, 27);
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
                FxForward.buy("FWD-EURUSD", EURUSD, "500000", "1.09", FORWARD_SETTLEMENT));
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

    /** A market with everything the pricers need, and nothing they do not. */
    public static MarketDataSnapshot market() {
        return MarketDataSnapshot.builder()
                .spot(AAPL, 195.50)
                .spot(MSFT, 412.25)
                .volatility(AAPL, 0.28)
                .discountRate(Currency.USD, 0.045)
                .discountRate(Currency.EUR, 0.032)
                .fxRate(EURUSD, 1.0725)
                .build();
    }

    /**
     * A long equity book with an options overlay: long stock, a covered call written against
     * it, and a protective put.
     */
    public static Portfolio portfolio() {
        return Portfolio.builder(PortfolioId.of("US-EQUITY-BOOK"), Currency.USD)
                .position(AAPL, 1_000)
                .position(MSFT, 250)
                .position(AAPL_CALL, -5)
                .position(AAPL_PUT, 8)
                .position(CORP_BOND, 250)
                .position(EUR_FORWARD, 1)
                .build();
    }

    /**
     * Every model the demo needs.
     *
     * <p>Four instrument types, three models - the discounted-cashflow model is registered
     * twice, once per cashflow-bearing instrument. Adding a fifth type would add exactly one
     * line here and change nothing else.
     */
    public static PricingService pricingService() {
        return PricingService.builder()
                .register(new SpotPriceModel())
                .register(new BlackScholesModel())
                .register(new DiscountedCashflowModel<>(Bond.class))
                .register(new DiscountedCashflowModel<>(FxForward.class))
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
                List.of(Currency.USD, Currency.EUR));
    }
}
