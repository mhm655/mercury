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
 * <h2>A bound can run out of sample before it runs out of band</h2>
 * The band is computed as a range of <em>ranks</em> around the point estimate's rank, and a
 * rank cannot go below 1 or above {@code n}. On a small sample it frequently wants to: ten
 * scenarios at 90% put the point estimate at rank 1 already, and the severe end of the band
 * asks for rank -1. Clamping it to 1 returns the worst observation - which is the point
 * estimate itself, so the interval comes back looking like {@code [5929.96, 14119.79]} with
 * its upper end silently equal to the number it was supposed to be bracketing.
 *
 * <p>Read as a two-sided band that is badly misleading: it says "the loss is at most
 * 14,119.79" when the truth is "this sample contains nothing worse, and a larger one very
 * likely would". So a clamped bound is flagged rather than quietly returned, and
 * {@link #toString} marks it with {@code >=} (or {@code <=} at the mild end). A bound limited
 * by the sample is not a bound, and the difference has to be visible at the point where
 * someone reads the number.
 *
 * <p>Immutable and thread-safe.
 */
public record QuantileConfidenceInterval(
        Money lowerBound,
        Money upperBound,
        boolean mildBoundAtSampleEdge,
        boolean severeBoundAtSampleEdge) {

    public QuantileConfidenceInterval {
        Objects.requireNonNull(lowerBound, "lowerBound");
        Objects.requireNonNull(upperBound, "upperBound");
        if (lowerBound.isGreaterThan(upperBound)) {
            throw new IllegalArgumentException(
                    "lowerBound (" + lowerBound + ") must not exceed upperBound (" + upperBound + ")");
        }
    }

    /** An interval whose two bounds both fell inside the sample. */
    public QuantileConfidenceInterval(Money lowerBound, Money upperBound) {
        this(lowerBound, upperBound, false, false);
    }

    /**
     * True if either end of the band was cut off by the size of the sample rather than by the
     * statistic - so the interval understates how uncertain the estimate really is.
     */
    public boolean isSampleLimited() {
        return mildBoundAtSampleEdge || severeBoundAtSampleEdge;
    }

    @Override
    public String toString() {
        return "[" + (mildBoundAtSampleEdge ? "<=" : "") + lowerBound
                + ", " + (severeBoundAtSampleEdge ? ">=" : "") + upperBound + "]";
    }
}
