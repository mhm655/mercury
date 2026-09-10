package com.mercury.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.instrument.AssetClass;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.instrument.Stock;
import com.mercury.instrument.TradabilityProfile;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.matching.Side;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.pricing.ModelName;
import com.mercury.pricing.PricingModel;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.ValuationResult;
import com.mercury.pricing.model.SpotPriceModel;
import com.mercury.risk.RiskLimit;
import com.mercury.trade.CreditLimit;
import com.mercury.trade.Counterparty;
import com.mercury.trade.Trade;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionRouterTest {

    private static final LocalDate VALUATION_DATE = LocalDate.of(2026, 3, 2);
    private static final SimulationClock CLOCK = SimulationClock.fixedAt(VALUATION_DATE);
    private static final CounterpartyId BUYER = CounterpartyId.of("CPTY-BUYER");
    private static final CounterpartyId SELLER = CounterpartyId.of("CPTY-SELLER");
    private static final CounterpartyDirectory COUNTERPARTIES = CounterpartyDirectory.of(
            new Counterparty(SELLER, "Seller Capital", new CreditLimit(Money.of("1000000000.00", Currency.USD))));

    /** A minimal OTC instrument, priced at a fixed 100.00 - just enough to prove routing. */
    private record TestOtcInstrument(InstrumentId id, Currency currency) implements FinancialInstrument {
        @Override
        public AssetClass assetClass() {
            return AssetClass.RATES;
        }

        @Override
        public TradabilityProfile tradability() {
            return TradabilityProfile.OVER_THE_COUNTER;
        }

        @Override
        public String description() {
            return "Test OTC instrument";
        }
    }

    private static final class FixedPriceModel implements PricingModel<TestOtcInstrument> {
        @Override
        public Class<TestOtcInstrument> instrumentType() {
            return TestOtcInstrument.class;
        }

        @Override
        public ModelName name() {
            return ModelName.of("fixed");
        }

        @Override
        public ValuationResult price(TestOtcInstrument instrument, MarketDataSnapshot market,
                                     LocalDate asOf) {
            return new ValuationResult(100.00, instrument.currency(), name());
        }
    }

    @Test
    void routesAnExchangeTradedInstrumentToTheOrderBookVenue() {
        Stock aapl = Stock.of("AAPL", Currency.USD);
        InstrumentCatalog catalog = InstrumentCatalog.of(aapl);
        ExecutionRouter router = new ExecutionRouter(
                new OrderBookVenue(new TradeIdGenerator("TRD-"), catalog),
                new OtcNegotiationVenue(PricingService.builder().register(new SpotPriceModel()).build(),
                        MarketDataSnapshot.builder(VALUATION_DATE).spot(aapl.id(), 195.50).build(),
                        catalog, new TradeIdGenerator("TRD-"), BUYER, COUNTERPARTIES, RiskLimit.none()));

        router.execute(aapl, OrderBookInstruction.limit(
                aapl.id(), Side.SELL, Price.of("100.00"), 100, SELLER), CLOCK);
        List<Trade> trades = router.execute(aapl, OrderBookInstruction.limit(
                aapl.id(), Side.BUY, Price.of("100.00"), 100, BUYER), CLOCK);

        assertThat(trades).hasSize(2);
        assertThat(trades).allMatch(t -> t.counterparty().isEmpty());
    }

    @Test
    void routesAnOtcInstrumentToTheOtcVenue() {
        TestOtcInstrument instrument = new TestOtcInstrument(InstrumentId.of("OTC-TEST"), Currency.USD);
        InstrumentCatalog catalog = InstrumentCatalog.of(instrument);
        ExecutionRouter router = new ExecutionRouter(
                new OrderBookVenue(new TradeIdGenerator("TRD-"), catalog),
                new OtcNegotiationVenue(PricingService.builder().register(new FixedPriceModel()).build(),
                        MarketDataSnapshot.builder(VALUATION_DATE).build(),
                        catalog, new TradeIdGenerator("TRD-"), BUYER, COUNTERPARTIES, RiskLimit.none()));

        List<Trade> trades = router.execute(instrument,
                new OtcInstruction(instrument.id(), Side.BUY, Quantity.of(1), SELLER, BasisPoints.ZERO),
                CLOCK);

        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).counterparty()).contains(SELLER);
    }

    @Test
    void anInstructionOfTheWrongShapeForItsVenueStillThrows() {
        Stock aapl = Stock.of("AAPL", Currency.USD);
        InstrumentCatalog catalog = InstrumentCatalog.of(aapl);
        ExecutionRouter router = new ExecutionRouter(
                new OrderBookVenue(new TradeIdGenerator("TRD-"), catalog),
                new OtcNegotiationVenue(PricingService.builder().register(new SpotPriceModel()).build(),
                        MarketDataSnapshot.builder(VALUATION_DATE).spot(aapl.id(), 195.50).build(),
                        catalog, new TradeIdGenerator("TRD-"), BUYER, COUNTERPARTIES, RiskLimit.none()));

        assertThatThrownBy(() -> router.execute(aapl,
                new OtcInstruction(aapl.id(), Side.BUY, Quantity.of(1), SELLER, BasisPoints.ZERO),
                CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
