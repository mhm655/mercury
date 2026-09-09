package com.mercury.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.Frequency;
import com.mercury.core.time.SchedulePeriod;
import com.mercury.core.time.Tenor;
import com.mercury.curve.CurveBootstrapper;
import com.mercury.curve.CurveInstrument;
import com.mercury.curve.DepositQuote;
import com.mercury.curve.ParSwapQuote;
import com.mercury.curve.YieldCurve;
import com.mercury.instrument.FloatingRateIndex;
import com.mercury.instrument.InterestRateSwap;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.model.SwapModel;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Checks the swap model against identities, not against its own past output.
 *
 * <h2>Why identities rather than reference values</h2>
 * A swap has no textbook price the way a Black-Scholes option does - its value depends
 * entirely on the curve it is priced against, and that curve is itself fitted. What it does
 * have is a set of relationships that must hold on <em>any</em> curve, and they are strong
 * enough to pin the implementation down:
 *
 * <ul>
 *   <li>A swap struck at its own par rate is worth nothing.</li>
 *   <li>A floating leg is worth {@code N x (DF(start) - DF(end))} however often it pays.</li>
 *   <li>Payer and receiver are exact opposites.</li>
 * </ul>
 *
 * <p>The middle one is the sharpest. It only holds if floating coupons are projected at the
 * simple forward rate on the leg's own day count, so the accrual fraction cancels against the
 * discounting. A model that projected the continuously-compounded forward instead would look
 * right, be within a few basis points, and fail here - which is exactly what a test is for.
 *
 * <h2>Tolerances</h2>
 * Coupons are {@link Money} and round to the cent. Twenty-five cashflows on a ten million
 * notional can therefore accumulate up to about 12 cents of rounding before any discounting,
 * so identities that ought to be exact are asserted to half a dollar - five parts in a
 * hundred million of the notional. The tolerance is the rounding, not the model.
 */
class SwapModelTest {

    private static final LocalDate TODAY = LocalDate.of(2024, 6, 28);
    private static final Money NOTIONAL = Money.of("10000000", Currency.USD);
    private static final SwapModel MODEL = new SwapModel();

    /** Cent-rounding across the cashflows of one swap, generously bounded. */
    private static final double ROUNDING = 0.50;

    private static final double FIVE_YEAR_QUOTE = 0.0425;

    private static List<CurveInstrument> quotes() {
        return List.of(
                DepositQuote.of(Tenor.months(3), 0.0533),
                DepositQuote.of(Tenor.months(6), 0.0524),
                DepositQuote.of(Tenor.years(1), 0.0500),
                ParSwapQuote.of(Tenor.years(2), 0.0460),
                ParSwapQuote.of(Tenor.years(5), FIVE_YEAR_QUOTE),
                ParSwapQuote.of(Tenor.years(10), 0.0430));
    }

    private static MarketDataSnapshot market() {
        return MarketDataSnapshot.builder(TODAY)
                .curve(Currency.USD, CurveBootstrapper.bootstrap(TODAY, quotes()))
                .build();
    }

    /** A five-year swap on the same conventions the curve was fitted with. */
    private static InterestRateSwap swapAt(String fixedRate) {
        return InterestRateSwap.builder()
                .id("IRS-5Y")
                .notional(NOTIONAL)
                .fixedRate(fixedRate)
                .payingFixed()
                .fixedFrequency(Frequency.SEMI_ANNUAL)
                .fixedDayCount(DayCountConvention.THIRTY_360_US)
                .floatingFrequency(Frequency.QUARTERLY)
                .index(FloatingRateIndex.usdSofr3M())
                .effectiveDate(TODAY)
                .maturityDate(Tenor.years(5).addTo(TODAY))
                .build();
    }

    @Nested
    @DisplayName("par")
    class Par {

        @Test
        @DisplayName("a swap struck at the curve's own five-year quote is worth nothing")
        void quotedParSwapIsWorthless() {
            // The end-to-end check on M5b and M6 together. The curve was fitted so that a
            // five-year par swap at 4.25% has no value; this builds that swap for real - two
            // legs, two schedules, projected coupons, discounting - and asks what it is worth.
            // Nothing in the pricing path knows it was an input to the bootstrap.
            double value = MODEL.price(swapAt("0.0425"), market(), TODAY).value();

            assertThat(value).isCloseTo(0.0, within(ROUNDING));
        }

