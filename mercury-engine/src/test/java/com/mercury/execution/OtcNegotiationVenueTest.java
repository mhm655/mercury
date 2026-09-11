package com.mercury.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
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
import com.mercury.risk.CounterpartyExposureLimit;
import com.mercury.risk.RiskLimit;
import com.mercury.trade.CreditLimit;
import com.mercury.trade.Counterparty;
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

    /** A limit generous enough that it never fires in tests that aren't about the risk check. */
    private static final Counterparty ACME = new Counterparty(COUNTERPARTY, "Acme Capital",
            new CreditLimit(Money.of("1000000000.00", Currency.USD)));

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
        return newVenue(new FixedPriceModel(), ACME.creditLimit());
    }

    private static OtcNegotiationVenue newZeroPricedVenue() {
        PricingService pricingService = PricingService.builder().register(new ZeroPriceModel()).build();
        MarketDataSnapshot market = MarketDataSnapshot.builder(VALUATION_DATE).build();
        InstrumentCatalog catalog = InstrumentCatalog.of(INSTRUMENT);
        CounterpartyDirectory counterparties = CounterpartyDirectory.of(ACME);
        return new OtcNegotiationVenue(pricingService, market, catalog, new TradeIdGenerator("TRD-"),
                OWN_BOOK, counterparties, new CounterpartyExposureLimit());
    }

    private static OtcNegotiationVenue newVenue(PricingModel<TestOtcInstrument> model,
                                                CreditLimit creditLimit) {
        PricingService pricingService = PricingService.builder().register(model).build();
        MarketDataSnapshot market = MarketDataSnapshot.builder(VALUATION_DATE).build();
        InstrumentCatalog catalog = InstrumentCatalog.of(INSTRUMENT);
        Counterparty counterparty = new Counterparty(COUNTERPARTY, "Acme Capital", creditLimit);
        CounterpartyDirectory counterparties = CounterpartyDirectory.of(counterparty);
        return new OtcNegotiationVenue(pricingService, market, catalog, new TradeIdGenerator("TRD-"),
                OWN_BOOK, counterparties, new CounterpartyExposureLimit());
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

    @Test
    void aTradeWithinTheCreditLimitExecutes() {
        // mid 100.00, no spread, x 100 units = 10,000.00 - comfortably under a 20,000 limit.
        OtcNegotiationVenue venue = newVenue(new FixedPriceModel(),
                new CreditLimit(Money.of("20000.00", Currency.USD)));
        OtcInstruction instruction = new OtcInstruction(INSTRUMENT.id(), Side.BUY, Quantity.of(100),
                COUNTERPARTY, BasisPoints.ZERO);

        NegotiationResult result = venue.negotiate(instruction, CLOCK);

        assertThat(result.isRejected()).isFalse();
        assertThat(result.trades()).hasSize(1);
    }

    @Test
    void aTradeOverTheCreditLimitIsRejectedRatherThanExecuted() {
        // mid 100.00, no spread, x 100 units = 10,000.00 - over a 5,000 limit.
        OtcNegotiationVenue venue = newVenue(new FixedPriceModel(),
                new CreditLimit(Money.of("5000.00", Currency.USD)));
        OtcInstruction instruction = new OtcInstruction(INSTRUMENT.id(), Side.BUY, Quantity.of(100),
                COUNTERPARTY, BasisPoints.ZERO);

        NegotiationResult result = venue.negotiate(instruction, CLOCK);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.trades()).isEmpty();
        assertThat(result.breaches()).hasSize(1);
        assertThat(result.breaches().get(0).counterparty()).isEqualTo(COUNTERPARTY);
        assertThat(result.breaches().get(0).projectedExposure())
                .isEqualTo(Money.of("10000.00", Currency.USD));
    }

    @Test
    void aRejectedNegotiationDoesNotAdvanceTradeIds() {
        OtcNegotiationVenue venue = newVenue(new FixedPriceModel(),
                new CreditLimit(Money.of("5000.00", Currency.USD)));
        OtcInstruction tooLarge = new OtcInstruction(INSTRUMENT.id(), Side.BUY, Quantity.of(100),
                COUNTERPARTY, BasisPoints.ZERO);
        venue.negotiate(tooLarge, CLOCK);

        // A trade small enough to fit under the same limit still gets the first trade id -
        // proof the rejected attempt above never minted one.
        OtcInstruction fits = new OtcInstruction(INSTRUMENT.id(), Side.BUY, Quantity.of(10),
                COUNTERPARTY, BasisPoints.ZERO);
        NegotiationResult result = venue.negotiate(fits, CLOCK);

        assertThat(result.trades().get(0).id().value()).isEqualTo("TRD-1");
    }

    @Test
    void exposureAccumulatesAcrossNegotiationsAndABreachStopsShortOfCommittingIt() {
        // Limit 15,000: first trade of 10,000 fits, a second of 10,000 would total 20,000 and
        // breach. The rejection must not have moved the running total past the first trade's
        // 10,000, so a later trade of 5,000 (total 15,000) still fits exactly.
        OtcNegotiationVenue venue = newVenue(new FixedPriceModel(),
                new CreditLimit(Money.of("15000.00", Currency.USD)));
        NegotiationResult first = venue.negotiate(new OtcInstruction(INSTRUMENT.id(), Side.BUY,
                Quantity.of(100), COUNTERPARTY, BasisPoints.ZERO), CLOCK);
        assertThat(first.isRejected()).isFalse();

        NegotiationResult secondTooLarge = venue.negotiate(new OtcInstruction(INSTRUMENT.id(),
                Side.BUY, Quantity.of(100), COUNTERPARTY, BasisPoints.ZERO), CLOCK);
        assertThat(secondTooLarge.isRejected()).isTrue();

        NegotiationResult third = venue.negotiate(new OtcInstruction(INSTRUMENT.id(), Side.BUY,
                Quantity.of(50), COUNTERPARTY, BasisPoints.ZERO), CLOCK);
        assertThat(third.isRejected()).isFalse();
    }

    @Test
    void exposureToReportsTheRunningTotalWithoutTradingAndIsUnaffectedByARejection() {
        OtcNegotiationVenue venue = newVenue(new FixedPriceModel(),
                new CreditLimit(Money.of("15000.00", Currency.USD)));
        assertThat(venue.exposureTo(COUNTERPARTY)).isEqualTo(Money.zero(Currency.USD));

        venue.negotiate(new OtcInstruction(INSTRUMENT.id(), Side.BUY, Quantity.of(100),
                COUNTERPARTY, BasisPoints.ZERO), CLOCK);
        assertThat(venue.exposureTo(COUNTERPARTY)).isEqualTo(Money.of("10000.00", Currency.USD));

        venue.negotiate(new OtcInstruction(INSTRUMENT.id(), Side.BUY, Quantity.of(100),
                COUNTERPARTY, BasisPoints.ZERO), CLOCK);
        assertThat(venue.exposureTo(COUNTERPARTY))
                .as("a rejected negotiation must not move the running total")
                .isEqualTo(Money.of("10000.00", Currency.USD));
    }

    @Test
    void projectedExposureIsConvertedIntoTheCreditLimitsOwnCurrency() {
        // Instrument prices in USD; the counterparty's credit limit is stated in EUR. The
        // check must convert the USD consideration into EUR before comparing it to the limit -
        // this is the one path in OtcNegotiationVenue that reads MarketDataSnapshot.fxRate.
        PricingService pricingService = PricingService.builder().register(new FixedPriceModel()).build();
        MarketDataSnapshot market = MarketDataSnapshot.builder(VALUATION_DATE)
                .fxRate(CurrencyPair.of(Currency.EUR, Currency.USD), 1.10)
                .build();
        InstrumentCatalog catalog = InstrumentCatalog.of(INSTRUMENT);
        // mid 100.00, x 100 units = 10,000.00 USD = 9,090.91 EUR at 1.10 - over an 8,000 EUR limit.
        Counterparty counterparty = new Counterparty(COUNTERPARTY, "Acme Capital",
                new CreditLimit(Money.of("8000.00", Currency.EUR)));
        CounterpartyDirectory counterparties = CounterpartyDirectory.of(counterparty);
        OtcNegotiationVenue venue = new OtcNegotiationVenue(pricingService, market, catalog,
                new TradeIdGenerator("TRD-"), OWN_BOOK, counterparties, new CounterpartyExposureLimit());

        NegotiationResult result = venue.negotiate(new OtcInstruction(INSTRUMENT.id(), Side.BUY,
                Quantity.of(100), COUNTERPARTY, BasisPoints.ZERO), CLOCK);

        assertThat(result.isRejected()).isTrue();
        assertThat(result.breaches().get(0).projectedExposure().currency()).isEqualTo(Currency.EUR);
        assertThat(venue.exposureTo(COUNTERPARTY)).isEqualTo(Money.zero(Currency.EUR));
    }

    @Test
    void releasingASettledTradeFreesUpRoomForAnotherOne() {
        // Limit 10,000: one trade of 10,000 fills it exactly. A second identical trade must
        // be rejected until the first is released by settlement, then must fit again.
        OtcNegotiationVenue venue = newVenue(new FixedPriceModel(),
                new CreditLimit(Money.of("10000.00", Currency.USD)));
        OtcInstruction instruction = new OtcInstruction(INSTRUMENT.id(), Side.BUY, Quantity.of(100),
                COUNTERPARTY, BasisPoints.ZERO);
        Trade first = venue.negotiate(instruction, CLOCK).trades().get(0);
        assertThat(venue.negotiate(instruction, CLOCK).isRejected()).isTrue();

        Trade settled = first.transitionTo(TradeStatus.CONFIRMED, "confirmed", CLOCK)
                .transitionTo(TradeStatus.SETTLED, "settled", CLOCK);
        venue.release(settled);

        assertThat(venue.exposureTo(COUNTERPARTY)).isEqualTo(Money.zero(Currency.USD));
        assertThat(venue.negotiate(instruction, CLOCK).isRejected()).isFalse();
    }

    @Test
    void releasingATradeThatIsNotSettledIsRejected() {
        OtcNegotiationVenue venue = newVenue(BasisPoints.ZERO);
        OtcInstruction instruction = new OtcInstruction(INSTRUMENT.id(), Side.BUY, Quantity.of(100),
                COUNTERPARTY, BasisPoints.ZERO);
        Trade executed = venue.negotiate(instruction, CLOCK).trades().get(0);

        assertThatThrownBy(() -> venue.release(executed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SETTLED");
    }

    @Test
    void releasingTheSameTradeTwiceIsRejected() {
        OtcNegotiationVenue venue = newVenue(BasisPoints.ZERO);
        OtcInstruction instruction = new OtcInstruction(INSTRUMENT.id(), Side.BUY, Quantity.of(100),
                COUNTERPARTY, BasisPoints.ZERO);
        Trade settled = venue.negotiate(instruction, CLOCK).trades().get(0)
                .transitionTo(TradeStatus.CONFIRMED, "confirmed", CLOCK)
                .transitionTo(TradeStatus.SETTLED, "settled", CLOCK);
        venue.release(settled);

        assertThatThrownBy(() -> venue.release(settled))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been released");
    }
}
