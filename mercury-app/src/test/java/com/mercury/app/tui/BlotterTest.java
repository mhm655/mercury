package com.mercury.app.tui;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.TradeId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeExecuted;
import com.mercury.trade.TradeStatus;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BlotterTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final CounterpartyId MERCURY = CounterpartyId.of("CPTY-MERCURY");
    private static final CounterpartyId SOMEONE_ELSE = CounterpartyId.of("CPTY-OTHER");
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 3, 2);
    private static final SimulationClock CLOCK = SimulationClock.fixedAt(TRADE_DATE);

    @Test
    void recordsATradeOwnedByOurBook() {
        Blotter blotter = new Blotter(MERCURY);

        blotter.accept(new TradeExecuted(executedTrade(MERCURY)));

        assertThat(blotter.rows()).hasSize(1);
        assertThat(blotter.rows().get(0)).contains("TRD-1", "AAPL", "order book");
    }

    @Test
    void ignoresATradeOwnedBySomeoneElse() {
        Blotter blotter = new Blotter(MERCURY);

        blotter.accept(new TradeExecuted(executedTrade(SOMEONE_ELSE)));

        assertThat(blotter.rows()).isEmpty();
    }

    @Test
    void recordsARejectionThatNeverBecameATrade() {
        Blotter blotter = new Blotter(MERCURY);

        blotter.recordRejection("counterparty exposure exceeds maximum");

        assertThat(blotter.rows()).containsExactly(
                "REJECTED     counterparty exposure exceeds maximum");
    }

    @Test
    void rowsAppearInTheOrderTheyWereRecorded() {
        Blotter blotter = new Blotter(MERCURY);

        blotter.accept(new TradeExecuted(executedTrade(MERCURY)));
        blotter.recordRejection("too much");

        assertThat(blotter.rows()).hasSize(2);
        assertThat(blotter.rows().get(1)).isEqualTo("REJECTED     too much");
    }

    private static Trade executedTrade(CounterpartyId owner) {
        Trade trade = Trade.newTrade(TradeId.of("TRD-1"), AAPL, owner, Quantity.of(100),
                Money.of("18000.00", Currency.USD), TRADE_DATE, Optional.empty(), Optional.empty());
        return trade.transitionTo(TradeStatus.VALIDATED, "matched", CLOCK)
                .transitionTo(TradeStatus.BOOKED, "booked", CLOCK)
                .transitionTo(TradeStatus.EXECUTED, "filled", CLOCK);
    }
}
