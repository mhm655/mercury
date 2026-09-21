package com.mercury.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.TradeId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.event.EventBus;
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
import com.mercury.trade.Counterparty;
import com.mercury.trade.CreditLimit;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeStatus;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link ExposureLedger} in isolation, and the fix it exists for: two
 * {@link OtcNegotiationVenue} instances sharing one ledger enforce a limit as a whole, rather
 * than each thinking it has the counterparty to itself - the gap
 * {@code docs/KNOWN_GAPS.md}'s former "Risk limits | Exposure is per-venue-instance, not
 * global" entry described.
 */
class ExposureLedgerTest {

    private static final LocalDate VALUATION_DATE = LocalDate.of(2026, 3, 2);
    private static final SimulationClock CLOCK = SimulationClock.fixedAt(VALUATION_DATE);
    private static final CounterpartyId OWN_BOOK = CounterpartyId.of("CPTY-MERCURY");
    private static final CounterpartyId COUNTERPARTY = CounterpartyId.of("CPTY-ACME");
    private static final InstrumentId INSTRUMENT_ID = InstrumentId.of("OTC-TEST");

    private record TestOtcInstrument(InstrumentId id, Currency currency) implements FinancialInstrument {
        @Override
        public TradabilityProfile tradability() {
            return TradabilityProfile.OVER_THE_COUNTER;
        }

        @Override
        public String description() {
            return "Test OTC instrument";
        }
    }

    private static final TestOtcInstrument INSTRUMENT = new TestOtcInstrument(INSTRUMENT_ID, Currency.USD);

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

    // ---------------------------------------------------------------- ledger alone

    @Test
    void exposureToDefaultsToZero() {
        ExposureLedger ledger = new ExposureLedger();

        assertThat(ledger.exposureTo(COUNTERPARTY, Currency.USD)).isEqualTo(Money.zero(Currency.USD));
    }

    @Test
    void commitRecordsExposureAndReleaseFreesIt() {
        ExposureLedger ledger = new ExposureLedger();
        Trade settled = settledTrade("TRD-1");

        ledger.commit(COUNTERPARTY, Money.of("500.00", Currency.USD), settled, Money.of("500.00", Currency.USD));
        assertThat(ledger.exposureTo(COUNTERPARTY, Currency.USD)).isEqualTo(Money.of("500.00", Currency.USD));

        ledger.release(settled);
        assertThat(ledger.exposureTo(COUNTERPARTY, Currency.USD)).isEqualTo(Money.zero(Currency.USD));
    }

