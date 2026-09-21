package com.mercury.trade;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.TradeId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TradeSettlementBookTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final CounterpartyId OWNER = CounterpartyId.of("CPTY-OWNER");
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 3, 2);
    private static final LocalDate SETTLEMENT_DATE = LocalDate.of(2026, 3, 4);
    private static final SimulationClock CLOCK = SimulationClock.fixedAt(SETTLEMENT_DATE);

    private static Trade executedTrade(String id, Optional<LocalDate> settlementDate) {
        return Trade.newTrade(TradeId.of(id), AAPL, OWNER, Quantity.of(100),
                        Money.of("19550.00", Currency.USD), TRADE_DATE, settlementDate, Optional.empty())
                .transitionTo(TradeStatus.VALIDATED, "matched", SimulationClock.fixedAt(TRADE_DATE))
                .transitionTo(TradeStatus.BOOKED, "booked", SimulationClock.fixedAt(TRADE_DATE))
                .transitionTo(TradeStatus.EXECUTED, "matched in full", SimulationClock.fixedAt(TRADE_DATE));
    }

    @Test
    void aTradeDueTodaySettles() {
        TradeSettlementBook book = new TradeSettlementBook();
        Trade trade = executedTrade("TRD-1", Optional.of(SETTLEMENT_DATE));
        book.accept(new TradeExecuted(trade));

        List<Trade> settled = book.settleDueBy(SETTLEMENT_DATE, CLOCK);

        assertThat(settled).hasSize(1);
        assertThat(settled.get(0).id()).isEqualTo(trade.id());
        assertThat(settled.get(0).status()).isEqualTo(TradeStatus.SETTLED);
        assertThat(book.openTrades()).isEmpty();
    }

    @Test
    void aTradeDueInThePastSettles() {
        TradeSettlementBook book = new TradeSettlementBook();
        Trade trade = executedTrade("TRD-1", Optional.of(SETTLEMENT_DATE.minusDays(3)));
        book.accept(new TradeExecuted(trade));

        List<Trade> settled = book.settleDueBy(SETTLEMENT_DATE, CLOCK);

        assertThat(settled).extracting(Trade::id).containsExactly(trade.id());
    }

    @Test
    void aTradeDueInTheFutureIsLeftOpen() {
        TradeSettlementBook book = new TradeSettlementBook();
        Trade trade = executedTrade("TRD-1", Optional.of(SETTLEMENT_DATE.plusDays(1)));
        book.accept(new TradeExecuted(trade));

        List<Trade> settled = book.settleDueBy(SETTLEMENT_DATE, CLOCK);

        assertThat(settled).isEmpty();
        assertThat(book.openTrades()).extracting(Trade::id).containsExactly(trade.id());
    }

    @Test
    void aTradeWithNoSettlementDateIsNeverSettled() {
        TradeSettlementBook book = new TradeSettlementBook();
        Trade trade = executedTrade("TRD-1", Optional.empty());
        book.accept(new TradeExecuted(trade));

        List<Trade> settled = book.settleDueBy(SETTLEMENT_DATE.plusYears(1), CLOCK);

        assertThat(settled).isEmpty();
        assertThat(book.openTrades()).hasSize(1);
    }

    @Test
    void settlingTwiceDoesNotResettleTheSameTrade() {
        TradeSettlementBook book = new TradeSettlementBook();
        Trade trade = executedTrade("TRD-1", Optional.of(SETTLEMENT_DATE));
        book.accept(new TradeExecuted(trade));

        book.settleDueBy(SETTLEMENT_DATE, CLOCK);
        List<Trade> secondCall = book.settleDueBy(SETTLEMENT_DATE.plusDays(1), CLOCK);

        assertThat(secondCall).isEmpty();
    }

    @Test
    void settlingAppendsConfirmedThenSettledToTheHistory() {
        TradeSettlementBook book = new TradeSettlementBook();
        Trade trade = executedTrade("TRD-1", Optional.of(SETTLEMENT_DATE));
        book.accept(new TradeExecuted(trade));

        Trade settled = book.settleDueBy(SETTLEMENT_DATE, CLOCK).get(0);

        assertThat(settled.history()).hasSize(trade.history().size() + 2);
        assertThat(settled.history().get(settled.history().size() - 2).to())
                .isEqualTo(TradeStatus.CONFIRMED);
        assertThat(settled.history().get(settled.history().size() - 1).to())
                .isEqualTo(TradeStatus.SETTLED);
    }

    @Test
    void multipleOpenTradesSettleInTheOrderTheyWereRecorded() {
        TradeSettlementBook book = new TradeSettlementBook();
        Trade first = executedTrade("TRD-1", Optional.of(SETTLEMENT_DATE));
        Trade second = executedTrade("TRD-2", Optional.of(SETTLEMENT_DATE));
        book.accept(new TradeExecuted(first));
        book.accept(new TradeExecuted(second));

        List<Trade> settled = book.settleDueBy(SETTLEMENT_DATE, CLOCK);

        assertThat(settled).extracting(Trade::id)
                .containsExactly(first.id(), second.id());
    }

    @Test
    void aRepeatedDeliveryOfTheSameTradeIsRecordedOnlyOnce() {
        TradeSettlementBook book = new TradeSettlementBook();
        Trade trade = executedTrade("TRD-1", Optional.of(SETTLEMENT_DATE));

        book.accept(new TradeExecuted(trade));
        book.accept(new TradeExecuted(trade));

        assertThat(book.openTrades()).hasSize(1);
    }
}
