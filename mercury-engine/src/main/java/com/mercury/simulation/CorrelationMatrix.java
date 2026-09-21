package com.mercury.simulation;

import java.util.Arrays;

/**
 * A validated correlation structure across several risk factors, and the Cholesky factor a
 * correlated Monte Carlo draw needs from it.
 *
 * <h2>Invariants on the type that owns them</h2>
 * A correlation matrix is not just any square array of doubles: it must be symmetric, every
 * diagonal entry must be exactly 1 (a factor is perfectly correlated with itself), every
 * off-diagonal entry must lie in {@code [-1, 1]}, and - the check the other three cannot
 * substitute for - the whole matrix must be positive <em>definite</em>, or no set of jointly
 * consistent random variables with genuinely independent components could actually produce it.
 * Checked once here, at construction, the same "reject a value the type itself cannot take"
 * discipline {@code MarketDataKey} already follows, rather than trusting every caller to have
 * checked by hand.
 *
 * <h2>Definite, not merely semi-definite</h2>
 * A singular matrix - one exact {@code +1} or {@code -1} pairwise correlation is the simplest
 * example - is positive <em>semi</em>-definite but not positive definite: mathematically valid,
 * but its Cholesky factor has a zero on the diagonal, and dividing a later row by that zero
 * (the standard algorithm's next step) produces {@code NaN} rather than a number. Handling that
 * correctly needs a pivoted or rank-revealing decomposition this class does not attempt, so a
 * singular matrix is rejected rather than silently producing {@code NaN} shocks three steps
 * later inside a Monte Carlo path - "loud beats plausible," the same rule
 * {@code MarketDataSnapshot} and {@code SwapModel} already follow for missing or invalid data.
 * A caller that genuinely needs an exact {@code +1}/{@code -1} pairwise correlation can express
 * it directly as one shared risk factor instead of two correlated ones.
 *
 * <h2>Why Cholesky, computed once and cached</h2>
 * {@link #choleskyLower()} is the standard way to turn independent standard normals into
 * correlated ones: draw {@code n} independent {@code Z}, multiply by the lower-triangular
 * factor {@code L} where {@code L * L^T} equals this matrix, and {@code L * Z} is a draw from
 * the correlated joint distribution. A matrix that is not positive definite has no such factor
 * - the decomposition hits a non-positive number under a square root - which is exactly the
 * signal used here to reject one, rather than a separate definiteness check duplicating the
 * same arithmetic. Computed once at construction rather than per path: a
 * {@code CorrelatedMonteCarloVaRCalculator} run draws the factor tens of thousands of times, and
 * it depends only on this matrix, never on a single path's draw.
 *
 * <p>Immutable and thread-safe.
 */
public final class CorrelationMatrix {

    private final double[][] correlations;
    private final double[][] choleskyLower;

    private CorrelationMatrix(double[][] correlations, double[][] choleskyLower) {
        this.correlations = correlations;
        this.choleskyLower = choleskyLower;
    }

    /**
     * @param correlations a square, symmetric matrix with a unit diagonal and every
     *                      off-diagonal entry in {@code [-1, 1]}
     * @throws IllegalArgumentException if {@code correlations} is not square, not symmetric,
     *                                  has a diagonal entry other than exactly {@code 1.0}, has
     *                                  an off-diagonal entry outside {@code [-1, 1]}, contains a
     *                                  non-finite value, or is not positive definite - including
     *                                  the singular case (an exact {@code +1}/{@code -1}
     *                                  pairwise correlation), which this class deliberately does
     *                                  not attempt to decompose - see the class javadoc
     */
    public static CorrelationMatrix of(double[][] correlations) {
        double[][] copy = validate(correlations);
        double[][] cholesky = cholesky(copy);
        return new CorrelationMatrix(copy, cholesky);
    }

    /** How many risk factors this matrix correlates. */
    public int size() {
        return correlations.length;
    }

    /**
     * The lower-triangular factor {@code L} such that {@code L * L^T} equals this matrix -
     * computed once at construction, returned as a defensive copy on every call since arrays
     * are mutable and this type is meant to be immutable.
     */
    public double[][] choleskyLower() {
        double[][] copy = new double[choleskyLower.length][];
        for (int i = 0; i < choleskyLower.length; i++) {
            copy[i] = choleskyLower[i].clone();
        }
        return copy;
    }

    private static double[][] validate(double[][] correlations) {
        if (correlations == null || correlations.length == 0) {
            throw new IllegalArgumentException("correlations must be a non-empty square matrix");
        }
        int size = correlations.length;
        double[][] copy = new double[size][];
        for (int i = 0; i < size; i++) {
            if (correlations[i] == null || correlations[i].length != size) {
                throw new IllegalArgumentException(
                        "correlations must be square: row " + i + " has "
                                + (correlations[i] == null ? 0 : correlations[i].length)
                                + " entries, expected " + size);
            }
            copy[i] = correlations[i].clone();
        }
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                double value = copy[i][j];
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException(
                            "correlations[" + i + "][" + j + "] must be finite, but was " + value);
                }
                if (i == j) {
                    if (value != 1.0) {
                        throw new IllegalArgumentException(
                                "correlations[" + i + "][" + i + "] (a factor's correlation with "
                                        + "itself) must be exactly 1.0, but was " + value);
                    }
                } else {
                    if (value < -1.0 || value > 1.0) {
                        throw new IllegalArgumentException(
                                "correlations[" + i + "][" + j + "] must be in [-1, 1], but was " + value);
                    }
                    if (value != copy[j][i]) {
                        throw new IllegalArgumentException(
                                "correlations must be symmetric: [" + i + "][" + j + "]=" + value
                                        + " but [" + j + "][" + i + "]=" + copy[j][i]);
                    }
                }
            }
        }
        return copy;
    }

    /**
     * Standard Cholesky-Banachiewicz decomposition: {@code L[i][j]} for {@code j <= i}, row by
     * row, each entry needing only entries already computed above and to its left.
     */
    private static double[][] cholesky(double[][] matrix) {
        int size = matrix.length;
        double[][] lower = new double[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0.0;
                for (int k = 0; k < j; k++) {
                    sum += lower[i][k] * lower[j][k];
                }
                if (i == j) {
                    double underRoot = matrix[i][i] - sum;
                    if (!(underRoot > 0.0)) {
                        throw new IllegalArgumentException(
                                "correlations is not positive definite: either no jointly consistent "
                                        + "set of correlated variables could produce this matrix, or it "
                                        + "is exactly singular (e.g. an exact +1/-1 pairwise correlation) "
                                        + "and needs a decomposition this class does not attempt - see "
                                        + "the class javadoc (failed at row " + i + ", "
                                        + Arrays.toString(matrix[i]) + ")");
                    }
                    lower[i][j] = Math.sqrt(underRoot);
                } else {
                    lower[i][j] = (matrix[i][j] - sum) / lower[j][j];
                }
            }
        }
        return lower;
    }
}
