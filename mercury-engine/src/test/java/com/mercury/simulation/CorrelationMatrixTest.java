package com.mercury.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class CorrelationMatrixTest {

    @Test
    void anIdentityMatrixHasAnIdentityCholeskyFactor() {
        CorrelationMatrix identity = CorrelationMatrix.of(new double[][] {
                {1.0, 0.0}, {0.0, 1.0}});

        double[][] lower = identity.choleskyLower();

        assertThat(lower[0][0]).isCloseTo(1.0, within(1e-12));
        assertThat(lower[0][1]).isCloseTo(0.0, within(1e-12));
        assertThat(lower[1][0]).isCloseTo(0.0, within(1e-12));
        assertThat(lower[1][1]).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void theCholeskyFactorReproducesTheOriginalMatrix() {
        // L * L^T must equal the original correlation matrix - the definition of the
        // decomposition, checked directly rather than trusted from the formula alone.
        CorrelationMatrix matrix = CorrelationMatrix.of(new double[][] {
                {1.0, 0.6, 0.2},
                {0.6, 1.0, 0.3},
                {0.2, 0.3, 1.0}});

        double[][] l = matrix.choleskyLower();
        double[][] reconstructed = multiplyByTranspose(l);

        double[][] original = {{1.0, 0.6, 0.2}, {0.6, 1.0, 0.3}, {0.2, 0.3, 1.0}};
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertThat(reconstructed[i][j]).isCloseTo(original[i][j], within(1e-9));
            }
        }
    }

    @Test
    void choleskyLowerReturnsADefensiveCopy() {
        CorrelationMatrix matrix = CorrelationMatrix.of(new double[][] {
                {1.0, 0.5}, {0.5, 1.0}});

        double[][] first = matrix.choleskyLower();
        first[0][0] = 999.0;
        double[][] second = matrix.choleskyLower();

        assertThat(second[0][0]).isNotEqualTo(999.0);
    }

    @Test
    void rejectsANonSquareMatrix() {
        assertThatThrownBy(() -> CorrelationMatrix.of(new double[][] {
                {1.0, 0.5}, {0.5, 1.0, 0.1}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("square");
    }

    @Test
    void rejectsANonSymmetricMatrix() {
        assertThatThrownBy(() -> CorrelationMatrix.of(new double[][] {
                {1.0, 0.5}, {0.4, 1.0}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symmetric");
    }

    @Test
    void rejectsADiagonalEntryThatIsNotOne() {
        assertThatThrownBy(() -> CorrelationMatrix.of(new double[][] {
                {1.0, 0.5}, {0.5, 0.9}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 1.0");
    }

    @Test
    void rejectsAnOffDiagonalEntryAboveOne() {
        assertThatThrownBy(() -> CorrelationMatrix.of(new double[][] {
                {1.0, 1.5}, {1.5, 1.0}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[-1, 1]");
    }

    @Test
    void rejectsANonFiniteEntry() {
        assertThatThrownBy(() -> CorrelationMatrix.of(new double[][] {
                {1.0, Double.NaN}, {Double.NaN, 1.0}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
    }

    @Test
    void rejectsAMatrixThatIsNotPositiveDefinite() {
        // Three pairwise correlations that cannot jointly hold: A and B almost perfectly
        // positively correlated, B and C almost perfectly positively correlated, but A and C
        // almost perfectly negatively correlated. No real set of variables can satisfy all
        // three at once.
        assertThatThrownBy(() -> CorrelationMatrix.of(new double[][] {
                {1.0, 0.9, -0.9},
                {0.9, 1.0, 0.9},
                {-0.9, 0.9, 1.0}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not positive definite");
    }

    @Test
    void rejectsAnExactPairwiseCorrelationAsSingular() {
        // Mathematically valid (positive semi-definite) but singular - this class only
        // attempts a plain Cholesky decomposition, which cannot handle a zero pivot before the
        // last row/column, so it is rejected rather than risking a NaN shock later.
        assertThatThrownBy(() -> CorrelationMatrix.of(new double[][] {
                {1.0, 1.0, 0.2},
                {1.0, 1.0, 0.2},
                {0.2, 0.2, 1.0}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not positive definite");
    }

    @Test
    void rejectsAnEmptyMatrix() {
        assertThatThrownBy(() -> CorrelationMatrix.of(new double[0][0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sizeReportsTheNumberOfFactors() {
        CorrelationMatrix matrix = CorrelationMatrix.of(new double[][] {
                {1.0, 0.1, 0.2},
                {0.1, 1.0, 0.3},
                {0.2, 0.3, 1.0}});

        assertThat(matrix.size()).isEqualTo(3);
    }

    private static double[][] multiplyByTranspose(double[][] l) {
        int n = l.length;
        double[][] result = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double sum = 0.0;
                for (int k = 0; k < n; k++) {
                    sum += l[i][k] * l[j][k];
                }
                result[i][j] = sum;
            }
        }
        return result;
    }
}
