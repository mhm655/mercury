package com.mercury.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class GeometricBrownianMotionTest {

    @Test
    void zeroYearsReturnsSpotExactly() {
        double value = GeometricBrownianMotion.terminalValue(
                100.0, 0.05, 0.20, 0.0, new SplittableRandom(1));

        assertThat(value).isEqualTo(100.0);
    }

    @Test
    void sameSeedGivesTheSameDraw() {
        double first = GeometricBrownianMotion.terminalValue(
                100.0, 0.05, 0.20, 1.0, new SplittableRandom(42));
        double second = GeometricBrownianMotion.terminalValue(
                100.0, 0.05, 0.20, 1.0, new SplittableRandom(42));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentSeedsGenerallyDrawDifferentValues() {
        double first = GeometricBrownianMotion.terminalValue(
                100.0, 0.05, 0.20, 1.0, new SplittableRandom(1));
        double second = GeometricBrownianMotion.terminalValue(
                100.0, 0.05, 0.20, 1.0, new SplittableRandom(2));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void theTerminalValueIsAlwaysPositive() {
        // exp(anything) > 0, always - GBM cannot produce a negative price, which is the whole
        // point of modelling log-returns rather than returns.
        SplittableRandom rng = new SplittableRandom(7);
        for (int i = 0; i < 10_000; i++) {
            double value = GeometricBrownianMotion.terminalValue(50.0, 0.03, 0.40, 2.0, rng);
            assertThat(value).isPositive();
        }
    }

    @Test
    void theSampleMeanConvergesToTheKnownAnalyticMean() {
        // E[S(T)] = S(0) * exp(mu * T) under GBM - a fact independent of this
        // implementation, so averaging many draws and comparing against it is a genuine
        // check, not the method testing itself.
        double spot = 100.0;
        double drift = 0.05;
        double volatility = 0.30;
        double years = 1.0;
        SplittableRandom rng = new SplittableRandom(123);

        double sum = 0.0;
        int paths = 200_000;
        for (int i = 0; i < paths; i++) {
            sum += GeometricBrownianMotion.terminalValue(spot, drift, volatility, years, rng);
        }
        double sampleMean = sum / paths;
        double analyticMean = spot * Math.exp(drift * years);

        // Monte Carlo standard error here is roughly spot * vol ~ 30, so +/- 0.5 over 200k
        // paths (error ~ 30/sqrt(200000) ~ 0.067) is a comfortably loose, non-flaky bound.
        assertThat(sampleMean).isCloseTo(analyticMean, within(0.5));
    }

    @Test
    void rejectsNonPositiveSpot() {
        assertThatThrownBy(() -> GeometricBrownianMotion.terminalValue(
                0.0, 0.05, 0.20, 1.0, new SplittableRandom(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeVolatility() {
        assertThatThrownBy(() -> GeometricBrownianMotion.terminalValue(
                100.0, 0.05, -0.01, 1.0, new SplittableRandom(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeYears() {
        assertThatThrownBy(() -> GeometricBrownianMotion.terminalValue(
                100.0, 0.05, 0.20, -1.0, new SplittableRandom(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
