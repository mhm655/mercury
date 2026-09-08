package com.mercury.curve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.Tenor;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The par round-trip: a bootstrapped curve must reprice the quotes it was built from.
 *
 * <h2>Why this is the test that matters</h2>
 * A curve has no independently known answer to check against. There is no closed form for
 * "the 7Y point given these ten quotes" - the curve <em>is</em> whatever fits, and different
 * interpolation gives a different, equally correct curve. So a reference-value test would
 * only assert that the implementation still does what it did yesterday.
 *
 * <p>What can be asserted is the defining property. If the curve is consistent with the
 * market it was built from, then feeding each input instrument back through it must return
 * the quoted rate: the deposit must reprice to its own discount factor, and the par swap must
 * have a present value of zero. That is a genuine round trip through the interpolator, the
 * schedule generator and the solver, and almost nothing can be wrong in any of them without
 * it failing.
 *
 * <p>The bootstrapper performs this check internally as well, and throwing there is not a
 * substitute for testing it here: the internal check guards a caller at runtime, and these
 * tests guard the check itself.
 */
class CurveBootstrapperTest {

    private static final LocalDate TODAY = LocalDate.of(2024, 6, 28);

    /** An upward-sloping market: deposits out to a year, swaps beyond it. */
    private static List<CurveInstrument> marketQuotes() {
        return List.of(
                DepositQuote.of(Tenor.months(3), 0.0530),
                DepositQuote.of(Tenor.months(6), 0.0525),
                DepositQuote.of(Tenor.years(1), 0.0500),
                ParSwapQuote.of(Tenor.years(2), 0.0460),
                ParSwapQuote.of(Tenor.years(5), 0.0425),
                ParSwapQuote.of(Tenor.years(10), 0.0430),
                ParSwapQuote.of(Tenor.years(30), 0.0420));
    }

    @Nested
    @DisplayName("the par round trip")
    class ParRoundTrip {

        @Test
        @DisplayName("every input quote reprices to par under the finished curve")
        void allQuotesReprice() {
            List<CurveInstrument> quotes = marketQuotes();

            YieldCurve curve = CurveBootstrapper.bootstrap(TODAY, quotes);

            for (CurveInstrument quote : quotes) {
                assertThat(quote.repricingError(curve))
                        .as("%s should reprice to par", quote.describe())
                        .isCloseTo(0.0, within(CurveBootstrapper.PAR_TOLERANCE));
            }
        }

        @Test
        @DisplayName("it holds under either interpolation scheme")
        void roundTripIsSchemeIndependent() {
            // The fitted curves differ between the pillars - that is the point of having a
            // choice - but both must agree with the market they were fitted to. A round trip
            // that only worked for one scheme would mean the solver and the interpolator were
            // coupled in a way neither should be.
            List<CurveInstrument> quotes = marketQuotes();

            for (Interpolation scheme : Interpolation.values()) {
                YieldCurve curve = CurveBootstrapper.bootstrap(TODAY, scheme, quotes);
                for (CurveInstrument quote : quotes) {
                    assertThat(quote.repricingError(curve))
                            .as("%s under %s", quote.describe(), scheme)
                            .isCloseTo(0.0, within(CurveBootstrapper.PAR_TOLERANCE));
                }
            }
        }

        @Test
        @DisplayName("the two schemes fit the same quotes to different curves in between")
        void schemesDisagreeBetweenPillars() {
            List<CurveInstrument> quotes = marketQuotes();

            YieldCurve linear = CurveBootstrapper.bootstrap(TODAY, Interpolation.LINEAR_ZERO,
                    quotes);
            YieldCurve flatForward = CurveBootstrapper.bootstrap(
                    TODAY, Interpolation.LOG_LINEAR_DISCOUNT, quotes);

            assertThat(linear.zeroRate(7.0))
                    .isNotCloseTo(flatForward.zeroRate(7.0), within(1e-9));
        }

        @Test
        @DisplayName("quotes given out of order are bootstrapped shortest first anyway")
        void orderOfInputDoesNotMatter() {
            List<CurveInstrument> forwards = marketQuotes();
            List<CurveInstrument> backwards = forwards.reversed();

            YieldCurve fromForwards = CurveBootstrapper.bootstrap(TODAY, forwards);
            YieldCurve fromBackwards = CurveBootstrapper.bootstrap(TODAY, backwards);

            assertThat(fromBackwards.pillars()).isEqualTo(fromForwards.pillars());
        }
    }

    @Nested
    @DisplayName("pillars land where the money moves")
    class PillarDates {

        @Test
        @DisplayName("a swap whose nominal tenor falls on a weekend still reprices exactly")
        void weekendTenorStillFits() {
            // The regression test for the defect that forced pillars onto dates rather than
            // tenors. 28 June 2026 is a Sunday, so this swap settles on the 29th - past the
            // pillar a tenor-keyed curve placed for it. With a longer quote in the set, that
            // final payment interpolated against a rate not yet solved, and the finished curve
            // missed this one quote by 1.5 basis points of present value while fitting every
            // other quote exactly.
            CurveInstrument weekendSwap = ParSwapQuote.of(Tenor.years(2), 0.046);

            assertThat(weekendSwap.pillarDate(TODAY)).isEqualTo(LocalDate.of(2026, 6, 29));

            YieldCurve curve = CurveBootstrapper.bootstrap(TODAY, List.of(
                    DepositQuote.of(Tenor.months(6), 0.0525),
                    weekendSwap,
                    ParSwapQuote.of(Tenor.years(5), 0.0425)));

            assertThat(weekendSwap.repricingError(curve))
                    .isCloseTo(0.0, within(CurveBootstrapper.PAR_TOLERANCE));
        }