        @Test
        @DisplayName("the computed par rate is the rate that makes it worthless")
        void parRateIsSelfConsistent() {
            MarketDataSnapshot market = market();
            double par = MODEL.parRate(swapAt("0.04"), market, TODAY);

            double value = MODEL.price(
                    swapAt(java.math.BigDecimal.valueOf(par).toPlainString()), market, TODAY)
                    .value();

            assertThat(value).isCloseTo(0.0, within(ROUNDING));
        }

        @Test
        @DisplayName("the par rate recovers the quote the curve was built from")
        void parRateRecoversTheQuote() {
            // Built on the same conventions ParSwapQuote uses, so the two should agree to well
            // inside a basis point. This closes the loop the other way round from the test
            // above: there, the curve priced a swap; here, the swap reports the curve.
            double par = MODEL.parRate(swapAt("0.04"), market(), TODAY);

            assertThat(par).isCloseTo(FIVE_YEAR_QUOTE, within(1e-6));
        }

        @Test
        @DisplayName("a swap paying above par has negative value to the payer")
        void offMarketSwap() {
            MarketDataSnapshot market = market();

            double expensive = MODEL.price(swapAt("0.0525"), market, TODAY).value();
            double cheap = MODEL.price(swapAt("0.0325"), market, TODAY).value();

            assertThat(expensive).isNegative();
            assertThat(cheap).isPositive();
        }
    }

    @Nested
    @DisplayName("the floating leg")
    class Floating {

        /** The floating leg alone, recovered as the swap minus its fixed leg. */
        private static double floatingLegValue(InterestRateSwap swap, MarketDataSnapshot market) {
            YieldCurve curve = market.yieldCurve(Currency.USD);
            double fixed = 0.0;
            for (SchedulePeriod period : swap.fixedLeg().schedule().unpaidPeriodsAsOf(TODAY)) {
                fixed += swap.fixedLeg().couponFor(period).amount().doubleValue()
                        * curve.discountFactor(period.paymentDate());
            }
            return MODEL.price(swap, market, TODAY).value() - fixed;
        }

        @Test
        @DisplayName("it is worth the notional times the change in discount factor, exactly")
        void telescopes() {
            // The identity that only holds if coupons are projected at the SIMPLE forward rate
            // on the leg's own day count. Every accrual fraction cancels against its own
            // discount factor and the sum collapses to two terms - which is why a floating leg
            // needs no schedule arithmetic at all to value, and why getting the convention
            // wrong is visible rather than subtle.
            InterestRateSwap swap = swapAt("0.0425");
            MarketDataSnapshot market = market();
            YieldCurve curve = market.yieldCurve(Currency.USD);

            LocalDate start = swap.floatingLeg().schedule().first().accrualStart();
            LocalDate end = swap.floatingLeg().schedule().last().accrualEnd();
            double expected = NOTIONAL.amount().doubleValue()
                    * (curve.discountFactor(start) - curve.discountFactor(end));

            // The floating leg is received on a payer swap, so it enters positively.
            assertThat(floatingLegValue(swap, market)).isCloseTo(expected, within(ROUNDING));
        }

        @Test
        @DisplayName("its value does not depend on how often it pays")
        void frequencyDoesNotMatter() {
            // A corollary of the telescoping, and a surprising one: a quarterly and an annual
            // floating leg on the same dates are worth the same. Worth asserting because it is
            // the kind of claim that is easy to state and easy to have quietly false.
            MarketDataSnapshot market = market();

            double quarterly = floatingLegValue(swapWithFloatingFrequency(Frequency.QUARTERLY),
                    market);
            double annual = floatingLegValue(swapWithFloatingFrequency(Frequency.ANNUAL), market);

            assertThat(annual).isCloseTo(quarterly, within(ROUNDING));
        }

        @Test
        @DisplayName("the simple forward and the continuous forward are different numbers")
        void projectionConventionMatters() {
            // Quantifying the mistake this model is built to avoid. Projecting at the curve's
            // continuously-compounded forward instead of the simple rate an index fixes at is
            // wrong by a few basis points per period - small enough to look like modelling
            // imprecision, large enough to move a ten million notional by hundreds.
            YieldCurve curve = market().yieldCurve(Currency.USD);
            LocalDate start = LocalDate.of(2025, 6, 30);
            LocalDate end = LocalDate.of(2025, 9, 30);

            double simple = curve.simpleForwardRate(start, end, DayCountConvention.ACT_360);
            double continuous = curve.forwardRate(curve.timeTo(start), curve.timeTo(end));

            assertThat(simple).isNotCloseTo(continuous, within(1e-5));
            assertThat(Math.abs(simple - continuous)).isLessThan(1e-3);
        }

