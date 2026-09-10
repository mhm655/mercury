package com.mercury.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.id.TradeId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link PortfolioLedger#book} is the bridge between a trade's lifecycle (M8) and the ledger
 * that has existed since M7 - this asserts it produces exactly the same ledger state as the
 * equivalent direct {@link PortfolioLedger#trade} call, so booking a {@code Trade} is
 * genuinely a delegation and not a second implementation of the same arithmetic.
 */
class PortfolioLedgerBookingTest {

    private static final PortfolioId BOOK = PortfolioId.of("BOOK");
    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final CounterpartyId OWNER = CounterpartyId.of("CPTY-OWNER");
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 3, 2);
    private static final SimulationClock CLOCK = SimulationClock.fixedAt(TRADE_DATE);

    private static PortfolioLedger opening() {
        return PortfolioLedger.opening(BOOK, Currency.USD,
                CostBasisMethod.FIRST_IN_FIRST_OUT, CashAccount.of(Money.of("1000000", Currency.USD)));
    }

    private static Trade executedBuy() {
        Trade trade = Trade.newTrade(TradeId.of("TRD-1"), AAPL, OWNER, Quantity.of(1_000),
                Money.of("180000.00", Currency.USD), TRADE_DATE, Optional.empty(), Optional.empty());
        return trade.transitionTo(TradeStatus.VALIDATED, "matched", CLOCK)
                .transitionTo(TradeStatus.BOOKED, "booked", CLOCK)
                .transitionTo(TradeStatus.EXECUTED, "filled", CLOCK);
    }

    @Test
    void bookingAnExecutedTradeMatchesTheEquivalentDirectCall() {
        PortfolioLedger viaBooking = opening().book(executedBuy());
        PortfolioLedger viaDirectCall = opening()
                .buy(AAPL, Quantity.of(1_000), Price.of("180"), Currency.USD, TRADE_DATE);

        assertThat(viaBooking.cash().balance(Currency.USD))
                .isEqualTo(viaDirectCall.cash().balance(Currency.USD));
        assertThat(viaBooking.costBasisOf(AAPL)).isEqualTo(viaDirectCall.costBasisOf(AAPL));
        assertThat(viaBooking.quantityOf(AAPL)).isEqualTo(viaDirectCall.quantityOf(AAPL));
    }

    @Test
    void bookingAConfirmedOrSettledTradeIsAlsoAccepted() {
        Trade confirmed = executedBuy().transitionTo(TradeStatus.CONFIRMED, "confirmed", CLOCK);
        Trade settled = confirmed.transitionTo(TradeStatus.SETTLED, "settled", CLOCK);

        assertThat(opening().book(confirmed).quantityOf(AAPL)).isEqualTo(Quantity.of(1_000));
        assertThat(opening().book(settled).quantityOf(AAPL)).isEqualTo(Quantity.of(1_000));
    }

    @Test
    void bookingANewTradeThrows() {
        Trade fresh = Trade.newTrade(TradeId.of("TRD-1"), AAPL, OWNER, Quantity.of(1_000),
                Money.of("180000.00", Currency.USD), TRADE_DATE, Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> opening().book(fresh))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEW");
    }

    @Test
    void bookingABookedButNotYetExecutedTradeThrows() {
        Trade booked = Trade.newTrade(TradeId.of("TRD-1"), AAPL, OWNER, Quantity.of(1_000),
                        Money.of("180000.00", Currency.USD), TRADE_DATE, Optional.empty(), Optional.empty())
                .transitionTo(TradeStatus.VALIDATED, "matched", CLOCK)
                .transitionTo(TradeStatus.BOOKED, "booked", CLOCK);

        assertThatThrownBy(() -> opening().book(booked))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOOKED");
    }

    @Test
    void bookAllFoldsEveryTradeInOrder() {
        Trade buy = executedBuy();
        Trade sell = Trade.newTrade(TradeId.of("TRD-2"), AAPL, OWNER, Quantity.of(-200),
                        Money.of("-38400.00", Currency.USD), TRADE_DATE.plusDays(1), Optional.empty(),
                        Optional.empty())
                .transitionTo(TradeStatus.VALIDATED, "matched", CLOCK)
                .transitionTo(TradeStatus.BOOKED, "booked", CLOCK)
                .transitionTo(TradeStatus.EXECUTED, "filled", CLOCK);

        PortfolioLedger ledger = opening().bookAll(List.of(buy, sell));

        assertThat(ledger.quantityOf(AAPL)).isEqualTo(Quantity.of(800));
    }
}
