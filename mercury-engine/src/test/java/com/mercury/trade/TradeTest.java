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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TradeTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 3, 2);
    private static final SimulationClock CLOCK = SimulationClock.fixedAt(TRADE_DATE);

    private static Trade newBuy() {
        return Trade.newTrade(TradeId.of("TRD-1"), AAPL, Quantity.of(100),
                Money.of("19550.00", Currency.USD), TRADE_DATE, Optional.empty(), Optional.empty());
    }

    @Nested
    class Construction {

        @Test
        void startsNewWithEmptyHistory() {
            Trade trade = newBuy();

            assertThat(trade.status()).isEqualTo(TradeStatus.NEW);
            assertThat(trade.history()).isEmpty();
        }

        @Test
        void rejectsAZeroDelta() {
            assertThatThrownBy(() -> Trade.newTrade(TradeId.of("TRD-1"), AAPL, Quantity.ZERO,
                    Money.zero(Currency.USD), TRADE_DATE, Optional.empty(), Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-zero");
        }
    }

    @Nested
    class LegalTransitions {

        @Test
        void appendsOneEventPerTransition() {
            Trade trade = newBuy().transitionTo(TradeStatus.VALIDATED, "passed pre-trade checks", CLOCK);

            assertThat(trade.status()).isEqualTo(TradeStatus.VALIDATED);
            assertThat(trade.history()).hasSize(1);
            TradeLifecycleEvent event = trade.history().get(0);
            assertThat(event.from()).isEqualTo(TradeStatus.NEW);
            assertThat(event.to()).isEqualTo(TradeStatus.VALIDATED);
            assertThat(event.reason()).isEqualTo("passed pre-trade checks");
            assertThat(event.at()).isEqualTo(CLOCK.now());
        }

        @Test
        void historyAccumulatesInOrder() {
            Trade trade = newBuy()
                    .transitionTo(TradeStatus.VALIDATED, "passed pre-trade checks", CLOCK)
                    .transitionTo(TradeStatus.BOOKED, "booked to the ledger", CLOCK)
                    .transitionTo(TradeStatus.EXECUTED, "matched in full", CLOCK)
                    .transitionTo(TradeStatus.CONFIRMED, "confirmation sent", CLOCK)
                    .transitionTo(TradeStatus.SETTLED, "cash and securities exchanged", CLOCK);

            assertThat(trade.status()).isEqualTo(TradeStatus.SETTLED);
            assertThat(trade.history()).extracting(TradeLifecycleEvent::to).containsExactly(
                    TradeStatus.VALIDATED, TradeStatus.BOOKED, TradeStatus.EXECUTED,
                    TradeStatus.CONFIRMED, TradeStatus.SETTLED);
        }

        @Test
        void rejectedIsReachableFromNewAndValidated() {
            Trade fromNew = newBuy().transitionTo(TradeStatus.REJECTED, "failed validation", CLOCK);
            assertThat(fromNew.status()).isEqualTo(TradeStatus.REJECTED);

            Trade fromValidated = newBuy()
                    .transitionTo(TradeStatus.VALIDATED, "passed pre-trade checks", CLOCK)
                    .transitionTo(TradeStatus.REJECTED, "counterparty declined", CLOCK);
            assertThat(fromValidated.status()).isEqualTo(TradeStatus.REJECTED);
        }

        @Test
        void cancellationIsReachableAnyTimeBeforeSettlement() {
            Trade booked = newBuy()
                    .transitionTo(TradeStatus.VALIDATED, "passed pre-trade checks", CLOCK)
                    .transitionTo(TradeStatus.BOOKED, "booked to the ledger", CLOCK);

            assertThat(booked.transitionTo(TradeStatus.CANCELLED, "trader cancelled", CLOCK).status())
                    .isEqualTo(TradeStatus.CANCELLED);

            Trade executed = booked.transitionTo(TradeStatus.EXECUTED, "matched in full", CLOCK);
            assertThat(executed.transitionTo(TradeStatus.CANCELLED, "busted trade", CLOCK).status())
                    .isEqualTo(TradeStatus.CANCELLED);

            Trade confirmed = executed.transitionTo(TradeStatus.CONFIRMED, "confirmation sent", CLOCK);
            assertThat(confirmed.transitionTo(TradeStatus.CANCELLED, "late bust", CLOCK).status())
                    .isEqualTo(TradeStatus.CANCELLED);
        }

        @Test
        void originalTradeIsUntouchedByATransition() {
            Trade original = newBuy();
            original.transitionTo(TradeStatus.VALIDATED, "passed pre-trade checks", CLOCK);

            assertThat(original.status()).isEqualTo(TradeStatus.NEW);
            assertThat(original.history()).isEmpty();
        }
    }

    @Nested
    class IllegalTransitions {

        @Test
        void cannotSkipStraightToSettled() {
            Trade trade = newBuy();

            assertThatThrownBy(() -> trade.transitionTo(TradeStatus.SETTLED, "shortcut", CLOCK))
                    .isInstanceOf(Trade.InvalidTradeTransitionException.class)
                    .hasMessageContaining("NEW")
                    .hasMessageContaining("SETTLED");
        }

        @Test
        void nothingFollowsSettled() {
            Trade settled = newBuy()
                    .transitionTo(TradeStatus.VALIDATED, "passed pre-trade checks", CLOCK)
                    .transitionTo(TradeStatus.BOOKED, "booked to the ledger", CLOCK)
                    .transitionTo(TradeStatus.EXECUTED, "matched in full", CLOCK)
                    .transitionTo(TradeStatus.CONFIRMED, "confirmation sent", CLOCK)
                    .transitionTo(TradeStatus.SETTLED, "cash and securities exchanged", CLOCK);

            assertThatThrownBy(() -> settled.transitionTo(TradeStatus.CANCELLED, "too late", CLOCK))
                    .isInstanceOf(Trade.InvalidTradeTransitionException.class)
                    .hasMessageContaining("terminal");
        }

        @Test
        void nothingFollowsRejected() {
            Trade rejected = newBuy().transitionTo(TradeStatus.REJECTED, "failed validation", CLOCK);

            assertThatThrownBy(() -> rejected.transitionTo(TradeStatus.VALIDATED, "retry", CLOCK))
                    .isInstanceOf(Trade.InvalidTradeTransitionException.class);
        }

        @Test
        void nothingFollowsCancelled() {
            Trade cancelled = newBuy()
                    .transitionTo(TradeStatus.VALIDATED, "passed pre-trade checks", CLOCK)
                    .transitionTo(TradeStatus.BOOKED, "booked to the ledger", CLOCK)
                    .transitionTo(TradeStatus.CANCELLED, "trader cancelled", CLOCK);

            assertThatThrownBy(() -> cancelled.transitionTo(TradeStatus.EXECUTED, "retry", CLOCK))
                    .isInstanceOf(Trade.InvalidTradeTransitionException.class);
        }
    }

    @Nested
    class TransitionTable {

        @Test
        void terminalStatusesAllowNothing() {
            assertThat(TradeStatus.SETTLED.allowedTransitions()).isEmpty();
            assertThat(TradeStatus.REJECTED.allowedTransitions()).isEmpty();
            assertThat(TradeStatus.CANCELLED.allowedTransitions()).isEmpty();
            assertThat(TradeStatus.SETTLED.isTerminal()).isTrue();
            assertThat(TradeStatus.NEW.isTerminal()).isFalse();
        }

        @Test
        void newAllowsValidatedOrRejectedOnly() {
            assertThat(TradeStatus.NEW.allowedTransitions())
                    .containsExactlyInAnyOrder(TradeStatus.VALIDATED, TradeStatus.REJECTED);
        }

        @Test
        void everyNonTerminalStatusHasACancellationOrRejectionPath() {
            for (TradeStatus status : List.of(TradeStatus.NEW, TradeStatus.VALIDATED,
                    TradeStatus.BOOKED, TradeStatus.EXECUTED, TradeStatus.CONFIRMED)) {
                assertThat(status.allowedTransitions())
                        .as("status %s must have somewhere to go", status)
                        .isNotEmpty();
            }
        }
    }
}
