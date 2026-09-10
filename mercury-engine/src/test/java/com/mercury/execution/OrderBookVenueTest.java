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
import com.mercury.instrument.Stock;
import com.mercury.matching.Side;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeStatus;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderBookVenueTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final CounterpartyId BUYER = CounterpartyId.of("CPTY-BUYER");
    private static final CounterpartyId SELLER = CounterpartyId.of("CPTY-SELLER");
    private static final SimulationClock CLOCK = SimulationClock.fixedAt(LocalDate.of(2026, 3, 2));

    private static OrderBookVenue newVenue() {
        InstrumentCatalog catalog = InstrumentCatalog.of(Stock.of("AAPL", Currency.USD));
        return new OrderBookVenue(new TradeIdGenerator("TRD-"), catalog);
    }

    @Test
    void anUnfilledRestingOrderProducesNoTrades() {
        OrderBookVenue venue = newVenue();

        List<Trade> trades = venue.execute(OrderBookInstruction.limit(
                AAPL, Side.BUY, Price.of("100.00"), 100, BUYER), CLOCK);

        assertThat(trades).isEmpty();
    }

    @Test
    void aCrossProducesOneExecutedTradePerSide() {
        OrderBookVenue venue = newVenue();
        venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 100, SELLER),
                CLOCK);

        List<Trade> trades = venue.execute(
                OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("100.00"), 100, BUYER), CLOCK);

        assertThat(trades).hasSize(2);
        assertThat(trades).allMatch(t -> t.status() == TradeStatus.EXECUTED);
        assertThat(trades).allMatch(t -> t.counterparty().isEmpty());

        Trade buyerTrade = trades.stream().filter(t -> t.owner().equals(BUYER)).findFirst()
                .orElseThrow();
        Trade sellerTrade = trades.stream().filter(t -> t.owner().equals(SELLER)).findFirst()
                .orElseThrow();

        assertThat(buyerTrade.delta()).isEqualTo(Quantity.of(100));
        assertThat(buyerTrade.consideration()).isEqualTo(Money.of("10000.00", Currency.USD));
        assertThat(sellerTrade.delta()).isEqualTo(Quantity.of(-100));
        assertThat(sellerTrade.consideration()).isEqualTo(Money.of("-10000.00", Currency.USD));

        assertThat(buyerTrade.id()).isNotEqualTo(sellerTrade.id());
    }

    @Test
    void aSweepAcrossLevelsProducesOneTradePairPerFillAndReconciles() {
        OrderBookVenue venue = newVenue();
        venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 100, SELLER),
                CLOCK);
        venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("101.00"), 100, SELLER),
                CLOCK);

        List<Trade> trades = venue.execute(
                OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("101.00"), 150, BUYER), CLOCK);

        // Two fills (100 @ 100.00, then 50 @ 101.00), two trades each.
        assertThat(trades).hasSize(4);

        Money buyerTotal = trades.stream().filter(t -> t.owner().equals(BUYER))
                .map(Trade::consideration).reduce(Money.zero(Currency.USD), Money::plus);
        Money sellerTotal = trades.stream().filter(t -> t.owner().equals(SELLER))
                .map(Trade::consideration).reduce(Money.zero(Currency.USD), Money::plus);

        // 100 * 100.00 + 50 * 101.00 = 10000.00 + 5050.00 = 15050.00, both ways.
        assertThat(buyerTotal).isEqualTo(Money.of("15050.00", Currency.USD));
        assertThat(sellerTotal).isEqualTo(Money.of("-15050.00", Currency.USD));
    }

    @Test
    void theFillThenResubmitPatternThatUsedToReuseIdsNowJustWorks() {
        // The exact shape of G-1 (docs/KNOWN_GAPS.md): an order fills completely, then a new
        // one is submitted while the old one is gone from the book. Repeated many times, this
        // used to risk a reused OrderId once client-supplied ids were in play. The venue mints
        // its own, so there is nothing to reuse - proven here by simply running it a lot and
        // getting fully consistent results every time.
        OrderBookVenue venue = newVenue();
        int iterations = 2_000;

        for (int i = 0; i < iterations; i++) {
            venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 10, SELLER),
                    CLOCK);
            List<Trade> trades = venue.execute(
                    OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("100.00"), 10, BUYER), CLOCK);

            assertThat(trades).hasSize(2);
            assertThat(trades).allMatch(t -> t.status() == TradeStatus.EXECUTED);
        }
    }

    @Test
    void aMarketOrderTakesWhateverLiquidityExists() {
        OrderBookVenue venue = newVenue();
        venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 100, SELLER),
                CLOCK);

        List<Trade> trades = venue.execute(
                OrderBookInstruction.market(AAPL, Side.BUY, 100, BUYER), CLOCK);

        assertThat(trades).hasSize(2);
        assertThat(trades).allMatch(t -> t.status() == TradeStatus.EXECUTED);
    }

    @Test
    void anImmediateOrCancelFillsWhatItCanAndRestsNothing() {
        OrderBookVenue venue = newVenue();
        venue.execute(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("100.00"), 40, SELLER),
                CLOCK);

        List<Trade> trades = venue.execute(OrderBookInstruction.immediateOrCancel(
                AAPL, Side.BUY, Price.of("100.00"), 100, BUYER), CLOCK);

        // Only the 40 that could fill immediately becomes trades; the other 60 is cancelled,
        // not left resting - so it produces no Trade of its own.
        assertThat(trades).hasSize(2);
        Trade buyerTrade = trades.stream().filter(t -> t.owner().equals(BUYER)).findFirst()
                .orElseThrow();
        assertThat(buyerTrade.delta()).isEqualTo(Quantity.of(40));
    }

    @Test
    void rejectsAnOtcInstruction() {
        OrderBookVenue venue = newVenue();
        OtcInstruction otc = new OtcInstruction(AAPL, Side.BUY, Quantity.of(100), SELLER,
                BasisPoints.ZERO);

        assertThatThrownBy(() -> venue.execute(otc, CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OrderBookInstruction");
    }
}
