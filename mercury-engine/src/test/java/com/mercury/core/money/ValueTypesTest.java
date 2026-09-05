package com.mercury.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Covers the smaller value types: {@link Price}, {@link Quantity}, {@link BasisPoints}, {@link CurrencyPair}. */
class ValueTypesTest {

    @Nested
    @DisplayName("Price")
    class Prices {

        @Test
        @DisplayName("rejects zero and negative prices at construction")
        void rejectsNonPositive() {
            assertThatThrownBy(() -> Price.of("0"))
                    .isInstanceOf(Price.NonPositivePriceException.class)
                    .hasMessageContaining("strictly positive");

            assertThatThrownBy(() -> Price.of("-1.50"))
                    .isInstanceOf(Price.NonPositivePriceException.class);
        }

        @Test
        @DisplayName("normalises scale so equality and map keying behave")
        void normalisesScale() {
            assertThat(Price.of("10.5")).isEqualTo(Price.of("10.50"));
            assertThat(Price.of("10.5")).hasSameHashCodeAs(Price.of("10.500"));
        }

        @Test
        @DisplayName("orders numerically")
        void ordersNumerically() {
            assertThat(Price.of("10.01").isGreaterThan(Price.of("10.00"))).isTrue();
            assertThat(Price.of("9.99").isLessThan(Price.of("10.00"))).isTrue();
            assertThat(Price.of("10.00").compareTo(Price.of("10.00"))).isZero();
        }

        @Test
        @DisplayName("gains a currency only when explicitly asked")
        void toMoneyAttachesCurrency() {
            assertThat(Price.of("10.50").toMoney(Currency.USD))
                    .isEqualTo(Money.of("10.50", Currency.USD));
        }

        @Test
        @DisplayName("prints without trailing zeros")
        void printsCleanly() {
            assertThat(Price.of("10.50")).hasToString("10.5");
            assertThat(Price.of("100")).hasToString("100");
        }
    }

    @Nested
    @DisplayName("Quantity")
    class Quantities {

        @Test
        @DisplayName("is signed, so a short position is an ordinary negative value")
        void isSigned() {
            Quantity shortPosition = Quantity.of(-100);

            assertThat(shortPosition.isShort()).isTrue();
            assertThat(shortPosition.isLong()).isFalse();
            assertThat(shortPosition.signum()).isEqualTo(-1);
        }

        @Test
        @DisplayName("position arithmetic needs no branching on direction")
        void arithmeticIsDirectionAgnostic() {
            // Selling 150 from a long 100 flips to short 50 - the same addition either way.
            assertThat(Quantity.of(100).plus(Quantity.of(-150))).isEqualTo(Quantity.of(-50));
            // Buying 150 into a short 100 flips to long 50.
            assertThat(Quantity.of(-100).plus(Quantity.of(150))).isEqualTo(Quantity.of(50));
        }

        @Test
        @DisplayName("supports fractional notionals")
        void supportsFractionalNotional() {
            assertThat(Quantity.of("1000000.50").plus(Quantity.of("0.25")))
                    .isEqualTo(Quantity.of("1000000.75"));
        }

        @Test
        @DisplayName("min gives the fill size when two orders meet")
        void minIsTheFillSize() {
            assertThat(Quantity.of(100).min(Quantity.of(40))).isEqualTo(Quantity.of(40));
            assertThat(Quantity.of(40).min(Quantity.of(100))).isEqualTo(Quantity.of(40));
        }

        @Test
        @DisplayName("times computes consideration, rounding once")
        void timesComputesConsideration() {
            assertThat(Quantity.of(100).times(Money.of("10.01", Currency.USD)))
                    .isEqualTo(Money.of("1001.00", Currency.USD));
        }

        @Test
        @DisplayName("normalises scale so equality behaves")
        void normalisesScale() {
            assertThat(Quantity.of("100")).isEqualTo(Quantity.of("100.00"));
            assertThat(Quantity.ZERO.isZero()).isTrue();
        }
    }

    @Nested
    @DisplayName("BasisPoints")
    class Bps {

        private static final double TOLERANCE = 1e-12;

