package com.mercury.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyMismatchException;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.money.Money;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class FxForwardTest {

    private static final CurrencyPair EURUSD = CurrencyPair.parse("EUR/USD");
    private static final LocalDate TRADE_DATE = LocalDate.of(2024, 6, 15);
    private static final LocalDate SETTLEMENT = LocalDate.of(2024, 9, 15);

    /** Buy 1,000,000 EUR at 1.08, so pay 1,080,000 USD. */
    private static FxForward buyEuro() {
        return FxForward.buy("FWD-EURUSD-3M", EURUSD, "1000000", "1.08", SETTLEMENT);
    }

    @Nested
    @DisplayName("classification")
    class Classification {

        @Test
        @DisplayName("is an OTC FX instrument, not exchange traded")
        void classification() {
            FxForward forward = buyEuro();

            assertThat(forward.assetClass()).isEqualTo(AssetClass.FX);
            assertThat(forward.tradability()).isEqualTo(TradabilityProfile.OVER_THE_COUNTER);
            assertThat(forward.tradability().hasCounterpartyRisk()).isTrue();
            assertThat(forward.tradability().isExchangeTraded()).isFalse();
        }

        @Test
        @DisplayName("reports the quote currency as its own")
        void currencyIsQuoteCurrency() {
            // A EUR/USD forward is a dollar-denominated position on the euro.
            assertThat(buyEuro().currency()).isEqualTo(Currency.USD);
        }

        @Test
        @DisplayName("matures on its settlement date")
        void maturityIsSettlement() {
            assertThat(buyEuro().maturityDate()).isEqualTo(SETTLEMENT);
        }
    }

    @Nested
    @DisplayName("the two legs")
    class Legs {

        @Test
        @DisplayName("buying 1,000,000 EUR at 1.08 pays 1,080,000 USD")
        void buyLegs() {
            FxForward forward = buyEuro();

            assertThat(forward.baseAmount()).isEqualTo(Money.of("1000000.00", Currency.EUR));
            assertThat(forward.quoteAmount()).isEqualTo(Money.of("-1080000.00", Currency.USD));
            assertThat(forward.isBuyingBase()).isTrue();
        }

        @Test
        @DisplayName("selling reverses both legs")
        void sellLegs() {
            FxForward sold = FxForward.sell("FWD-SELL", EURUSD, "1000000", "1.08", SETTLEMENT);

            assertThat(sold.baseAmount()).isEqualTo(Money.of("-1000000.00", Currency.EUR));
            assertThat(sold.quoteAmount()).isEqualTo(Money.of("1080000.00", Currency.USD));
            assertThat(sold.isBuyingBase()).isFalse();
        }

        @Test
        @DisplayName("the legs always face in opposite directions")
        void legsAreOpposite() {
            for (FxForward forward : List.of(
                    buyEuro(),
                    FxForward.sell("S", EURUSD, "500000", "1.08", SETTLEMENT))) {

                assertThat(forward.baseAmount().isPositive())
                        .isNotEqualTo(forward.quoteAmount().isPositive());
            }
        }

        @Test
        @DisplayName("the rate is quote units per one base unit, per market convention")
        void rateDirection() {
            // JPY has no minor units, which also exercises Money's per-currency scaling.
            FxForward usdJpy = FxForward.buy(
                    "FWD-USDJPY", CurrencyPair.parse("USD/JPY"), "1000000", "157.25", SETTLEMENT);

            assertThat(usdJpy.baseAmount()).isEqualTo(Money.of("1000000.00", Currency.USD));
            assertThat(usdJpy.quoteAmount()).isEqualTo(Money.of("-157250000", Currency.JPY));
            assertThat(usdJpy.quoteAmount().amount().scale()).isZero();
        }
    }

    @Nested
    @DisplayName("cashflows")
    class Cashflows {

        @Test
        @DisplayName("both legs settle on the settlement date")
        void twoCashflowsOnSettlement() {
            List<Cashflow> cashflows = buyEuro().cashflows(TRADE_DATE);

            assertThat(cashflows).hasSize(2);
            assertThat(cashflows).allSatisfy(cf ->
                    assertThat(cf.paymentDate()).isEqualTo(SETTLEMENT));
        }

        @Test
        @DisplayName("the two cashflows are in different currencies and cannot be summed")
        void cashflowsAreInDifferentCurrencies() {
            // This is why Cashflow carries Money rather than a bare number: a
            // single-currency cashflow type could not represent this instrument at all.
            List<Cashflow> cashflows = buyEuro().cashflows(TRADE_DATE);
            Money base = cashflows.get(0).amount();
            Money quote = cashflows.get(1).amount();

            assertThat(base.currency()).isNotEqualTo(quote.currency());
            assertThatThrownBy(() -> base.plus(quote))
                    .isInstanceOf(CurrencyMismatchException.class);
        }

        @Test
        @DisplayName("nothing remains once settled")
        void noCashflowsAfterSettlement() {
            FxForward forward = buyEuro();

            assertThat(forward.cashflows(SETTLEMENT)).isEmpty();
            assertThat(forward.cashflows(SETTLEMENT.plusDays(1))).isEmpty();
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects a zero notional, which has neither size nor side")
        void rejectsZeroNotional() {
            assertThatThrownBy(() -> FxForward.buy("X", EURUSD, "0", "1.08", SETTLEMENT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be zero");
        }

        @Test
        @DisplayName("rejects a non-positive forward rate")
        void rejectsNonPositiveRate() {
            assertThatThrownBy(() -> FxForward.buy("X", EURUSD, "1000", "0", SETTLEMENT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");

            assertThatThrownBy(() -> FxForward.buy("X", EURUSD, "1000", "-1.08", SETTLEMENT))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
