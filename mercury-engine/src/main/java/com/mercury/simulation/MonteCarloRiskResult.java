package com.mercury.simulation;

import com.mercury.core.money.Money;
import java.util.Objects;

/**
 * Value at Risk and Expected Shortfall from one Monte Carlo run, alongside the path count
 * that produced them - so a reader of the number also sees how much sampling stands behind
 * it, rather than a bare figure that looks exact regardless of whether it came from 100
 * paths or 100,000.
 *
 * <p>Immutable and thread-safe.
 */
public record MonteCarloRiskResult(Money valueAtRisk, Money expectedShortfall, int pathCount) {

    public MonteCarloRiskResult {
        Objects.requireNonNull(valueAtRisk, "valueAtRisk");
        Objects.requireNonNull(expectedShortfall, "expectedShortfall");
        if (pathCount <= 0) {
            throw new IllegalArgumentException("pathCount must be positive, but was " + pathCount);
        }
    }
}
