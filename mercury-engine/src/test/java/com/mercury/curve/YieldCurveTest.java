package com.mercury.curve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.mercury.core.time.Tenor;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds the curve to properties that are true of any correct term structure.
 *
 * <p>Most of these are relationships rather than numbers - a discount factor of one at time
 * zero, forward rates that compound back to the discount factors they came from, pillar
 * values reproduced exactly. Reference values matter too, but a curve is mostly defined by
 * the identities it has to satisfy, and those catch a class of error that spot-checking a
 * single number does not.
 */
class YieldCurveTest {

    private static final LocalDate TODAY = LocalDate.of(2024, 6, 28);
    private static final double TOLERANCE = 1e-12;

    /** An upward-sloping curve, which is the normal shape and the one that exposes slope bugs. */
    private static YieldCurve rising(Interpolation interpolation) {
        return YieldCurve.builder(TODAY)
                .interpolation(interpolation)
                .pillar(Tenor.months(6), 0.030)
                .pillar(Tenor.years(1), 0.035)
                .pillar(Tenor.years(5), 0.040)
                .pillar(Tenor.years(10), 0.042)
                .build();
    }

    @Nested
    @DisplayName("identities every curve must satisfy")
    class Identities {

        @Test
        @DisplayName("the discount factor at time zero is exactly one")
        void discountFactorAtZero() {
            // Money today is worth what it is worth. Holds whatever the near-end rate happens
            // to extrapolate to, because the exponent is zero either way.
            for (Interpolation scheme : Interpolation.values()) {
                assertThat(rising(scheme).discountFactor(0.0)).isEqualTo(1.0);
            }
        }

        @Test
        @DisplayName("the curve reproduces its own pillars exactly")
        void pillarsAreExact() {
            // Interpolation must be interpolation, not smoothing. A scheme that missed its own
            // inputs would silently reprice the very instruments the curve was built from.
            for (Interpolation scheme : Interpolation.values()) {
                YieldCurve curve = rising(scheme);
                curve.pillars().forEach((date, rate) ->
                        assertThat(curve.zeroRate(curve.timeTo(date)))
                                .isCloseTo(rate, within(TOLERANCE)));
            }
        }

        @Test
        @DisplayName("forward rates compound back to the discount factors they came from")
        void forwardsCompoundBack() {
            // DF(t2) = DF(t1) x e^(-f(t1,t2) x (t2-t1)). This is the definition of a forward
            // rate, and the single most useful invariant a curve has: it ties the two things
            // callers ask for together, so neither can drift from the other.
            for (Interpolation scheme : Interpolation.values()) {
                YieldCurve curve = rising(scheme);
                double t1 = 2.0;
                double t2 = 7.5;

                double forward = curve.forwardRate(t1, t2);
                double compounded = curve.discountFactor(t1) * Math.exp(-forward * (t2 - t1));

                assertThat(compounded).isCloseTo(curve.discountFactor(t2), within(1e-14));
            }
        }

        @Test
        @DisplayName("discount factors fall as the horizon lengthens, on a positive curve")
        void discountFactorsDecrease() {
            YieldCurve curve = rising(Interpolation.LOG_LINEAR_DISCOUNT);

            double previous = 1.0;
            for (double t = 0.25; t <= 15.0; t += 0.25) {
                double factor = curve.discountFactor(t);
                assertThat(factor).isLessThan(previous);
                previous = factor;
            }
        }
    }

    @Nested
    @DisplayName("interpolation schemes")
    class Schemes {

        @Test
        @DisplayName("log-linear discounting makes the forward rate constant between pillars")
        void flatForwardIsFlat() {
            // The defining property of the scheme, and the reason the market calls it
            // flat-forward. Asserted between the 5Y and 10Y pillars, where the gap is wide
            // enough that a non-constant forward would be obvious.
            YieldCurve curve = rising(Interpolation.LOG_LINEAR_DISCOUNT);

            double first = curve.forwardRate(5.1, 5.2);
            for (double t = 5.2; t < 9.9; t += 0.1) {
                assertThat(curve.forwardRate(t, t + 0.1)).isCloseTo(first, within(1e-12));
            }
        }

