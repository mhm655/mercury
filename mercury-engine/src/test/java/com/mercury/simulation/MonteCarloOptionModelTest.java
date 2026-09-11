package com.mercury.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Price;
import com.mercury.instrument.EuropeanOption;
import com.mercury.instrument.OptionType;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.ValuationResult;
import com.mercury.pricing.model.BlackScholesModel;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The cross-validation `docs/DESIGN_PROPOSAL.md` section 8 names: "Monte Carlo option price
 * converging to the closed form."
 */
class MonteCarloOptionModelTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final LocalDate VALUATION = LocalDate.of(2024, 1, 15);
    private static final LocalDate EXPIRY = LocalDate.of(2025, 1, 15);

    private static final EuropeanOption CALL = EuropeanOption.call(
            "AAPL-C-200", AAPL, Price.of("200"), EXPIRY, Currency.USD);
    private static final EuropeanOption PUT = EuropeanOption.put(
            "AAPL-P-200", AAPL, Price.of("200"), EXPIRY, Currency.USD);

    private static MarketDataSnapshot market() {
        return MarketDataSnapshot.builder(VALUATION)
                .spot(AAPL, 200.0)
                .volatility(AAPL, 0.25)
                .discountRate(Currency.USD, 0.04)
                .build();
    }

    @Nested
    @DisplayName("convergence to the closed form")
    class Convergence {

        @Test
        @DisplayName("a call converges to the Black-Scholes price at a large path count")
        void callConvergesToClosedForm() {
            double analytic = new BlackScholesModel().price(CALL, market(), VALUATION).value();
            double simulated = new MonteCarloOptionModel(42, 200_000)
                    .price(CALL, market(), VALUATION).value();

            // The payoff standard deviation here is roughly 50 per share (consistent with
            // sigma * spot * sqrt(T) = 0.25 * 200 * 1), so the per-share standard error at
            // 200,000 paths is about 50 / sqrt(200,000) =~ 0.11, or about 11 per 100-share
            // contract. A tolerance of five standard errors (=~ 55, rounded up to 60) is a
            // roughly 1-in-3-million false-failure rate per test at this fixed seed - loose
            // enough to be non-flaky, not so loose it would pass a broken implementation.
            assertThat(simulated).isCloseTo(analytic, within(60.0));
        }

        @Test
        @DisplayName("a put converges to the Black-Scholes price at a large path count")
        void putConvergesToClosedForm() {
            double analytic = new BlackScholesModel().price(PUT, market(), VALUATION).value();
            double simulated = new MonteCarloOptionModel(7, 200_000)
                    .price(PUT, market(), VALUATION).value();

            assertThat(simulated).isCloseTo(analytic, within(60.0));
        }

        @Test
        @DisplayName("the error shrinks as the path count grows - the actual convergence trend")
        void errorShrinksWithMorePaths() {
            // A single agreeing point does not demonstrate convergence by itself - it could
            // agree by coincidence at that one path count. This checks the trend the name
            // "convergence" actually promises: more paths, closer to the truth, on average.
            // Averaged over several seeds so one unlucky draw cannot flip the comparison.
            double analytic = new BlackScholesModel().price(CALL, market(), VALUATION).value();

            double smallCountTotalError = 0.0;
            double largeCountTotalError = 0.0;
            int trials = 5;
            for (long seed = 1; seed <= trials; seed++) {
                double small = new MonteCarloOptionModel(seed, 200)
                        .price(CALL, market(), VALUATION).value();
                double large = new MonteCarloOptionModel(seed, 50_000)
                        .price(CALL, market(), VALUATION).value();
                smallCountTotalError += Math.abs(small - analytic);
                largeCountTotalError += Math.abs(large - analytic);
            }

            assertThat(largeCountTotalError / trials).isLessThan(smallCountTotalError / trials);
        }

        @Test
        @DisplayName("priceOneShare agrees with the textbook Black-Scholes formula directly")
        void priceOneShareMatchesTextbookInputs() {
            // The same known-answer style SensitivityCalculatorTest already uses for delta:
            // drive both static/instance formulas with identical raw inputs, no instrument or
            // market snapshot in between.
            double spot = 200.0;
            double strike = 200.0;
            double years = 1.0;
            double rate = 0.04;
            double volatility = 0.25;

            double analytic = BlackScholesModel.price(OptionType.CALL, spot, strike, years, rate, volatility);
            double simulated = new MonteCarloOptionModel(99, 200_000)
                    .priceOneShare(OptionType.CALL, spot, strike, years, rate, volatility);

            // Five standard errors at the per-share scale (~0.11) - see callConvergesToClosedForm.
            assertThat(simulated).isCloseTo(analytic, within(0.6));
        }
    }

    @Nested
    @DisplayName("reproducibility and purity")
    class Reproducibility {

        @Test
        @DisplayName("the same seed and market always give the same price")
        void deterministic() {
            MonteCarloOptionModel model = new MonteCarloOptionModel(123, 1_000);

            ValuationResult first = model.price(CALL, market(), VALUATION);
            ValuationResult second = model.price(CALL, market(), VALUATION);

            assertThat(first.value()).isEqualTo(second.value());
        }

        @Test
        @DisplayName("a different seed generally gives a different price at a small path count")
        void differentSeedsDiffer() {
            // At a small path count the sampling error is large enough that two seeds should
            // essentially never land on the exact same average.
            ValuationResult first = new MonteCarloOptionModel(1, 200).price(CALL, market(), VALUATION);
            ValuationResult second = new MonteCarloOptionModel(2, 200).price(CALL, market(), VALUATION);

            assertThat(first.value()).isNotEqualTo(second.value());
        }

        @Test
        @DisplayName("registered through PricingService like any other model")
        void registersThroughPricingService() {
            PricingService pricingService = PricingService.builder()
                    .register(new BlackScholesModel())
                    .register(new MonteCarloOptionModel(42, 200_000))
                    .build();

            ValuationResult analytic = pricingService.price(CALL, BlackScholesModel.NAME, market(), VALUATION);
            ValuationResult simulated = pricingService.price(CALL, MonteCarloOptionModel.NAME, market(), VALUATION);

            assertThat(simulated.value()).isCloseTo(analytic.value(), within(60.0));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects a non-positive path count")
        void rejectsBadPathCount() {
            assertThatThrownBy(() -> new MonteCarloOptionModel(1, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
