package com.mercury.core.math;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Checks the root finder against roots that are known without it.
 *
 * <p>Square roots and logarithms have answers {@link Math} already knows, so the solver can
 * be held to an answer it played no part in producing. Testing a numerical method against
 * its own output is the classic way to write a suite that passes while the method is wrong.
 */
class RootFinderTest {

    private static final double TOLERANCE = 1e-10;

    @Nested
    @DisplayName("known roots")
    class KnownRoots {

        @Test
        @DisplayName("finds the square root of two")
        void squareRoot() {
            double root = RootFinder.bisect(x -> x * x - 2.0, 0.0, 2.0, TOLERANCE);

            assertThat(root).isCloseTo(Math.sqrt(2.0), within(1e-9));
        }

        @Test
        @DisplayName("works on a decreasing function as well as an increasing one")
        void decreasingFunction() {
            // The sign bookkeeping is the only part of bisection that is easy to get wrong,
            // and it is wrong in exactly one direction, so both are worth asserting.
            double root = RootFinder.bisect(x -> 2.0 - x * x, 0.0, 2.0, TOLERANCE);

            assertThat(root).isCloseTo(Math.sqrt(2.0), within(1e-9));
        }

        @Test
        @DisplayName("finds where the exponential crosses one half")
        void exponential() {
            double root = RootFinder.bisect(x -> Math.exp(-x) - 0.5, 0.0, 5.0, TOLERANCE);

            assertThat(root).isCloseTo(Math.log(2.0), within(1e-9));
        }

        @Test
        @DisplayName("a root sitting exactly on the bracket is returned as it stands")
        void rootAtTheEdge() {
            assertThat(RootFinder.bisect(x -> x, 0.0, 1.0, TOLERANCE)).isEqualTo(0.0);
            assertThat(RootFinder.bisect(x -> x - 1.0, 0.0, 1.0, TOLERANCE)).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("searching without a bracket")
    class BracketSearch {

        @Test
        @DisplayName("widens the bracket until it finds a sign change")
        void expandsFromTooNarrowAStart() {
            // The guess is nowhere near, and the initial width is far too small. The search
            // has to double its way out to the answer.
            double root = RootFinder.solve(x -> x - 37.0, 0.0, 1e-3, TOLERANCE);

            assertThat(root).isCloseTo(37.0, within(1e-8));
        }

        @Test
        @DisplayName("finds a root below the guess as readily as above it")
        void searchesBothDirections() {
            assertThat(RootFinder.solve(x -> x + 12.5, 0.0, 0.1, TOLERANCE))
                    .isCloseTo(-12.5, within(1e-8));
        }

        @Test
        @DisplayName("a guess that is already the answer is returned untouched")
        void guessIsTheRoot() {
            assertThat(RootFinder.solve(x -> x - 3.0, 3.0, 1.0, TOLERANCE)).isEqualTo(3.0);
        }

        @Test
        @DisplayName("rejects a non-positive initial width rather than looping forever")
        void rejectsBadWidth() {
            assertThatThrownBy(() -> RootFinder.solve(x -> x, 0.5, 0.0, TOLERANCE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("refusing to guess")
    class Refusals {

        @Test
        @DisplayName("a bracket with no sign change is rejected, not approximated")
        void noSignChange() {
            // x^2 + 1 has no real root. A solver that returned its closest approach would hand
            // back a number indistinguishable from an answer.
            assertThatThrownBy(() -> RootFinder.bisect(x -> x * x + 1.0, -1.0, 1.0, TOLERANCE))
                    .isInstanceOf(RootFinder.NoRootException.class)
                    .hasMessageContaining("No sign change");
        }

        @Test
        @DisplayName("a function with no root anywhere fails the widening search too")
        void noRootAnywhere() {
            assertThatThrownBy(() -> RootFinder.solve(x -> Math.exp(x) + 1.0, 0.0, 1.0, TOLERANCE))
                    .isInstanceOf(RootFinder.NoRootException.class);
        }

        @Test
        @DisplayName("a bracket containing an even number of roots is refused")
        void evenNumberOfRoots() {
            // (x-1)(x+1) is positive at both ends of [-2, 2] despite two roots inside. Bisection
            // cannot see them and says so, rather than reporting whichever it stumbled into.
            assertThatThrownBy(
                    () -> RootFinder.bisect(x -> x * x - 1.0, -2.0, 2.0, TOLERANCE))
                    .isInstanceOf(RootFinder.NoRootException.class);
        }
    }

    @Nested
    @DisplayName("accuracy")
    class Accuracy {

        @Test
        @DisplayName("a tighter tolerance really does give a closer answer")
        void toleranceIsHonoured() {
            DoubleUnaryOperator f = x -> x * x - 2.0;

            double loose = RootFinder.bisect(f, 0.0, 2.0, 1e-3);
            double tight = RootFinder.bisect(f, 0.0, 2.0, 1e-14);

            assertThat(Math.abs(f.applyAsDouble(tight)))
                    .isLessThan(Math.abs(f.applyAsDouble(loose)));
            assertThat(Math.abs(f.applyAsDouble(tight))).isLessThanOrEqualTo(1e-14);
        }
    }
}
