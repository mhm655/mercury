package com.mercury.app;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Price;
import com.mercury.core.time.SimulationClock;
import com.mercury.instrument.EuropeanOption;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.instrument.Stock;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioValuationService;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.model.BlackScholesModel;
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

    /** Fixed, so the scenario never depends on when it is run. */
    public static final LocalDate VALUATION_DATE = LocalDate.of(2024, 6, 28);

    private static final LocalDate EXPIRY = LocalDate.of(2025, 6, 20);

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
                EuropeanOption.put("AAPL-P-180", AAPL, Price.of("180"), EXPIRY, Currency.USD));
    }

    /** A market with everything the pricers need, and nothing they do not. */
    public static MarketDataSnapshot market() {
        return MarketDataSnapshot.builder()
                .spot(AAPL, 195.50)
                .spot(MSFT, 412.25)
                .volatility(AAPL, 0.28)
                .discountRate(Currency.USD, 0.045)
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
                .build();
    }

    /** Both models registered; adding a third instrument would add one more line here. */
    public static PricingService pricingService() {
        return PricingService.builder()
                .register(new SpotPriceModel())
                .register(new BlackScholesModel())
                .build();
    }

    public static PortfolioValuationService valuationService() {
        return new PortfolioValuationService(
                pricingService(), InstrumentCatalog.of(instruments()));
    }

    public static SensitivityCalculator sensitivityCalculator() {
        return new SensitivityCalculator(valuationService());
    }
}
