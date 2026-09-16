package com.mercury.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.TradeId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TradeExecutedTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final CounterpartyId OWNER = CounterpartyId.of("CPTY-OWNER");
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 3, 2);
    private static final SimulationClock CLOCK = SimulationClock.fixedAt(TRADE_DATE);

    private static Trade newTrade() {
        return Trade.newTrade(TradeId.of("TRD-1"), AAPL, OWNER, Quantity.of(100),
                Money.of("18000.00", Currency.USD), TRADE_DATE, Optional.empty(), Optional.empty());
    }

    private static Trade executed() {
        return newTrade().transitionTo(TradeStatus.VALIDATED, "matched", CLOCK)
                .transitionTo(TradeStatus.BOOKED, "booked", CLOCK)
                .transitionTo(TradeStatus.EXECUTED, "filled", CLOCK);
    }

    @Test
    void announcesAnExecutedTrade() {
        assertThat(new TradeExecuted(executed()).trade().status()).isEqualTo(TradeStatus.EXECUTED);
    }

    @Test
    void announcesConfirmedAndSettledTradesToo() {
        Trade confirmed = executed().transitionTo(TradeStatus.CONFIRMED, "confirmed", CLOCK);
        Trade settled = confirmed.transitionTo(TradeStatus.SETTLED, "settled", CLOCK);

        assertThat(new TradeExecuted(confirmed).trade()).isEqualTo(confirmed);
        assertThat(new TradeExecuted(settled).trade()).isEqualTo(settled);
    }

    @Test
    void refusesToAnnounceATradeThatNeverExecuted() {
        // A subscriber books what it is told about. Announcing a NEW trade as an execution is
        // how a phantom position reaches a ledger - the same reason PortfolioLedger.book
        // refuses one, checked here so the announcement never gets that far.
        assertThatThrownBy(() -> new TradeExecuted(newTrade()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NEW");
    }

    @Test
    void refusesNull() {
        assertThatThrownBy(() -> new TradeExecuted(null)).isInstanceOf(NullPointerException.class);
    }
}