        @Test
        @DisplayName("linear zero interpolation does not, which is the trade-off")
        void linearZeroHasDriftingForwards() {
            // Stated as a test rather than only in prose: the two schemes are not
            // interchangeable, and the difference shows up in forwards long before it shows up
            // in a plot of the zero curve.
            YieldCurve curve = rising(Interpolation.LINEAR_ZERO);

            double near = curve.forwardRate(5.1, 5.2);
            double far = curve.forwardRate(9.7, 9.8);

            assertThat(far).isNotCloseTo(near, within(1e-6));
        }

        @Test
        @DisplayName("the schemes agree at pillars and disagree between them")
        void schemesDifferOnlyInTheGaps() {
            YieldCurve linear = rising(Interpolation.LINEAR_ZERO);
            YieldCurve logLinear = rising(Interpolation.LOG_LINEAR_DISCOUNT);

            double atPillar = linear.timeTo(Tenor.years(5).addTo(TODAY));
            assertThat(linear.zeroRate(atPillar))
                    .isCloseTo(logLinear.zeroRate(atPillar), within(TOLERANCE));

            assertThat(linear.zeroRate(3.0)).isNotCloseTo(logLinear.zeroRate(3.0), within(1e-9));
        }
    }

    @Nested
    @DisplayName("outside the pillars")
    class Extrapolation {

        @Test
        @DisplayName("the rate is held flat beyond both ends")
        void flatBeyondTheEnds() {
            YieldCurve curve = rising(Interpolation.LOG_LINEAR_DISCOUNT);

            assertThat(curve.zeroRate(0.01)).isCloseTo(0.030, within(TOLERANCE));
            assertThat(curve.zeroRate(0.1)).isCloseTo(0.030, within(TOLERANCE));
            assertThat(curve.zeroRate(30.0)).isCloseTo(0.042, within(TOLERANCE));
            assertThat(curve.zeroRate(100.0)).isCloseTo(0.042, within(TOLERANCE));
        }

        @Test
        @DisplayName("a rising curve does not keep rising forever")
        void slopeIsNotContinued() {
            // The reason flat extrapolation was chosen. The 5Y-10Y slope is 0.4bp per year;
            // continued to 100 years it would put the long end around 5.9%, a number no quote
            // in this curve supports. Flat says the only honest thing instead.
            YieldCurve curve = rising(Interpolation.LOG_LINEAR_DISCOUNT);

            double slopePerYear = (0.042 - 0.040) / 5.0;
            double ifSlopeContinued = 0.042 + slopePerYear * 90.0;

            assertThat(curve.zeroRate(100.0)).isCloseTo(0.042, within(TOLERANCE));
            assertThat(ifSlopeContinued).isGreaterThan(0.055);
        }
    }

    @Nested
    @DisplayName("the flat curve")
    class Flat {

        @Test
        @DisplayName("discounts exactly as e^-rt, matching the pricers that used a flat rate")
        void matchesClosedForm() {
            // The compatibility this type has to keep: before M5b every pricer discounted at
            // exp(-r x t) with one rate per currency. A one-pillar curve must be that, exactly,
            // or replacing the old path with this one would silently move every price.
            YieldCurve curve = YieldCurve.flat(TODAY, 0.045);

            for (double t : new double[] {0.5, 1.0, 4.96, 30.0}) {
                assertThat(curve.discountFactor(t))
                        .isCloseTo(Math.exp(-0.045 * t), within(1e-15));
            }
        }

        @Test
        @DisplayName("its forward rate is its zero rate at every horizon")
        void forwardEqualsZero() {
            YieldCurve curve = YieldCurve.flat(TODAY, 0.045);

            assertThat(curve.forwardRate(1.0, 7.0)).isCloseTo(0.045, within(1e-14));
            assertThat(curve.isFlat()).isTrue();
        }

        @Test
        @DisplayName("a multi-pillar curve at one rate is flat too")
        void manyPillarsOneRate() {
            YieldCurve curve = YieldCurve.builder(TODAY)
                    .pillar(Tenor.years(1), 0.02)
                    .pillar(Tenor.years(5), 0.02)
                    .build();

            assertThat(curve.isFlat()).isTrue();
            assertThat(curve.forwardRate(2.0, 3.0)).isCloseTo(0.02, within(1e-14));
        }
    }

