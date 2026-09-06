package com.mercury.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Price;
import com.mercury.instrument.EuropeanOption;
import com.mercury.instrument.OptionType;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.model.BlackScholesModel;
import com.mercury.pricing.model.NormalDistribution;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reference-value and invariant tests for Black-Scholes.
 *
 * <p>The headline expected values come from Hull, <i>Options, Futures and Other
 * Derivatives</i> - a published source, computed independently of this implementation. A
 * pricer checked only against its own output will confirm that a wrong formula is
 * consistently wrong, which is why the worked example is spelled out below rather than
 * captured from a run.
 */
class BlackScholesModelTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final LocalDate VALUATION = LocalDate.of(2024, 1, 15);

    @Nested
    @DisplayName("reference values")
    class ReferenceValues {

        /**
         * Hull's worked example: S = 42, K = 40, r = 10%, sigma = 20%, T = 0.5 years.
         *
         * <p>Worked through by hand:
         * <pre>
         *   ln(42/40)              = 0.0487902
         *   (r + sigma^2/2) T      = (0.10 + 0.02) x 0.5 = 0.06
         *   sigma sqrt(T)          = 0.2 x 0.7071068 = 0.1414214
         *   d1 = 0.1087902 / 0.1414214 = 0.7693
         *   d2 = 0.7693 - 0.1414214    = 0.6279
         *   call = 42 N(0.7693) - 40 e^(-0.05) N(0.6279) = 4.76
         *   put  = 40 e^(-0.05) N(-0.6279) - 42 N(-0.7693) = 0.81
         * </pre>
         */
        @Test
        @DisplayName("Hull's call example prices to 4.76")
        void hullCall() {
            double call = BlackScholesModel.price(
                    OptionType.CALL, 42.0, 40.0, 0.5, 0.10, 0.20);

            assertThat(call).isCloseTo(4.76, within(0.005));
        }

        @Test
        @DisplayName("Hull's put example prices to 0.81")
        void hullPut() {
            double put = BlackScholesModel.price(
                    OptionType.PUT, 42.0, 40.0, 0.5, 0.10, 0.20);

            assertThat(put).isCloseTo(0.81, within(0.005));
        }

        @Test
        @DisplayName("at-the-money one-year call, 20% vol, zero rates")
        void atTheMoneyZeroRate() {
            // With r = 0 and S = K, the call is S x (2 N(sigma sqrt(T) / 2) - 1).
            // For sigma sqrt(T) = 0.20: 100 x (2 x N(0.1) - 1) = 100 x 0.0796557 = 7.96557.
            double call = BlackScholesModel.price(
                    OptionType.CALL, 100.0, 100.0, 1.0, 0.0, 0.20);

            // Tolerance derived rather than guessed. The price is 100 x (2 N(0.1) - 1), so an
            // error of eps in N becomes 200 eps in the price. With N accurate to 7.5e-8 that
            // is up to 1.5e-5 - and a first attempt at 1e-5 failed at 1.18e-5, which was the
            // tolerance being wrong rather than the pricer. Error bounds have to be propagated
            // through the calculation, not applied to its inputs.
            assertThat(call).isCloseTo(7.9655674, within(2e-5));
        }
    }

    @Nested
    @DisplayName("invariants that must hold for any model")
    class Invariants {

        @Test
        @DisplayName("put-call parity holds across a wide range of spots")
        void putCallParity() {
            // C - P = S - K e^(-rT). This is a no-arbitrage identity, independent of any
            // pricing model, so it is one of the strongest checks available on a pricer.
            double strike = 100.0;
            double years = 0.75;
            double rate = 0.04;
            double volatility = 0.30;

            for (double spot = 40.0; spot <= 200.0; spot += 7.5) {
                double call = BlackScholesModel.price(
                        OptionType.CALL, spot, strike, years, rate, volatility);
                double put = BlackScholesModel.price(
                        OptionType.PUT, spot, strike, years, rate, volatility);

                assertThat(call - put)
                        .as("parity at spot %s", spot)
                        .isCloseTo(spot - strike * Math.exp(-rate * years), within(1e-9));
            }
        }

        @Test
        @DisplayName("a call is worth more as volatility rises")
        void callIncreasesWithVolatility() {
            double previous = -1.0;
            for (double volatility = 0.05; volatility <= 1.0; volatility += 0.05) {
                double call = BlackScholesModel.price(
                        OptionType.CALL, 100.0, 100.0, 1.0, 0.03, volatility);

                assertThat(call).as("vol %s", volatility).isGreaterThan(previous);
                previous = call;
            }
        }

        @Test
        @DisplayName("a call is worth more as spot rises; a put less")
        void monotonicInSpot() {
            double previousCall = -1.0;
            double previousPut = Double.MAX_VALUE;

            for (double spot = 50.0; spot <= 150.0; spot += 5.0) {
                double call = BlackScholesModel.price(OptionType.CALL, spot, 100, 1, 0.03, 0.25);
                double put = BlackScholesModel.price(OptionType.PUT, spot, 100, 1, 0.03, 0.25);

                assertThat(call).as("call at %s", spot).isGreaterThan(previousCall);
                assertThat(put).as("put at %s", spot).isLessThan(previousPut);
                previousCall = call;
                previousPut = put;
            }
        }

        @Test
        @DisplayName("prices stay within their no-arbitrage bounds")
        void respectsArbitrageBounds() {
            // A call is worth at least max(S - Ke^-rT, 0) and never more than S.
            double strike = 100.0;
            double years = 1.0;
            double rate = 0.05;

            for (double spot = 20.0; spot <= 300.0; spot += 20.0) {
                double call = BlackScholesModel.price(
                        OptionType.CALL, spot, strike, years, rate, 0.30);
                double lowerBound = Math.max(spot - strike * Math.exp(-rate * years), 0.0);

                assertThat(call).as("spot %s", spot)
                        .isGreaterThanOrEqualTo(lowerBound - 1e-9)
                        .isLessThanOrEqualTo(spot);
            }
        }

        @Test
        @DisplayName("every price is non-negative - an option is a right, not an obligation")
        void neverNegative() {
            for (double spot = 1.0; spot <= 400.0; spot += 13.0) {
                for (OptionType type : OptionType.values()) {
                    assertThat(BlackScholesModel.price(type, spot, 100, 2.0, 0.05, 0.4))
                            .as("%s at spot %s", type, spot)
                            .isGreaterThanOrEqualTo(0.0);
                }
            }
        }
    }

    @Nested
    @DisplayName("boundary cases that would otherwise produce NaN")
    class Boundaries {

        @Test
        @DisplayName("at expiry the option is worth its intrinsic value")
        void atExpiry() {
            // T = 0 makes sigma sqrt(T) zero, so d1 would divide by zero.
            assertThat(BlackScholesModel.price(OptionType.CALL, 120.0, 100.0, 0.0, 0.05, 0.3))
                    .isCloseTo(20.0, within(1e-12));
            assertThat(BlackScholesModel.price(OptionType.CALL, 80.0, 100.0, 0.0, 0.05, 0.3))
                    .isZero();
            assertThat(BlackScholesModel.price(OptionType.PUT, 80.0, 100.0, 0.0, 0.05, 0.3))
                    .isCloseTo(20.0, within(1e-12));
        }

        @Test
        @DisplayName("zero volatility gives the discounted intrinsic value, not NaN")
        void zeroVolatility() {
            // A certain outcome: the option is worth spot less the discounted strike.
            double call = BlackScholesModel.price(OptionType.CALL, 120.0, 100.0, 1.0, 0.05, 0.0);

            assertThat(call).isCloseTo(120.0 - 100.0 * Math.exp(-0.05), within(1e-12));
            assertThat(Double.isNaN(call)).isFalse();
        }

        @Test
        @DisplayName("deep out of the money is worth almost nothing but stays finite")
        void deepOutOfTheMoney() {
            double call = BlackScholesModel.price(OptionType.CALL, 1.0, 1000.0, 0.1, 0.02, 0.15);

            assertThat(call).isGreaterThanOrEqualTo(0.0).isLessThan(1e-6);
            assertThat(Double.isFinite(call)).isTrue();
        }
    }

    @Nested
    @DisplayName("the model wired to real market data")
    class ThroughTheModel {

        @Test
        @DisplayName("reads spot, volatility and rate from the snapshot")
        void readsFromSnapshot() {
            EuropeanOption option = EuropeanOption.call(
                    "AAPL-C-200", AAPL, Price.of("200"), VALUATION.plusYears(1), Currency.USD);
            MarketDataSnapshot market = MarketDataSnapshot.builder()
                    .spot(AAPL, 200.0)
                    .volatility(AAPL, 0.25)
                    .discountRate(Currency.USD, 0.04)
                    .build();

            ValuationResult result = new BlackScholesModel().price(option, market, VALUATION);

            assertThat(result.currency()).isEqualTo(Currency.USD);
            assertThat(result.model()).isEqualTo(BlackScholesModel.NAME);
            assertThat(result.value()).isGreaterThan(0.0);
            // An at-the-money one-year call at 25% vol is worth roughly a tenth of spot.
            assertThat(result.value()).isBetween(15.0, 30.0);
        }

        @Test
        @DisplayName("missing volatility fails loudly rather than pricing at zero vol")
        void missingVolatilityThrows() {
            EuropeanOption option = EuropeanOption.call(
                    "AAPL-C-200", AAPL, Price.of("200"), VALUATION.plusYears(1), Currency.USD);
            MarketDataSnapshot incomplete = MarketDataSnapshot.builder()
                    .spot(AAPL, 200.0).discountRate(Currency.USD, 0.04).build();

            assertThatThrownBy(() -> new BlackScholesModel().price(option, incomplete, VALUATION))
                    .isInstanceOf(MarketDataSnapshot.MissingMarketDataException.class)
                    .hasMessageContaining("vol:AAPL");
        }

        @Test
        @DisplayName("prices the underlying's spot, not the option's own id")
        void usesUnderlyingSpot() {
            // The option references its underlying by id; a snapshot keyed by the option's own
            // id must not satisfy it.
            EuropeanOption option = EuropeanOption.call(
                    "AAPL-C-200", AAPL, Price.of("200"), VALUATION.plusYears(1), Currency.USD);
            MarketDataSnapshot wrongKey = MarketDataSnapshot.builder()
                    .spot(InstrumentId.of("AAPL-C-200"), 200.0)
                    .volatility(InstrumentId.of("AAPL-C-200"), 0.25)
                    .discountRate(Currency.USD, 0.04)
                    .build();

            assertThatThrownBy(() -> new BlackScholesModel().price(option, wrongKey, VALUATION))
                    .isInstanceOf(MarketDataSnapshot.MissingMarketDataException.class)
                    .hasMessageContaining("spot:AAPL");
        }
    }

    @Nested
    @DisplayName("the cumulative normal")
    class CumulativeNormal {

        @Test
        @DisplayName("matches known values within the stated 7.5e-8 bound")
        void knownValues() {
            assertThat(NormalDistribution.cumulative(0.0)).isCloseTo(0.5, within(7.5e-8));
            assertThat(NormalDistribution.cumulative(1.0)).isCloseTo(0.8413447, within(7.5e-8));
            assertThat(NormalDistribution.cumulative(-1.0)).isCloseTo(0.1586553, within(7.5e-8));
            assertThat(NormalDistribution.cumulative(1.96)).isCloseTo(0.9750021, within(7.5e-8));
            assertThat(NormalDistribution.cumulative(-2.5)).isCloseTo(0.0062097, within(7.5e-8));
        }

        @Test
        @DisplayName("is symmetric: N(-x) = 1 - N(x), to the approximation's accuracy")
        void isSymmetric() {
            // The reflection makes this exact for x > 0, but not to 1e-12 at x = 0: in Java
            // -0.0 >= 0.0 is true, so cumulative(-0.0) takes the same branch as cumulative(0.0)
            // and the two differ by twice the approximation's error at the origin. Asserting
            // against the documented 7.5e-8 bound is the honest statement of what this
            // implementation guarantees.
            for (double x = 0.0; x <= 5.0; x += 0.25) {
                assertThat(NormalDistribution.cumulative(-x))
                        .as("x = %s", x)
                        .isCloseTo(1.0 - NormalDistribution.cumulative(x), within(7.5e-8));
            }
        }

        @Test
        @DisplayName("symmetry is exact away from zero, where the reflection applies cleanly")
        void symmetryIsExactAwayFromZero() {
            for (double x = 0.25; x <= 5.0; x += 0.25) {
                assertThat(NormalDistribution.cumulative(-x))
                        .as("x = %s", x)
                        .isCloseTo(1.0 - NormalDistribution.cumulative(x), within(1e-15));
            }
        }

        @Test
        @DisplayName("is monotonically increasing and bounded to [0, 1]")
        void monotonicAndBounded() {
            double previous = -1.0;
            for (double x = -6.0; x <= 6.0; x += 0.1) {
                double value = NormalDistribution.cumulative(x);

                assertThat(value).as("x = %s", x).isBetween(0.0, 1.0).isGreaterThan(previous);
                previous = value;
            }
        }

        @Test
        @DisplayName("handles infinities")
        void handlesInfinities() {
            assertThat(NormalDistribution.cumulative(Double.POSITIVE_INFINITY)).isEqualTo(1.0);
            assertThat(NormalDistribution.cumulative(Double.NEGATIVE_INFINITY)).isZero();
            assertThatThrownBy(() -> NormalDistribution.cumulative(Double.NaN))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
