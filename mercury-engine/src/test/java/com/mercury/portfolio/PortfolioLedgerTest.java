package com.mercury.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.money.Money;
import com.mercury.core.money.Price;
import com.mercury.core.money.Quantity;
import com.mercury.instrument.Stock;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.model.SpotPriceModel;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A book as a history rather than a snapshot: lots, cash, and the profit already taken.
 *
 * <p>The sharpest test here is that a trade at the market leaves the book no richer. Buying
 * costs cash and gains an asset of the same value, so cash plus cost basis is invariant - and a
 * ledger that recorded the position without the cash would show a book growing out of nothing,
 * which is the failure this type exists to prevent.
 */
class PortfolioLedgerTest {

    private static final PortfolioId BOOK = PortfolioId.of("BOOK");
    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final InstrumentId SAP = InstrumentId.of("SAP");
    private static final LocalDate JANUARY = LocalDate.of(2024, 1, 15);
    private static final LocalDate JUNE = LocalDate.of(2024, 6, 28);

    private static PortfolioLedger opening() {
        return PortfolioLedger.opening(BOOK, Currency.USD,
                CostBasisMethod.FIRST_IN_FIRST_OUT,
                CashAccount.of(Money.of("1000000", Currency.USD)));
    }

    @Nested
    @DisplayName("cash and positions move together")
    class CashAndPositions {

        @Test
        @DisplayName("buying costs cash and gains a position of the same value")
        void tradeAtMarketLeavesTheBookLevel() {
            PortfolioLedger after = opening()
                    .buy(AAPL, Quantity.of(1_000), Price.of("180"), Currency.USD, JANUARY);

            assertThat(after.cash().balance(Currency.USD))
                    .isEqualTo(Money.of("820000.00", Currency.USD));
            assertThat(after.costBasisOf(AAPL)).isEqualTo(Money.of("180000.00", Currency.USD));
            assertThat(after.cash().balance(Currency.USD).plus(after.costBasisOf(AAPL)))
                    .isEqualTo(Money.of("1000000.00", Currency.USD));
        }

        @Test
        @DisplayName("selling short brings cash in and records a negative cost")
        void shortSaleBringsCashIn() {
            PortfolioLedger after = opening()
                    .sell(AAPL, Quantity.of(100), Price.of("200"), Currency.USD, JANUARY);

            assertThat(after.quantityOf(AAPL)).isEqualTo(Quantity.of(-100));
            assertThat(after.cash().balance(Currency.USD))
                    .isEqualTo(Money.of("1020000.00", Currency.USD));
            assertThat(after.costBasisOf(AAPL)).isEqualTo(Money.of("-20000.00", Currency.USD));
        }

        @Test
        @DisplayName("a derivative entered at the market costs nothing and moves no cash")
        void zeroCostDerivative() {
            // A swap or an FX forward has no price to quote - Price rejects zero, and rightly.
            // Its cost basis is nothing, so its whole value is unrealised from day one.
            PortfolioLedger after = opening()
                    .trade(InstrumentId.of("IRS-5Y"), Quantity.of(1),
                            Money.zero(Currency.USD), JANUARY);

            assertThat(after.cash().balance(Currency.USD))
                    .isEqualTo(Money.of("1000000.00", Currency.USD));
            assertThat(after.costBasisOf(InstrumentId.of("IRS-5Y")))
                    .isEqualTo(Money.zero(Currency.USD));
        }

        @Test
        @DisplayName("a cash balance may go negative, because borrowing is a real position")
        void overdraftIsAllowed() {
            PortfolioLedger after = opening()
                    .buy(AAPL, Quantity.of(10_000), Price.of("200"), Currency.USD, JANUARY);

            assertThat(after.cash().balance(Currency.USD).isNegative()).isTrue();
        }

        @Test
        @DisplayName("cash is held per currency and never netted across them")
        void cashIsPerCurrency() {
            PortfolioLedger after = opening()
                    .buy(SAP, Quantity.of(100), Price.of("150"), Currency.EUR, JANUARY);

            assertThat(after.cash().balance(Currency.USD))
                    .isEqualTo(Money.of("1000000.00", Currency.USD));
            assertThat(after.cash().balance(Currency.EUR))
                    .isEqualTo(Money.of("-15000.00", Currency.EUR));
        }
    }

    @Nested
    @DisplayName("realising profit")
    class Realising {

        @Test
        @DisplayName("closing a long at a higher price realises the difference")
        void longProfit() {
            PortfolioLedger after = opening()
                    .buy(AAPL, Quantity.of(100), Price.of("180"), Currency.USD, JANUARY)
                    .sell(AAPL, Quantity.of(100), Price.of("200"), Currency.USD, JUNE);

            assertThat(after.realisedPnl(Currency.USD))
                    .isEqualTo(Money.of("2000.00", Currency.USD));
            assertThat(after.quantityOf(AAPL)).isEqualTo(Quantity.ZERO);
            assertThat(after.instruments()).isEmpty();
        }