    @Nested
    @DisplayName("immutability and construction")
    class Construction {

        @Test
        @DisplayName("withPillar leaves the original curve alone")
        void withPillarIsPure() {
            // The bootstrapper builds a curve one pillar at a time. If that mutated, none of
            // the intermediate states would be safe to hold, and a failed solve would leave a
            // half-built curve behind.
            YieldCurve original = YieldCurve.flat(TODAY, 0.03);

            YieldCurve extended = original.withPillar(Tenor.years(5).addTo(TODAY), 0.05);

            assertThat(original.size()).isEqualTo(1);
            assertThat(extended.size()).isEqualTo(2);
            assertThat(original.zeroRate(5.0)).isCloseTo(0.03, within(TOLERANCE));
        }

        @Test
        @DisplayName("replacing an existing pillar does not add a second one")
        void withPillarReplaces() {
            YieldCurve curve = YieldCurve.flat(TODAY, 0.03)
                    .withPillar(Tenor.years(1).addTo(TODAY), 0.06);

            assertThat(curve.size()).isEqualTo(1);
            assertThat(curve.zeroRate(1.0)).isCloseTo(0.06, within(TOLERANCE));
        }

        @Test
        @DisplayName("pillars come back in tenor order however they were added")
        void pillarsAreOrdered() {
            YieldCurve curve = YieldCurve.builder(TODAY)
                    .pillar(Tenor.years(10), 0.042)
                    .pillar(Tenor.months(6), 0.030)
                    .pillar(Tenor.years(1), 0.035)
                    .build();

            assertThat(curve.pillars().keySet()).containsExactly(
                    Tenor.months(6).addTo(TODAY),
                    Tenor.years(1).addTo(TODAY),
                    Tenor.years(10).addTo(TODAY));
        }

        @Test
        @DisplayName("an empty curve is refused rather than inventing rates")
        void emptyCurveRejected() {
            assertThatThrownBy(() -> YieldCurve.builder(TODAY).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one pillar");
        }

        @Test
        @DisplayName("tenors that compare equal but are not equal both survive")
        void nearlyEqualTenorsAreKept() {
            // 4W and 1M sort identically under the deliberately approximate ordering on Tenor,
            // which compares nominal length in days, but they resolve four days apart - so a
            // curve has to hold both.
            //
            // This mattered. The first version of YieldCurve sorted pillars into a TreeMap
            // keyed by Tenor, and TreeMap orders by compareTo rather than equals, so it merged
            // the two and built the curve from a quote the caller never gave. Storing pillars
            // by resolved date removes the hazard entirely: ordering on LocalDate agrees with
            // equality, so no two distinct keys can collapse into one.
            YieldCurve curve = YieldCurve.builder(TODAY)
                    .pillar(Tenor.weeks(4), 0.031)
                    .pillar(Tenor.months(1), 0.032)
                    .build();

            assertThat(curve.size()).isEqualTo(2);
            assertThat(curve.pillars()).containsKeys(
                    Tenor.weeks(4).addTo(TODAY), Tenor.months(1).addTo(TODAY));
        }

        @Test
        @DisplayName("a pillar at or before the reference date is refused")
        void pillarNotInTheFuture() {
            assertThatThrownBy(() -> YieldCurve.builder(TODAY).pillar(TODAY, 0.03).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not after the reference date");
        }
    }

    @Nested
    @DisplayName("refusing bad questions")
    class Refusals {

        @Test
        @DisplayName("a negative horizon is a caller error, not a discount factor above one")
        void negativeHorizon() {
            assertThatThrownBy(() -> YieldCurve.flat(TODAY, 0.03).zeroRate(-1.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a date before the curve is refused")
        void dateBeforeReference() {
            assertThatThrownBy(() -> YieldCurve.flat(TODAY, 0.03)
                    .discountFactor(TODAY.minusDays(1)))
                    .isInstanceOf(YieldCurve.DateBeforeCurveException.class);
        }

        @Test
        @DisplayName("a forward period that runs backwards is refused")
        void backwardsForward() {
            assertThatThrownBy(() -> YieldCurve.flat(TODAY, 0.03).forwardRate(5.0, 2.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly after");
        }
    }
}
