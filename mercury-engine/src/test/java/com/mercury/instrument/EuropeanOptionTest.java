package com.mercury.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Price;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EuropeanOptionTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final LocalDate VALUATION = LocalDate.of(2024, 1, 15);
    private static final LocalDate EXPIRY = LocalDate.of(2025, 1, 15);
    private static final double TOLERANCE = 1e-9;

    private static EuropeanOption call() {
        return EuropeanOption.call("AAPL-C-200", AAPL, Price.of("200"), EXPIRY, Currency.USD);
    }

    private static EuropeanOption put() {
        return EuropeanOption.put("AAPL-P-200", AAPL, Price.of("200"), EXPIRY, Currency.USD);
    }

    @Nested
    @DisplayName("classification")
    class Classification {

        @Test
        @DisplayName("is an OTC equity derivative")
        void classification() {
            EuropeanOption option = call();

            assertThat(option.assetClass()).isEqualTo(AssetClass.EQUITY);
            assertThat(option.tradability()).isEqualTo(TradabilityProfile.OVER_THE_COUNTER);
            assertThat(option.currency()).isEqualTo(Currency.USD);
        }

        @Test
        @DisplayName("has an underlying and option terms, but no contractual cashflows")
        void capabilities() {
            EuropeanOption option = call();

            assertThat(option).isInstanceOf(HasUnderlying.class);
            assertThat(option).isInstanceOf(OptionTerms.class);
            assertThat(option).isInstanceOf(Maturing.class);
            // An option's payoff is contingent, not contractual.
            assertThat(option).isNotInstanceOf(CashflowGenerating.class);
        }

        @Test
        @DisplayName("references its underlying by identity, not by object")
        void referencesUnderlyingById() {
            assertThat(call().underlyingId()).isEqualTo(AAPL);
        }

        @Test
        @DisplayName("expiry is its maturity")
        void expiryIsMaturity() {
            EuropeanOption option = call();

            assertThat(option.maturityDate()).isEqualTo(option.expiryDate()).isEqualTo(EXPIRY);
            assertThat(option.hasMaturedAsOf(VALUATION)).isFalse();
            assertThat(option.hasMaturedAsOf(EXPIRY)).isTrue();
        }

        @Test
        @DisplayName("uses the standard equity contract multiplier by default")
        void standardMultiplier() {
            assertThat(call().contractMultiplier()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("payoff")
    class Payoff {

        @Test
        @DisplayName("a call pays spot minus strike when in the money, nothing otherwise")
        void callPayoff() {
            EuropeanOption option = call();

            assertThat(option.intrinsicValue(250.0)).isCloseTo(50.0, within(TOLERANCE));
            assertThat(option.intrinsicValue(200.0)).isCloseTo(0.0, within(TOLERANCE));
            assertThat(option.intrinsicValue(150.0)).isCloseTo(0.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("a put pays strike minus spot when in the money, nothing otherwise")
        void putPayoff() {
            EuropeanOption option = put();

            assertThat(option.intrinsicValue(150.0)).isCloseTo(50.0, within(TOLERANCE));
            assertThat(option.intrinsicValue(200.0)).isCloseTo(0.0, within(TOLERANCE));
            assertThat(option.intrinsicValue(250.0)).isCloseTo(0.0, within(TOLERANCE));
        }

        @Test
        @DisplayName("intrinsic value is never negative - an option is a right, not an obligation")
        void payoffIsNeverNegative() {
            for (double spot = 1.0; spot <= 400.0; spot += 7.0) {
                assertThat(call().intrinsicValue(spot)).as("call at spot %s", spot)
                        .isGreaterThanOrEqualTo(0.0);
                assertThat(put().intrinsicValue(spot)).as("put at spot %s", spot)
                        .isGreaterThanOrEqualTo(0.0);
            }
        }

        @Test
        @DisplayName("call minus put intrinsic equals spot minus strike, at every spot")
        void intrinsicParity() {
            // The intrinsic-value form of put-call parity. It holds by construction here,
            // and the full parity relation on discounted prices is checked at M6.
            double strike = 200.0;
            for (double spot = 50.0; spot <= 350.0; spot += 11.0) {
                double difference = OptionType.CALL.intrinsicValue(spot, strike)
                        - OptionType.PUT.intrinsicValue(spot, strike);

                assertThat(difference).as("spot %s", spot)
                        .isCloseTo(spot - strike, within(TOLERANCE));
            }
        }
    }

    @Nested
    @DisplayName("time to expiry")
    class TimeToExpiry {

        @Test
        @DisplayName("one year on ACT/365F, the convention Black-Scholes assumes")
        void oneYear() {
            // 15 Jan 2024 to 15 Jan 2025 is 366 actual days (2024 being a leap year),
            // and ACT/365F always divides by 365.
            assertThat(call().yearsToExpiry(VALUATION))
                    .isCloseTo(366.0 / 365.0, within(1e-12));
        }

        @Test
        @DisplayName("is zero at expiry")
        void zeroAtExpiry() {
            assertThat(call().yearsToExpiry(EXPIRY)).isCloseTo(0.0, within(TOLERANCE));
        }
    }

    @Nested
    @DisplayName("opposite type")
    class OppositeType {

        @Test
        @DisplayName("produces the matching option with the same terms")
        void producesMatchingOption() {
            EuropeanOption theCall = call();
            EuropeanOption thePut = theCall.withOppositeType();

            assertThat(thePut.optionType()).isEqualTo(OptionType.PUT);
            assertThat(thePut.strike()).isEqualTo(theCall.strike());
            assertThat(thePut.expiryDate()).isEqualTo(theCall.expiryDate());
            assertThat(thePut.underlyingId()).isEqualTo(theCall.underlyingId());
            assertThat(thePut.id()).isNotEqualTo(theCall.id());
        }

        @Test
        @DisplayName("OptionType.opposite is its own inverse")
        void oppositeIsInvolution() {
            assertThat(OptionType.CALL.opposite()).isEqualTo(OptionType.PUT);
            assertThat(OptionType.PUT.opposite()).isEqualTo(OptionType.CALL);
            assertThat(OptionType.CALL.opposite().opposite()).isEqualTo(OptionType.CALL);
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects a non-positive contract multiplier")
        void rejectsBadMultiplier() {
            assertThatThrownBy(() -> new EuropeanOption(
                    InstrumentId.of("X"), AAPL, OptionType.CALL, Price.of("200"),
                    EXPIRY, 0, Currency.USD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("multiplier must be positive");
        }

        @Test
        @DisplayName("rejects an option written on itself")
        void rejectsSelfReference() {
            assertThatThrownBy(() -> new EuropeanOption(
                    AAPL, AAPL, OptionType.CALL, Price.of("200"), EXPIRY, 100, Currency.USD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be its own underlying");
        }

        @Test
        @DisplayName("strike must be positive, which Price already guarantees")
        void strikeMustBePositive() {
            assertThatThrownBy(() -> Price.of("0"))
                    .isInstanceOf(Price.NonPositivePriceException.class);
        }
    }
}