        @Test
        @DisplayName("a spread over the index is worth its own annuity")
        void spreadRaisesTheLegItIsPaidOn() {
            // A payer swap receives floating, so a spread on the floating leg makes the swap
            // more valuable. Direction only - the size is checked by the par identity.
            MarketDataSnapshot market = market();

            double withoutSpread = MODEL.price(swapAt("0.0425"), market, TODAY).value();
            double withSpread = MODEL.price(
                    InterestRateSwap.builder()
                            .id("IRS-5Y-SPREAD")
                            .notional(NOTIONAL)
                            .fixedRate("0.0425")
                            .payingFixed()
                            .fixedFrequency(Frequency.SEMI_ANNUAL)
                            .fixedDayCount(DayCountConvention.THIRTY_360_US)
                            .floatingFrequency(Frequency.QUARTERLY)
                            .index(FloatingRateIndex.usdSofr3M())
                            .spread(BasisPoints.of(25))
                            .effectiveDate(TODAY)
                            .maturityDate(Tenor.years(5).addTo(TODAY))
                            .build(),
                    market, TODAY).value();

            assertThat(withSpread).isGreaterThan(withoutSpread);
        }

        private static InterestRateSwap swapWithFloatingFrequency(Frequency frequency) {
            return InterestRateSwap.builder()
                    .id("IRS-" + frequency)
                    .notional(NOTIONAL)
                    .fixedRate("0.0425")
                    .payingFixed()
                    .fixedFrequency(Frequency.SEMI_ANNUAL)
                    .fixedDayCount(DayCountConvention.THIRTY_360_US)
                    .floatingFrequency(frequency)
                    .index(FloatingRateIndex.usdSofr3M())
                    .effectiveDate(TODAY)
                    .maturityDate(Tenor.years(5).addTo(TODAY))
                    .build();
        }
    }

    @Nested
    @DisplayName("direction and lifecycle")
    class Direction {

        @Test
        @DisplayName("payer and receiver are exact opposites")
        void payerAndReceiverMirror() {
            MarketDataSnapshot market = market();

            double payer = MODEL.price(swapAt("0.0500"), market, TODAY).value();
            double receiver = MODEL.price(
                    InterestRateSwap.builder()
                            .id("IRS-RECEIVER")
                            .notional(NOTIONAL)
                            .fixedRate("0.0500")
                            .receivingFixed()
                            .fixedFrequency(Frequency.SEMI_ANNUAL)
                            .fixedDayCount(DayCountConvention.THIRTY_360_US)
                            .floatingFrequency(Frequency.QUARTERLY)
                            .index(FloatingRateIndex.usdSofr3M())
                            .effectiveDate(TODAY)
                            .maturityDate(Tenor.years(5).addTo(TODAY))
                            .build(),
                    market, TODAY).value();

            assertThat(receiver).isCloseTo(-payer, within(1e-9));
        }

        @Test
        @DisplayName("a payer swap gains value when rates rise")
        void payerGainsAsRatesRise() {
            // The defining economic property of the position, and the one a sign error breaks.
            MarketDataSnapshot base = market();
            MarketDataSnapshot higher = base.withShock(
                    com.mercury.marketdata.MarketShock.bumpRate(Currency.USD, BasisPoints.of(50)));

            InterestRateSwap swap = swapAt("0.0425");

            assertThat(MODEL.price(swap, higher, TODAY).value())
                    .isGreaterThan(MODEL.price(swap, base, TODAY).value());
        }

        @Test
        @DisplayName("a matured swap is worth nothing and has no par rate")
        void maturedSwap() {
            InterestRateSwap swap = swapAt("0.0425");
            LocalDate afterMaturity = swap.maturityDate().plusDays(1);
            MarketDataSnapshot later = MarketDataSnapshot.builder(afterMaturity)
                    .curve(Currency.USD, YieldCurve.flat(afterMaturity, 0.04))
                    .build();

            assertThat(MODEL.price(swap, later, afterMaturity).value()).isEqualTo(0.0);
            assertThatThrownBy(() -> MODEL.parRate(swap, later, afterMaturity))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no fixed coupons left");
        }
    }

    @Nested
    @DisplayName("through the registry")
    class ThroughTheRegistry {

        @Test
        @DisplayName("a swap dispatches to the swap model with nothing else registered for it")
        void dispatches() {
            PricingService pricing = PricingService.builder().register(new SwapModel()).build();

            ValuationResult result = pricing.price(swapAt("0.0425"), market(), TODAY);

            assertThat(result.model()).isEqualTo(SwapModel.NAME);
            assertThat(result.currency()).isEqualTo(Currency.USD);
        }
    }
}