        @Test
        @DisplayName("a pillar sits on the date the instrument actually settles")
        void pillarIsTheSettlementDate() {
            // 28 September 2024 is a Saturday, so a 3M deposit returns the cash on Monday the
            // 30th, and that is where its pillar belongs.
            DepositQuote deposit = DepositQuote.of(Tenor.months(3), 0.053);

            assertThat(deposit.pillarDate(TODAY)).isEqualTo(LocalDate.of(2024, 9, 30));

            YieldCurve curve = CurveBootstrapper.bootstrap(TODAY, List.of(deposit));

            assertThat(curve.pillars()).containsOnlyKeys(LocalDate.of(2024, 9, 30));
        }
    }

    @Nested
    @DisplayName("what the curve says about the market")
    class Shape {

        @Test
        @DisplayName("a curve fitted to one flat deposit is that deposit, converted")
        void singleDeposit() {
            // The only case with an answer known independently of the bootstrapper. A one-year
            // deposit at 5% simple accrues over its ACT/360 fraction to the adjusted maturity,
            // so its discount factor is 1 / (1 + 0.05 x tau) and the continuously-compounded
            // zero rate is -ln(DF) divided by the ACT/365F year fraction the curve measures.
            //
            // Both day counts appear here on purpose. Collapsing them to one - or hard-coding
            // 365/360 for a deposit whose maturity actually rolls two days over a weekend - is
            // the arithmetic slip this test exists to catch.
            DepositQuote deposit = DepositQuote.of(Tenor.years(1), 0.05);
            YieldCurve curve = CurveBootstrapper.bootstrap(TODAY, List.of(deposit));

            LocalDate maturity = deposit.pillarDate(TODAY);
            double accrual = DayCountConvention.ACT_360.yearFraction(TODAY, maturity);
            double discountFactor = 1.0 / (1.0 + 0.05 * accrual);
            double years = curve.timeTo(maturity);
            double expected = -Math.log(discountFactor) / years;

            assertThat(curve.zeroRate(years)).isCloseTo(expected, within(1e-10));
            assertThat(curve.zeroRate(years)).isLessThan(0.05);
        }

        @Test
        @DisplayName("an inverted market produces a falling curve")
        void invertedCurve() {
            // Short rates above long ones. Nothing in the bootstrapper assumes a shape, and a
            // solver that had quietly bounded its search would fail here.
            YieldCurve curve = CurveBootstrapper.bootstrap(TODAY, List.of(
                    DepositQuote.of(Tenor.months(6), 0.055),
                    ParSwapQuote.of(Tenor.years(2), 0.045),
                    ParSwapQuote.of(Tenor.years(10), 0.035)));

            assertThat(curve.zeroRate(9.0)).isLessThan(curve.zeroRate(1.0));
        }

        @Test
        @DisplayName("a negative-rate market bootstraps without complaint")
        void negativeRates() {
            // EUR and JPY have both traded through zero. Rejecting it would encode a market
            // condition as a validation rule - the same reasoning as MarketDataKey.DiscountRate.
            YieldCurve curve = CurveBootstrapper.bootstrap(TODAY, List.of(
                    DepositQuote.of(Tenor.months(6), -0.004),
                    ParSwapQuote.of(Tenor.years(5), -0.002)));

            assertThat(curve.zeroRate(3.0)).isNegative();
            assertThat(curve.discountFactor(3.0)).isGreaterThan(1.0);
        }

        @Test
        @DisplayName("a curve fitted to one rate everywhere comes out flat")
        void flatMarket() {
            // Three swaps at one par rate should give a curve with no term structure to speak
            // of. Not exactly flat in zero-rate terms - the par rate is a coupon-weighted
            // average and the day counts differ - but close enough that the shape is visibly
            // level rather than sloping.
            YieldCurve curve = CurveBootstrapper.bootstrap(TODAY, List.of(
                    ParSwapQuote.of(Tenor.years(2), 0.04),
                    ParSwapQuote.of(Tenor.years(5), 0.04),
                    ParSwapQuote.of(Tenor.years(10), 0.04)));

            assertThat(curve.zeroRate(9.0)).isCloseTo(curve.zeroRate(3.0), within(5e-4));
        }
    }

    @Nested
    @DisplayName("refusing to return a curve it cannot stand behind")
    class Refusals {

        @Test
        @DisplayName("an empty quote set is rejected")
        void noQuotes() {
            assertThatThrownBy(() -> CurveBootstrapper.bootstrap(TODAY, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one quote");
        }

        @Test
        @DisplayName("two quotes on the same date are rejected rather than one being dropped")
        void duplicatePillars() {
            assertThatThrownBy(() -> CurveBootstrapper.bootstrap(TODAY, List.of(
                    DepositQuote.of(Tenor.years(1), 0.05),
                    DepositQuote.of(Tenor.years(1), 0.06))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("both fix the pillar at");
        }
    }
}
