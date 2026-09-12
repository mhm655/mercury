package com.mercury.simulation;

import com.mercury.core.money.Money;
import java.util.Objects;

/**
 * Value at Risk and Expected Shortfall from one Monte Carlo run, alongside the path count
 * and seed that produced them - so a reader of the number also sees how much sampling stands
 * behind it, rather than a bare figure that looks exact regardless of whether it came from
 * 100 paths or 100,000.
 *
 * <h2>Why the seed travels with the result</h2>
 * {@code docs/DESIGN_PROPOSAL.md} section 7.2 names "injected clock, injected seed" as a
 * reproducibility requirement - the point of an injected seed is that a specific number can
 * be defended and reproduced later, which only works if whoever is looking at the number
 * later can find out which seed produced it. A seed known only to whoever happened to call
 * {@link MonteCarloVaRCalculator} does not actually deliver that: this record carries it so
 * the figure is self-describing rather than depending on someone's memory of how it was run.
 *
 * <p>Immutable and thread-safe.
 */
public record MonteCarloRiskResult(Money valueAtRisk, Money expectedShortfall, int pathCount, long seed) {

    public MonteCarloRiskResult {
        Objects.requireNonNull(valueAtRisk, "valueAtRisk");
        Objects.requireNonNull(expectedShortfall, "expectedShortfall");
        if (pathCount <= 0) {
            throw new IllegalArgumentException("pathCount must be positive, but was " + pathCount);
        }
    }
}