        @Test
        @DisplayName("converts between bp, percent and decimal")
        void converts() {
            BasisPoints bp150 = BasisPoints.of(150);

            assertThat(bp150.asDecimal()).isCloseTo(0.015, within(TOLERANCE));
            assertThat(bp150.asPercent()).isCloseTo(1.5, within(TOLERANCE));
        }

        @Test
        @DisplayName("the alternative factories agree with each other")
        void factoriesAgree() {
            assertThat(BasisPoints.ofPercent(1.5).value()).isCloseTo(150.0, within(TOLERANCE));
            assertThat(BasisPoints.ofDecimal(0.015).value()).isCloseTo(150.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("one basis point is the DV01 bump")
        void oneIsTheDv01Bump() {
            assertThat(BasisPoints.ONE.asDecimal()).isCloseTo(0.0001, within(TOLERANCE));
        }

        @Test
        @DisplayName("is signed, because a shock can be downward")
        void isSigned() {
            assertThat(BasisPoints.of(150).negated().value()).isCloseTo(-150.0, within(TOLERANCE));
            assertThat(BasisPoints.of(150).minus(BasisPoints.of(200)).value())
                    .isCloseTo(-50.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("rejects non-finite values")
        void rejectsNonFinite() {
            assertThatThrownBy(() -> BasisPoints.of(Double.NaN))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("CurrencyPair")
    class Pairs {

        @Test
        @DisplayName("parses and prints the market convention")
        void parsesMarketConvention() {
            CurrencyPair eurusd = CurrencyPair.parse("EUR/USD");

            assertThat(eurusd.base()).isEqualTo(Currency.EUR);
            assertThat(eurusd.quote()).isEqualTo(Currency.USD);
            assertThat(eurusd).hasToString("EUR/USD");
        }

        @Test
        @DisplayName("direction is part of identity, so EUR/USD is not USD/EUR")
        void directionMatters() {
            CurrencyPair eurusd = CurrencyPair.of(Currency.EUR, Currency.USD);
            CurrencyPair usdeur = CurrencyPair.of(Currency.USD, Currency.EUR);

            assertThat(eurusd).isNotEqualTo(usdeur);
            assertThat(eurusd.inverse()).isEqualTo(usdeur);
            assertThat(eurusd.inverse().inverse()).isEqualTo(eurusd);
        }

        @Test
        @DisplayName("rejects a pair of one currency, which has no meaningful rate")
        void rejectsIdenticalCurrencies() {
            assertThatThrownBy(() -> CurrencyPair.of(Currency.USD, Currency.USD))
                    .isInstanceOf(CurrencyPair.IdenticalCurrenciesException.class)
                    .hasMessageContaining("two different currencies");
        }

        @Test
        @DisplayName("rejects malformed text")
        void rejectsMalformed() {
            assertThatThrownBy(() -> CurrencyPair.parse("EURUSD"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("BASE/QUOTE");
        }

        @Test
        @DisplayName("reports which currencies it touches, for exposure bucketing")
        void reportsInvolvedCurrencies() {
            CurrencyPair eurusd = CurrencyPair.parse("EUR/USD");

            assertThat(eurusd.involves(Currency.EUR)).isTrue();
            assertThat(eurusd.involves(Currency.USD)).isTrue();
            assertThat(eurusd.involves(Currency.JPY)).isFalse();
        }
    }

    @Nested
    @DisplayName("Currency")
    class Currencies {

        @Test
        @DisplayName("minor units are not universally two")
        void minorUnitsVary() {
            assertThat(Currency.USD.minorUnits()).isEqualTo(2);
            assertThat(Currency.JPY.minorUnits()).isZero();
        }

        @Test
        @DisplayName("code is the ISO alphabetic code")
        void codeIsIso() {
            assertThat(Currency.USD.code()).isEqualTo("USD");
            assertThat(Currency.JPY.displayName()).isEqualTo("Japanese Yen");
        }

        @Test
        @DisplayName("every currency declares a sane minor-unit count")
        void allCurrenciesAreSane() {
            for (Currency currency : Currency.values()) {
                assertThat(currency.minorUnits()).as("%s", currency).isBetween(0, 4);
                assertThat(currency.displayName()).as("%s", currency).isNotBlank();
                assertThat(BigDecimal.ONE.setScale(currency.minorUnits())).isNotNull();
            }
        }
    }
}
