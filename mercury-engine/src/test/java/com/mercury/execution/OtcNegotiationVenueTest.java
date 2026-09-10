package com.mercury.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.instrument.AssetClass;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.instrument.TradabilityProfile;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.matching.Side;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.pricing.ModelName;
import com.mercury.pricing.PricingModel;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.ValuationResult;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeStatus;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OtcNegotiationVenueTest {

    private static final LocalDate VALUATION_DATE = LocalDate.of(2026, 3, 2);
    private static final SimulationClock CLOCK = SimulationClock.fixedAt(VALUATION_DATE);
    private static final CounterpartyId OWN_BOOK = CounterpartyId.of("CPTY-MERCURY");
    private static final CounterpartyId COUNTERPARTY = CounterpartyId.of("CPTY-ACME");

    /** A minimal OTC instrument, priced at a fixed 100.00 - just enough to exercise the venue. */
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

    private static final TestOtcInstrument INSTRUMENT =
            new TestOtcInstrument(InstrumentId.of("OTC-TEST"), Currency.USD);

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

    /** Prices at exactly zero - the net-present-value-at-inception case a swap hits routinely. */
    private static final class ZeroPriceModel implements PricingModel<TestOtcInstrument> {
        @Override
        public Class<TestOtcInstrument> instrumentType() {
            return TestOtcInstrument.class;
        }

        @Override
        public ModelName name() {
            return ModelName.of("zero");
        }

        @Override
        public ValuationResult price(TestOtcInstrument instrument, MarketDataSnapshot market,
                                     LocalDate asOf) {
            return new ValuationResult(0.0, instrument.currency(), name());
        }
    }

    private static OtcNegotiationVenue newVenue(BasisPoints ignored) {
        PricingService pricingService = PricingService.builder().register(new FixedPriceModel()).build();
        MarketDataSnapshot market = MarketDataSnapshot.builder(VALUATION_DATE).build();
        InstrumentCatalog catalog = InstrumentCatalog.of(INSTRUMENT);
        return new OtcNegotiationVenue(pricingService, market, catalog, new TradeIdGenerator("TRD-"),
                OWN_BOOK);
    }

    private static OtcNegotiationVenue newZeroPricedVenue() {
        PricingService pricingService = PricingService.builder().register(new ZeroPriceModel()).build();
        MarketDataSnapshot market = MarketDataSnapshot.builder(VALUATION_DATE).build();
        InstrumentCatalog catalog = InstrumentCatalog.of(INSTRUMENT);
        return new OtcNegotiationVenue(pricingService, market, catalog, new TradeIdGenerator("TRD-"),
                OWN_BOOK);
    }

    @Test
    void aBuyPaysAboveTheMid() {
        OtcNegotiationVenue venue = newVenue(BasisPoints.ZERO);
        OtcInstruction instruction = new OtcInstruction(INSTRUMENT.id(), Side.BUY, Quantity.of(100),
                COUNTERPARTY, BasisPoints.ofPercent(1.0));

        List<Trade> trades = venue.execute(instruction, CLOCK);

        assertThat(trades).hasSize(1);
        Trade trade = trades.get(0);
        // mid 100.00, +1% spread = 101.00 per unit, x 100 units = 10100.00.
        assertThat(trade.consideration()).isEqualTo(Money.of("10100.00", Currency.USD));
        assertThat(trade.delta()).isEqualTo(Quantity.of(100));
        assertThat(trade.owner()).isEqualTo(OWN_BOOK);
        assertThat(trade.counterparty()).contains(COUNTERPARTY);
        assertThat(trade.status()).isEqualTo(TradeStatus.EXECUTED);
    }

    @Test
    void aSellReceivesBelowTheMid() {
        OtcNegotiationVenue venue = newVenue(BasisPoints.ZERO);
        OtcInstruction instruction = new OtcInstruction(INSTRUMENT.id(), Side.SELL, Quantity.of(100),
                COUNTERPARTY, BasisPoints.ofPercent(1.0));

        List<Trade> trades = venue.execute(instruction, CLOCK);

        Trade trade = trades.get(0);
        // mid 100.00, -1% spread = 99.00 per unit, x 100 units = 9900.00, negated (money in).
        assertThat(trade.consideration()).isEqualTo(Money.of("-9900.00", Currency.USD));
        assertThat(trade.delta()).isEqualTo(Quantity.of(-100));
    }

    @Test
    void zeroSpreadTradesExactlyAtTheMid() {
        OtcNegotiationVenue venue = newVenue(BasisPoints.ZERO);
        OtcInstruction instruction = new OtcInstruction(INSTRUMENT.id(), Side.BUY, Quantity.of(10),
                COUNTERPARTY, BasisPoints.ZERO);

        Trade trade = venue.execute(instruction, CLOCK).get(0);

        assertThat(trade.consideration()).isEqualTo(Money.of("1000.00", Currency.USD));
    }

    @Test
    void refusesANonzeroSpreadThatHadNoEffectOnAZeroPricedInstrument() {
        // The bug this guards against: a swap at inception prices at (or near) zero, and a
        // percentage-of-mid spread on a number that is already zero is still zero - a trade
        // would silently claim a spread was applied when it was not.
        OtcNegotiationVenue venue = newZeroPricedVenue();
        OtcInstruction instruction = new OtcInstruction(INSTRUMENT.id(), Side.BUY, Quantity.of(1),
                COUNTERPARTY, BasisPoints.ofPercent(0.10));

        assertThatThrownBy(() -> venue.execute(instruction, CLOCK))
                .isInstanceOf(OtcNegotiationVenue.SpreadHadNoEffectException.class)
                .hasMessageContaining("net-present-value");
    }

    @Test
    void aZeroSpreadOnAZeroPricedInstrumentIsNotAnError() {
        // No spread was ever requested, so there is nothing for it to have failed to do -
        // a genuinely zero-consideration trade (e.g. a swap at inception) is legitimate.
        OtcNegotiationVenue venue = newZeroPricedVenue();
        OtcInstruction instruction = new OtcInstruction(INSTRUMENT.id(), Side.BUY, Quantity.of(1),
                COUNTERPARTY, BasisPoints.ZERO);

        Trade trade = venue.execute(instruction, CLOCK).get(0);

        assertThat(trade.consideration()).isEqualTo(Money.zero(Currency.USD));
    }

    @Test
    void rejectsAnOrderBookInstruction() {
        OtcNegotiationVenue venue = newVenue(BasisPoints.ZERO);
        OrderBookInstruction wrong = OrderBookInstruction.market(
                INSTRUMENT.id(), Side.BUY, 10, COUNTERPARTY);

        assertThatThrownBy(() -> venue.execute(wrong, CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OtcInstruction");
    }
}