    @Test
    void releaseRejectsATradeNeverRecorded() {
        ExposureLedger ledger = new ExposureLedger();

        assertThatThrownBy(() -> ledger.release(settledTrade("TRD-404")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never recorded");
    }

    @Test
    void releaseRejectsATradeThatIsNotSettledOrCancelled() {
        ExposureLedger ledger = new ExposureLedger();
        Trade executed = executedTrade("TRD-1");
        ledger.commit(COUNTERPARTY, Money.of("500.00", Currency.USD), executed, Money.of("500.00", Currency.USD));

        assertThatThrownBy(() -> ledger.release(executed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SETTLED or CANCELLED");
    }

    @Test
    void releasingTheSameTradeTwiceIsRejected() {
        ExposureLedger ledger = new ExposureLedger();
        Trade settled = settledTrade("TRD-1");
        ledger.commit(COUNTERPARTY, Money.of("500.00", Currency.USD), settled, Money.of("500.00", Currency.USD));

        ledger.release(settled);

        assertThatThrownBy(() -> ledger.release(settled))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- two venues, one ledger

    @Test
    void twoVenuesSharingALedgerEnforceOneCombinedLimit() {
        // Sized for exactly one 500-unit trade at 100.00/unit: the first venue's trade should
        // exhaust it, so the second venue - a different instance, same ledger - must see no
        // room left. Before M18 each venue tracked exposure independently and both would have
        // admitted their own trade, together breaching a limit neither venue believed it had.
        ExposureLedger shared = new ExposureLedger();
        CreditLimit limit = new CreditLimit(Money.of("50000.00", Currency.USD));
        OtcNegotiationVenue first = venueSharing(shared, limit);
        OtcNegotiationVenue second = venueSharing(shared, limit);

        NegotiationResult firstResult = first.negotiate(new OtcInstruction(
                INSTRUMENT_ID, Side.BUY, Quantity.of(500), COUNTERPARTY, BasisPoints.ZERO), CLOCK);
        assertThat(firstResult.isRejected()).isFalse();

        NegotiationResult secondResult = second.negotiate(new OtcInstruction(
                INSTRUMENT_ID, Side.BUY, Quantity.of(1), COUNTERPARTY, BasisPoints.ZERO), CLOCK);

        assertThat(secondResult.isRejected()).isTrue();
        assertThat(first.exposureTo(COUNTERPARTY)).isEqualTo(second.exposureTo(COUNTERPARTY));
    }

    @Test
    void twoVenuesSharingALedgerNeverAdmitMoreThanTheCombinedLimit() throws InterruptedException {
        int perAttemptNotional = 100; // 100 units at 100.00 = 10,000.00 each
        int limitInAttempts = 100;
        CreditLimit limit = new CreditLimit(Money.of(
                String.valueOf(perAttemptNotional * 100L * limitInAttempts), Currency.USD));
        ExposureLedger shared = new ExposureLedger();
        OtcNegotiationVenue first = venueSharing(shared, limit);
        OtcNegotiationVenue second = venueSharing(shared, limit);

        int threads = 16;
        int attemptsPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            OtcNegotiationVenue venue = (i % 2 == 0) ? first : second;
            pool.execute(() -> {
                try {
                    for (int a = 0; a < attemptsPerThread; a++) {
                        NegotiationResult result = venue.negotiate(new OtcInstruction(INSTRUMENT_ID,
                                Side.BUY, Quantity.of(perAttemptNotional), COUNTERPARTY, BasisPoints.ZERO), CLOCK);
                        if (!result.isRejected()) {
                            accepted.incrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(accepted.get()).isEqualTo(limitInAttempts);
        assertThat(first.exposureTo(COUNTERPARTY)).isEqualTo(limit.maximum());
    }

    private static OtcNegotiationVenue venueSharing(ExposureLedger ledger, CreditLimit limit) {
        PricingService pricingService = PricingService.builder().register(new FixedPriceModel()).build();
        MarketDataSnapshot market = MarketDataSnapshot.builder(VALUATION_DATE).build();
        InstrumentCatalog catalog = InstrumentCatalog.of(INSTRUMENT);
        Counterparty counterparty = new Counterparty(COUNTERPARTY, "Acme Capital", limit);
        CounterpartyDirectory counterparties = CounterpartyDirectory.of(counterparty);
        return new OtcNegotiationVenue(pricingService, market, catalog, new TradeIdGenerator("TRD-"),
                OWN_BOOK, counterparties, new CounterpartyExposureLimit(),
                EventBus.ignoring(), ledger);
    }

    private static Trade executedTrade(String id) {
        return Trade.newTrade(TradeId.of(id), INSTRUMENT_ID, OWN_BOOK, Quantity.of(100),
                        Money.of("500.00", Currency.USD), VALUATION_DATE, Optional.empty(),
                        Optional.of(COUNTERPARTY))
                .transitionTo(TradeStatus.VALIDATED, "priced on request", CLOCK)
                .transitionTo(TradeStatus.BOOKED, "booked to the ledger", CLOCK)
                .transitionTo(TradeStatus.EXECUTED, "negotiated", CLOCK);
    }

    private static Trade settledTrade(String id) {
        return executedTrade(id)
                .transitionTo(TradeStatus.CONFIRMED, "confirmation sent", CLOCK)
                .transitionTo(TradeStatus.SETTLED, "cash and securities exchanged", CLOCK);
    }
}
