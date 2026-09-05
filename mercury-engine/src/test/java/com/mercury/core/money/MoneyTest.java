package com.mercury.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Nested
    @DisplayName("scale normalisation")
    class ScaleNormalisation {

        @Test
        @DisplayName("normalises to the currency's minor units")
        void normalisesToMinorUnits() {
            assertThat(Money.of("1.5", Currency.USD).amount()).isEqualTo(new BigDecimal("1.50"));
            assertThat(Money.of("1", Currency.USD).amount()).isEqualTo(new BigDecimal("1.00"));
        }

        @Test
        @DisplayName("JPY has no minor units, so yen amounts carry no decimal places")
        void jpyHasNoMinorUnits() {
            Money yen = Money.of("100.4", Currency.JPY);

            assertThat(yen.amount()).isEqualTo(new BigDecimal("100"));
            assertThat(yen.amount().scale()).isZero();
            assertThat(yen).hasToString("100 JPY");
        }

        @Test
        @DisplayName("equality survives differing input scales, because BigDecimal's does not")
        void equalityIsScaleIndependent() {
            // The point of normalising: new BigDecimal("1.5").equals(new BigDecimal("1.50"))
            // is false, so without it the record's generated equals would be wrong.
            assertThat(new BigDecimal("1.5")).isNotEqualTo(new BigDecimal("1.50"));

            assertThat(Money.of("1.5", Currency.USD))
                    .isEqualTo(Money.of("1.50", Currency.USD))
                    .hasSameHashCodeAs(Money.of("1.500", Currency.USD));
        }

        @Test
        @DisplayName("same amount in different currencies is not equal")
        void currencyIsPartOfIdentity() {
            assertThat(Money.of("10.00", Currency.USD)).isNotEqualTo(Money.of("10.00", Currency.EUR));
        }
    }

    @Nested
    @DisplayName("rounding")
    class Rounding {

        @Test
        @DisplayName("uses HALF_EVEN, so exact halves alternate rather than always rising")
        void usesHalfEven() {
            // Preceding digit even -> stays; odd -> rises. This is what stops a large book
            // of roundings drifting systematically upward, as HALF_UP would.
            assertThat(Money.of("1.005", Currency.USD).amount()).isEqualTo(new BigDecimal("1.00"));
            assertThat(Money.of("1.015", Currency.USD).amount()).isEqualTo(new BigDecimal("1.02"));
            assertThat(Money.of("1.025", Currency.USD).amount()).isEqualTo(new BigDecimal("1.02"));
        }
    }

    @Nested
    @DisplayName("factories")
    class Factories {

        @Test
        @DisplayName("ofMinor interprets its argument as cents, pence or whole yen")
        void ofMinorRespectsCurrency() {
            assertThat(Money.ofMinor(150, Currency.USD)).isEqualTo(Money.of("1.50", Currency.USD));
            assertThat(Money.ofMinor(150, Currency.JPY)).isEqualTo(Money.of("150", Currency.JPY));
        }

        @Test
        @DisplayName("fromModelValue is the single crossing point from double to decimal")
        void fromModelValueConverts() {
            assertThat(Money.fromModelValue(0.1 + 0.2, Currency.USD))
                    .isEqualTo(Money.of("0.30", Currency.USD));
        }

        @Test
        @DisplayName("fromModelValue rejects NaN and infinity, which signal a broken model")
        void fromModelValueRejectsNonFinite() {
            assertThatThrownBy(() -> Money.fromModelValue(Double.NaN, Currency.USD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-finite");

            assertThatThrownBy(() -> Money.fromModelValue(Double.POSITIVE_INFINITY, Currency.USD))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("zero carries a currency")
        void zeroHasCurrency() {
            assertThat(Money.zero(Currency.EUR).isZero()).isTrue();
            assertThat(Money.zero(Currency.EUR).currency()).isEqualTo(Currency.EUR);
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("addition and subtraction are exact")
        void addAndSubtract() {
            Money ten = Money.of("10.00", Currency.USD);
            Money three = Money.of("3.33", Currency.USD);

            assertThat(ten.plus(three)).isEqualTo(Money.of("13.33", Currency.USD));
            assertThat(ten.minus(three)).isEqualTo(Money.of("6.67", Currency.USD));
        }

        @Test
        @DisplayName("a hundred additions of a cent make exactly one dollar")
        void repeatedAdditionDoesNotDrift() {
            // The reason this type is not built on double: 0.01 has no exact binary
            // representation, so the same loop in double arrives at 1.0000000000000007.
            Money total = Money.zero(Currency.USD);
            for (int i = 0; i < 100; i++) {
                total = total.plus(Money.of("0.01", Currency.USD));
            }
            assertThat(total).isEqualTo(Money.of("1.00", Currency.USD));
        }

        @Test
        @DisplayName("multiplication rounds once")
        void multiplicationRoundsOnce() {
            Money price = Money.of("10.01", Currency.USD);

            assertThat(price.multipliedBy(new BigDecimal("3")))
                    .isEqualTo(Money.of("30.03", Currency.USD));
            assertThat(price.multipliedBy(3L)).isEqualTo(Money.of("30.03", Currency.USD));
        }

        @Test
        @DisplayName("division rounds to the currency scale")
        void divisionRounds() {
            assertThat(Money.of("10.00", Currency.USD).dividedBy(new BigDecimal("3")))
                    .isEqualTo(Money.of("3.33", Currency.USD));
        }

        @Test
        @DisplayName("division by zero is rejected")
        void divisionByZeroRejected() {
            assertThatThrownBy(() -> Money.of("1.00", Currency.USD).dividedBy(BigDecimal.ZERO))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        @DisplayName("negation and absolute value")
        void negateAndAbs() {
            Money debit = Money.of("-5.00", Currency.USD);

            assertThat(debit.negated()).isEqualTo(Money.of("5.00", Currency.USD));
            assertThat(debit.abs()).isEqualTo(Money.of("5.00", Currency.USD));
            assertThat(debit.isNegative()).isTrue();
        }
    }

    @Nested
    @DisplayName("currency safety")
    class CurrencySafety {

        @Test
        @DisplayName("adding different currencies throws instead of silently converting")
        void additionRejectsMismatch() {
            Money usd = Money.of("10.00", Currency.USD);
            Money eur = Money.of("10.00", Currency.EUR);

            assertThatThrownBy(() -> usd.plus(eur))
                    .isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("USD")
                    .hasMessageContaining("EUR")
                    .hasMessageContaining("Convert explicitly");
        }

        @Test
        @DisplayName("subtraction and comparison reject mismatches too")
        void otherOperationsRejectMismatch() {
            Money usd = Money.of("10.00", Currency.USD);
            Money jpy = Money.of("10", Currency.JPY);

            assertThatThrownBy(() -> usd.minus(jpy)).isInstanceOf(CurrencyMismatchException.class);
            assertThatThrownBy(() -> usd.compareTo(jpy)).isInstanceOf(CurrencyMismatchException.class);
        }

        @Test
        @DisplayName("the exception reports both currencies for diagnosis")
        void exceptionCarriesBothCurrencies() {
            CurrencyMismatchException thrown = null;
            try {
                Money.of("1.00", Currency.USD).plus(Money.of("1.00", Currency.GBP));
            } catch (CurrencyMismatchException e) {
                thrown = e;
            }

            assertThat(thrown).isNotNull();
            assertThat(thrown.left()).isEqualTo(Currency.USD);
            assertThat(thrown.right()).isEqualTo(Currency.GBP);
        }
    }

    @Nested
    @DisplayName("comparison")
    class Comparison {

        @Test
        @DisplayName("orders amounts within a currency")
        void ordersWithinCurrency() {
            Money small = Money.of("1.00", Currency.USD);
            Money large = Money.of("2.00", Currency.USD);

            assertThat(large.isGreaterThan(small)).isTrue();
            assertThat(small.isLessThan(large)).isTrue();
            assertThat(small.compareTo(small)).isZero();
        }

        @Test
        @DisplayName("sign predicates")
        void signPredicates() {
            assertThat(Money.of("1.00", Currency.USD).isPositive()).isTrue();
            assertThat(Money.of("-1.00", Currency.USD).isNegative()).isTrue();
            assertThat(Money.zero(Currency.USD).isZero()).isTrue();
        }
    }
}
