package com.mercury.risk;

import com.mercury.core.money.Money;
import java.util.Objects;

/**
 * A band around a quantile estimate - {@code lowerBound} and {@code upperBound} are both
 * loss figures in the same sense {@link HistoricalVaRCalculator#valueAtRisk} reports one, so
 * {@code lowerBound <= upperBound} always (the milder plausible estimate is smaller than the
 * more severe one, not the other way around).
 *
 * <h2>Two different meanings of "confidence" - do not conflate them</h2>
 * {@code HistoricalVaRCalculator.valueAtRisk}'s own {@code confidenceLevel} parameter (e.g.
 * 99%) answers "what fraction of scenarios lost no more than this." This interval answers a
 * completely different question: "how much would the 99% VaR estimate itself have moved if a
 * different, equally-sized sample of scenarios had been drawn" - the interval is fixed at
 * {@value HistoricalVaRCalculator#CONFIDENCE_INTERVAL_LEVEL} regardless of what VaR confidence
 * level produced the point estimate it brackets. A VaR of 99% confidence with a wide interval
 * here is a perfectly sensible, honest combination - it means "no more than 1% of scenarios
 * lost more than this, but exactly how much more is itself uncertain from this sample size."
 *
 * <p>Immutable and thread-safe.
 */
public record QuantileConfidenceInterval(Money lowerBound, Money upperBound) {

    public QuantileConfidenceInterval {
        Objects.requireNonNull(lowerBound, "lowerBound");
        Objects.requireNonNull(upperBound, "upperBound");
        if (lowerBound.isGreaterThan(upperBound)) {
            throw new IllegalArgumentException(
                    "lowerBound (" + lowerBound + ") must not exceed upperBound (" + upperBound + ")");
        }
    }

    @Override
    public String toString() {
        return "[" + lowerBound + ", " + upperBound + "]";
    }
}
