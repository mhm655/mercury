package com.mercury.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.id.TradeId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.event.AsynchronousEventBus;
import com.mercury.event.EventBus;
import com.mercury.event.SynchronousEventBus;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeExecuted;
import com.mercury.trade.TradeStatus;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LedgerKeeperTest {

    private static final PortfolioId BOOK = PortfolioId.of("BOOK");
    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final CounterpartyId OURS = CounterpartyId.of("CPTY-OURS");
    private static final CounterpartyId THEIRS = CounterpartyId.of("CPTY-THEIRS");
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 3, 2);
    private static final SimulationClock CLOCK = SimulationClock.fixedAt(TRADE_DATE);

    private static PortfolioLedger opening() {
        return PortfolioLedger.opening(BOOK, Currency.USD, CostBasisMethod.FIRST_IN_FIRST_OUT,
                CashAccount.of(Money.of("1000000", Currency.USD)));
    }

    private static Trade executedBuy(String id, CounterpartyId owner, long quantity) {
        Trade trade = Trade.newTrade(TradeId.of(id), AAPL, owner, Quantity.of(quantity),
                Money.of("180.00", Currency.USD).multipliedBy(quantity),
                TRADE_DATE, Optional.empty(), Optional.empty());
        return trade.transitionTo(TradeStatus.VALIDATED, "matched", CLOCK)
                .transitionTo(TradeStatus.BOOKED, "booked", CLOCK)
                .transitionTo(TradeStatus.EXECUTED, "filled", CLOCK);
    }

    @Test
    void booksOurOwnExecutions() {
        LedgerKeeper keeper = new LedgerKeeper(OURS, opening());

        keeper.accept(new TradeExecuted(executedBuy("TRD-1", OURS, 100)));

        assertThat(keeper.ledger().quantityOf(AAPL)).isEqualTo(Quantity.of(100));
        assertThat(keeper.owner()).isEqualTo(OURS);
    }

    @Test
    void ignoresSomeoneElsesExecutions() {
        // The order book publishes both sides of every fill, so the counterparty's own trade
        // arrives here too. Booking it would put the market maker's inventory in our book.
        LedgerKeeper keeper = new LedgerKeeper(OURS, opening());

        keeper.accept(new TradeExecuted(executedBuy("TRD-1", THEIRS, 100)));

        assertThat(keeper.ledger().quantityOf(AAPL)).isEqualTo(Quantity.ZERO);
        assertThat(keeper.ledger().cash().balance(Currency.USD))
                .isEqualTo(Money.of("1000000.00", Currency.USD));
    }

    @Test
    void accumulatesAcrossEvents() {
        LedgerKeeper keeper = new LedgerKeeper(OURS, opening());

        keeper.accept(new TradeExecuted(executedBuy("TRD-1", OURS, 100)));
        keeper.accept(new TradeExecuted(executedBuy("TRD-2", OURS, 50)));
        keeper.accept(new TradeExecuted(executedBuy("TRD-3", THEIRS, 999)));

        assertThat(keeper.ledger().quantityOf(AAPL)).isEqualTo(Quantity.of(150));
    }

    @Test
    void theSameExecutionTwiceIsRefusedRatherThanDoubleCounted() {
        // PortfolioLedger already refuses a repeated trade id; this keeper must not paper over
        // that, because a bus delivering an event twice is a defect worth seeing.
        LedgerKeeper keeper = new LedgerKeeper(OURS, opening());
        TradeExecuted event = new TradeExecuted(executedBuy("TRD-1", OURS, 100));
        keeper.accept(event);

        assertThatThrownBy(() -> keeper.accept(event)).isInstanceOf(IllegalStateException.class);
        assertThat(keeper.ledger().quantityOf(AAPL)).isEqualTo(Quantity.of(100));
    }

    @Test
    void keepsTheLedgerUpToDateThroughASynchronousBus() {
        EventBus bus = new SynchronousEventBus();
        LedgerKeeper keeper = new LedgerKeeper(OURS, opening());
        bus.subscribe(TradeExecuted.class, keeper);

        bus.publish(new TradeExecuted(executedBuy("TRD-1", OURS, 100)));

        assertThat(keeper.ledger().quantityOf(AAPL)).isEqualTo(Quantity.of(100));
    }

    @Test
    void keepsTheLedgerUpToDateThroughAnAsynchronousBus() {
        // The same subscriber, unchanged, on the bus that delivers from another thread - which
        // is the point of the keeper holding the mutable cell rather than the ledger doing so.
        LedgerKeeper keeper = new LedgerKeeper(OURS, opening());
        try (AsynchronousEventBus bus = new AsynchronousEventBus()) {
            bus.subscribe(TradeExecuted.class, keeper);
            for (int i = 1; i <= 100; i++) {
                bus.publish(new TradeExecuted(executedBuy("TRD-" + i, OURS, 10)));
            }
            bus.close();

            assertThat(keeper.ledger().quantityOf(AAPL)).isEqualTo(Quantity.of(1_000));
        }
    }
}