        @Test
        @DisplayName("closing a short at a lower price realises the difference too")
        void shortProfit() {
            // The same formula: realised = cash from the closing trade minus the cost basis
            // consumed. Sold at 200, bought back at 180, so cash out 18,000 against a cost of
            // minus 20,000 - a profit of 2,000 from the identical line of arithmetic.
            PortfolioLedger after = opening()
                    .sell(AAPL, Quantity.of(100), Price.of("200"), Currency.USD, JANUARY)
                    .buy(AAPL, Quantity.of(100), Price.of("180"), Currency.USD, JUNE);

            assertThat(after.realisedPnl(Currency.USD))
                    .isEqualTo(Money.of("2000.00", Currency.USD));
        }

        @Test
        @DisplayName("the cost basis method changes the realised figure, as it must")
        void methodChangesTheAnswer() {
            assertThat(realisedUnder(CostBasisMethod.FIRST_IN_FIRST_OUT))
                    .isEqualTo(Money.of("5000.00", Currency.USD));
            assertThat(realisedUnder(CostBasisMethod.LAST_IN_FIRST_OUT))
                    .isEqualTo(Money.of("1000.00", Currency.USD));
            assertThat(realisedUnder(CostBasisMethod.AVERAGE_COST))
                    .isEqualTo(Money.of("3000.00", Currency.USD));
        }

        @Test
        @DisplayName("realised profit is recorded in the currency it was made in")
        void realisedPerCurrency() {
            PortfolioLedger after = opening()
                    .buy(SAP, Quantity.of(100), Price.of("150"), Currency.EUR, JANUARY)
                    .sell(SAP, Quantity.of(100), Price.of("160"), Currency.EUR, JUNE);

            assertThat(after.realisedPnl(Currency.EUR))
                    .isEqualTo(Money.of("1000.00", Currency.EUR));
            assertThat(after.realisedPnl(Currency.USD)).isEqualTo(Money.zero(Currency.USD));
            assertThat(after.realisedCurrencies()).containsExactly(Currency.EUR);
        }

        @Test
        @DisplayName("a partial sale realises on the part sold and leaves the rest open")
        void partialSale() {
            PortfolioLedger after = opening()
                    .buy(AAPL, Quantity.of(100), Price.of("180"), Currency.USD, JANUARY)
                    .sell(AAPL, Quantity.of(40), Price.of("200"), Currency.USD, JUNE);

            assertThat(after.realisedPnl(Currency.USD))
                    .isEqualTo(Money.of("800.00", Currency.USD));
            assertThat(after.quantityOf(AAPL)).isEqualTo(Quantity.of(60));
            assertThat(after.costBasisOf(AAPL)).isEqualTo(Money.of("10800.00", Currency.USD));
        }

        private static Money realisedUnder(CostBasisMethod method) {
            return PortfolioLedger.opening(BOOK, Currency.USD, method,
                            CashAccount.of(Money.of("1000000", Currency.USD)))
                    .buy(AAPL, Quantity.of(100), Price.of("50"), Currency.USD, JANUARY)
                    .buy(AAPL, Quantity.of(100), Price.of("90"), Currency.USD, JUNE)
                    .sell(AAPL, Quantity.of(100), Price.of("100"), Currency.USD, JUNE)
                    .realisedPnl(Currency.USD);
        }
    }

    @Nested
    @DisplayName("what it refuses")
    class Refusals {

        @Test
        @DisplayName("a trade that would carry a position through zero")
        void crossingZero() {
            PortfolioLedger held = opening()
                    .buy(AAPL, Quantity.of(10), Price.of("180"), Currency.USD, JANUARY);

            assertThatThrownBy(() -> held.sell(AAPL, Quantity.of(15), Price.of("200"),
                    Currency.USD, JUNE))
                    .isInstanceOf(PositionLots.PositionCrossesZeroException.class)
                    .hasMessageContaining("two trades, not one");
        }

        @Test
        @DisplayName("a cost basis for something the book does not hold")
        void costBasisOfNothing() {
            assertThatThrownBy(() -> opening().costBasisOf(AAPL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("invent a currency");
        }
    }

    @Nested
    @DisplayName("projecting to a portfolio")
    class Projection {

        @Test
        @DisplayName("the history reduces to the positions it implies")
        void toPortfolio() {
            Portfolio portfolio = opening()
                    .buy(AAPL, Quantity.of(100), Price.of("180"), Currency.USD, JANUARY)
                    .buy(AAPL, Quantity.of(50), Price.of("190"), Currency.USD, JUNE)
                    .sell(AAPL, Quantity.of(30), Price.of("200"), Currency.USD, JUNE)
                    .toPortfolio();

            assertThat(portfolio.size()).isEqualTo(1);
            assertThat(portfolio.positions().iterator().next().quantity())
                    .isEqualTo(Quantity.of(120));
        }

        @Test
        @DisplayName("a closed position does not appear at all")
        void closedPositionsDisappear() {
            Portfolio portfolio = opening()
                    .buy(AAPL, Quantity.of(100), Price.of("180"), Currency.USD, JANUARY)
                    .sell(AAPL, Quantity.of(100), Price.of("200"), Currency.USD, JUNE)
                    .toPortfolio();

            assertThat(portfolio.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("profit and loss")
    class Pnl {

        private static MarketDataSnapshot market() {
            return MarketDataSnapshot.builder(JUNE)
                    .spot(AAPL, 200.0)
                    .spot(SAP, 160.0)
                    .fxRate(CurrencyPair.parse("EUR/USD"), 1.10)
                    .build();
        }

        private static PortfolioValuationService valuation() {
            return new PortfolioValuationService(
                    PricingService.builder().register(new SpotPriceModel()).build(),
                    InstrumentCatalog.of(Stock.of("AAPL", Currency.USD),
                            Stock.of("SAP", Currency.EUR)));
        }

        @Test
        @DisplayName("unrealised is market value minus what the position cost")
        void unrealised() {
            PortfolioLedger ledger = opening()
                    .buy(AAPL, Quantity.of(100), Price.of("180"), Currency.USD, JANUARY);

            PnlStatement pnl = PnlStatement.of(ledger,
                    valuation().value(ledger.toPortfolio(), market(), JUNE), market());

            assertThat(pnl.unrealised()).isEqualTo(Money.of("2000.00", Currency.USD));
            assertThat(pnl.realised()).isEqualTo(Money.zero(Currency.USD));
            assertThat(pnl.total()).isEqualTo(Money.of("2000.00", Currency.USD));
        }

        @Test
        @DisplayName("realised and unrealised are reported separately, never as one figure")
        void bothTogether() {
            // The point of the split. This book has taken 2,000 of profit and is sitting on
            // 1,000 more; a single number would say 3,000 and hide that one of them is a fact
            // and the other an opinion.
            PortfolioLedger ledger = opening()
                    .buy(AAPL, Quantity.of(200), Price.of("180"), Currency.USD, JANUARY)
                    .sell(AAPL, Quantity.of(100), Price.of("200"), Currency.USD, JUNE);

            PnlStatement pnl = PnlStatement.of(ledger,
                    valuation().value(ledger.toPortfolio(), market(), JUNE), market());

            assertThat(pnl.realised()).isEqualTo(Money.of("2000.00", Currency.USD));
            assertThat(pnl.unrealised()).isEqualTo(Money.of("2000.00", Currency.USD));
        }

        @Test
        @DisplayName("a foreign position's unrealised P&L includes the currency move")
        void foreignUnrealisedIncludesFx() {
            // Bought 100 SAP at EUR 150, now EUR 160, with EUR/USD at 1.10. The position cost
            // EUR 15,000 - USD 16,500 at today's rate - and is worth USD 17,600, so the gain
            // reported in dollars is 1,100: the euro gain of 1,000 converted. That is what the
            // holder actually made, which is why the conversion uses today's rate on both
            // sides rather than the rate on the day of purchase.
            PortfolioLedger ledger = opening()
                    .buy(SAP, Quantity.of(100), Price.of("150"), Currency.EUR, JANUARY);

            PnlStatement pnl = PnlStatement.of(ledger,
                    valuation().value(ledger.toPortfolio(), market(), JUNE), market());

            PnlStatement.Line line = pnl.lines().get(0);
            assertThat(line.isForeign()).isTrue();
            assertThat(line.costBasisLocal()).isEqualTo(Money.of("15000.00", Currency.EUR));
            assertThat(line.costBasisReporting()).isEqualTo(Money.of("16500.00", Currency.USD));
            assertThat(line.marketValue()).isEqualTo(Money.of("17600.00", Currency.USD));
            assertThat(pnl.unrealised()).isEqualTo(Money.of("1100.00", Currency.USD));
        }

        @Test
        @DisplayName("a valuation of a different book is refused")
        void mismatchedBooks() {
            PortfolioLedger ledger = opening()
                    .buy(AAPL, Quantity.of(100), Price.of("180"), Currency.USD, JANUARY);
            PortfolioValuation otherBook = valuation().value(
                    Portfolio.builder(BOOK, Currency.USD).position(SAP, 10).build(),
                    market(), JUNE);

            assertThatThrownBy(() -> PnlStatement.of(ledger, otherBook, market()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("two different books");
        }
    }
}
