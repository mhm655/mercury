package com.mercury.risk;

import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.marketdata.MarketShock;
import com.mercury.portfolio.Portfolio;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Value at Risk and Expected Shortfall by historical simulation: revalue the portfolio under
 * each of a set of actual historical daily moves, and report a percentile of the resulting
 * P&amp;L distribution as the loss.
 *
 * <h2>One mechanism, another feature</h2>
 * {@link SensitivityCalculator#valueChangeUnder} already does "shock the market, revalue,
 * report the difference" - the primitive {@code docs/DESIGN_PROPOSAL.md} section 5.3 built
 * for stress testing and Greeks. Historical VaR is a third use of exactly that primitive,
 * repeated over many shocks instead of one: no new revaluation machinery, only a percentile
 * over the results. M12's {@code com.mercury.simulation.MonteCarloVaRCalculator} is a fourth:
 * it generates its scenario list by simulation instead of supplying historical ones, and
 * delegates the percentile math here rather than reimplementing it - Monte Carlo VaR and
 * historical VaR differ only in where the scenario list comes from.
 *
 * <h2>Historical, not Monte Carlo</h2>
 * This class is a genuinely different technique from Monte Carlo simulation, not an early
 * duplicate of it - only the percentile statistic is shared (see above). Historical
 * simulation uses moves that actually happened - each {@link MarketShock} here should
 * represent one real historical day's observed change - so it needs no distributional
 * assumption about returns at all, at the cost of only ever seeing scenarios that occurred
 * in the sampled history. Monte Carlo instead simulates paths from an assumed process, which
 * can explore combinations of moves history never happened to produce.
 *
 * <h2>No historical-data loader</h2>
 * Scenarios are supplied by the caller as {@link MarketShock}s, not fetched from anywhere.
 * This engine has no live or historical market-data feed anywhere - every
 * {@link MarketDataSnapshot} in Mercury is already caller-constructed - so building a loader
 * for real historical series would be new infrastructure this class has no need to own. See
 * {@code docs/KNOWN_GAPS.md}.
 *
 * <h2>The percentile convention, worked by hand</h2>
 * Standard historical-simulation VaR: sort the scenario P&amp;Ls ascending (worst first) and
 * take the observation at rank {@code k = ceil((1 - confidenceLevel) * n)}, counting from the
 * worst end and clamped to {@code [1, n]}. Ten scenarios at 90% confidence gives
 * {@code k = ceil(0.10 * 10) = 1} - the single worst observation is the VaR. A hundred
 * scenarios at 95% confidence gives {@code k = 5} - the fifth-worst. VaR is reported as a
 * positive loss, {@code max(0, -thatPnL)}: if even the worst-at-that-confidence outcome was a
 * gain, there is no loss to report at that confidence level.
 *
 * <h2>Expected Shortfall, M12</h2>
 * Also called Conditional VaR: the average of every scenario at least as bad as the VaR
 * threshold (ranks {@code 1..k}), rather than just the threshold observation itself. Two
 * facts fall out of that definition rather than needing separate proof: Expected Shortfall
 * is always at least as large as VaR at the same confidence (an average of a tail that
 * includes the boundary observation cannot be milder than the boundary alone), and it is
 * more sensitive to the shape of the tail beyond the threshold, which is precisely the
 * "how bad is the plausible worst case" question VaR alone does not answer.
 *
 * <h2>How precise is the VaR estimate itself?</h2>
 * {@link #valueAtRiskConfidenceInterval} answers a question {@link #valueAtRisk} alone
 * cannot: how much would this same figure have moved if a different, equally-sized sample of
 * scenarios had been drawn? A VaR computed from 30 historical days carries a much wider band
 * than one computed from 2,000 Monte Carlo paths, and a bare point estimate does not say so.
 * See that method's javadoc for the technique and why it costs nothing extra to compute.
 *
 * <p>Stateless and thread-safe, given a thread-safe {@link SensitivityCalculator}.
 */
public final class HistoricalVaRCalculator {

    /**
     * The confidence level {@link #valueAtRiskConfidenceInterval} brackets - a separate
     * question from a caller's own VaR {@code confidenceLevel} parameter, fixed rather than
     * configurable to keep the two from ever being confused for the same knob. See
     * {@link QuantileConfidenceInterval}'s javadoc for why they mean different things.
     */
    public static final double CONFIDENCE_INTERVAL_LEVEL = 0.95;

    /** The two-sided z-score for {@link #CONFIDENCE_INTERVAL_LEVEL}. */
    private static final double CONFIDENCE_INTERVAL_Z = 1.959964;

    /**
     * Bootstrap resamples {@link #expectedShortfallConfidenceInterval} draws. A magic number,
     * named rather than left bare: large enough that the percentile estimate itself does not
     * add meaningful noise on top of the historical scenario sample's own uncertainty, small
     * enough to run in milliseconds against the hundreds-to-low-thousands of scenarios this
     * class is actually called with - see that method's javadoc for why this class does not
     * parallelise the resampling.
     */
    private static final int BOOTSTRAP_RESAMPLES = 1000;

    private final SensitivityCalculator sensitivities;

    public HistoricalVaRCalculator(SensitivityCalculator sensitivities) {
        this.sensitivities = Objects.requireNonNull(sensitivities, "sensitivities");
    }

    /**
     * The loss such that, across {@code historicalScenarios}, no worse than
     * {@code confidenceLevel} of them lost more - see the class javadoc for the exact
     * percentile convention.
     *
     * @param historicalScenarios one shock per historical day; order does not matter, it is
     *                            sorted internally
     * @param confidenceLevel strictly between 0 and 1, e.g. {@code 0.95} for 95%
     * @throws IllegalArgumentException if {@code historicalScenarios} is empty or
     *                                  {@code confidenceLevel} is not strictly between 0 and 1
     */
    public Money valueAtRisk(Portfolio portfolio, List<MarketShock> historicalScenarios,
                             MarketDataSnapshot market, LocalDate asOf, double confidenceLevel) {
        return valueAtRisk(rank(portfolio, historicalScenarios, market, asOf, confidenceLevel));
    }

    /**
     * {@link #valueAtRisk}, {@link #expectedShortfall} and {@link #valueAtRiskConfidenceInterval}
     * together, from a single revaluation of every scenario.
     *
     * <p>Revaluation is the whole cost of this class - sorting and reading three positions is
     * nothing beside it - so a caller wanting more than one of the three should ask here
     * rather than calling each method and paying for the revaluation each time.
     *
     * @throws IllegalArgumentException if {@code historicalScenarios} is empty or
     *                                  {@code confidenceLevel} is not strictly between 0 and 1
     */
    public Measures measure(Portfolio portfolio, List<MarketShock> historicalScenarios,
                            MarketDataSnapshot market, LocalDate asOf, double confidenceLevel) {
        RankedScenarios ranked = rank(portfolio, historicalScenarios, market, asOf, confidenceLevel);
        return new Measures(valueAtRisk(ranked), expectedShortfall(ranked), confidenceInterval(ranked));
    }

    /**
     * {@link #measure} over P&amp;Ls a caller has already revalued, rather than scenarios this
     * class revalues itself.
     *
     * <p>For M13's parallel Monte Carlo, which revalues its paths across worker threads and
     * needs only the statistics from here. Taking the finished P&amp;Ls keeps the percentile
     * math in this one class while the revaluation happens wherever it is fastest - this class
     * stays single-threaded and knows nothing about workers.
     *
     * @param profitAndLosses one P&amp;L per scenario, all in one currency; order does not
     *                        matter, it is sorted internally
     * @throws IllegalArgumentException if {@code profitAndLosses} is empty or
     *                                  {@code confidenceLevel} is not strictly between 0 and 1
     */
    public Measures measure(List<Money> profitAndLosses, double confidenceLevel) {
        Objects.requireNonNull(profitAndLosses, "profitAndLosses");
        requireScenariosAndConfidence(profitAndLosses.isEmpty(), confidenceLevel);
        RankedScenarios ranked = rank(profitAndLosses, confidenceLevel);
        return new Measures(valueAtRisk(ranked), expectedShortfall(ranked), confidenceInterval(ranked));
    }

    /** The three statistics {@link #measure} computes from one ranking. */
    public record Measures(Money valueAtRisk, Money expectedShortfall,
                           QuantileConfidenceInterval valueAtRiskConfidenceInterval) {

        public Measures {
            Objects.requireNonNull(valueAtRisk, "valueAtRisk");
            Objects.requireNonNull(expectedShortfall, "expectedShortfall");
            Objects.requireNonNull(valueAtRiskConfidenceInterval, "valueAtRiskConfidenceInterval");
        }
    }

    private static Money valueAtRisk(RankedScenarios ranked) {
        return lossOrZero(ranked.sorted().get(ranked.rank() - 1));
    }

    /**
     * The average loss across every scenario at least as bad as the {@link #valueAtRisk}
     * threshold - see the class javadoc for why this is always at least as large as VaR.
     *
     * @param historicalScenarios one shock per historical day; order does not matter, it is
     *                            sorted internally
     * @param confidenceLevel strictly between 0 and 1, e.g. {@code 0.95} for 95%
     * @throws IllegalArgumentException if {@code historicalScenarios} is empty or
     *                                  {@code confidenceLevel} is not strictly between 0 and 1
     */
    public Money expectedShortfall(Portfolio portfolio, List<MarketShock> historicalScenarios,
                                   MarketDataSnapshot market, LocalDate asOf, double confidenceLevel) {
        return expectedShortfall(rank(portfolio, historicalScenarios, market, asOf, confidenceLevel));
    }

    private static Money expectedShortfall(RankedScenarios ranked) {
        List<Money> tail = ranked.sorted().subList(0, ranked.rank());
        Money sum = tail.stream().reduce(Money.zero(tail.get(0).currency()), Money::plus);
        Money average = sum.dividedBy(BigDecimal.valueOf(tail.size()));
        return lossOrZero(average);
    }

    /**
     * A {@value #CONFIDENCE_INTERVAL_LEVEL}-confidence band around {@link #valueAtRisk}'s own
     * estimate - not a second, more precise number, but a statement of how much the first one
     * should be trusted.
     *
     * <h2>The method: rank uncertainty, not resampling</h2>
     * {@code valueAtRisk} is one order statistic - the {@code k}-th smallest of {@code n}
     * scenarios. Standard large-sample theory treats the <em>count</em> of scenarios at or
     * below the true population quantile as Binomial({@code n}, {@code p}) where
     * {@code p = k / n}, which is approximately Normal with standard deviation
     * {@code sqrt(n p (1 - p))}. That gives a band of plausible <em>ranks</em> around
     * {@code k} directly - {@code k +/- z * sqrt(n p (1 - p))} - which this maps straight onto
     * two more positions in the P&amp;L list already sorted for {@code valueAtRisk}. No
     * resampling loop and no bootstrap. Called on its own this still revalues every scenario;
     * asked for through {@link #measure} alongside the point estimate, it reads two more
     * entries from the list the point estimate already sorted, and costs nothing further.
     *
     * <p>Distribution-free, matching the rest of this class: nothing here assumes returns are
     * Normal, only that a count of successes among many trials is - a much weaker and more
     * defensible assumption, and the reason a bootstrap (which needs no distributional
     * assumption at all, at the cost of genuinely repeating the ranking {@code B} times) was
     * considered and set aside here. See {@code docs/KNOWN_GAPS.md}.
     *
     * <p>Narrower with more scenarios, as it should be: relative to the loss scale, more data
     * pins the quantile down more tightly, even though the absolute rank band
     * ({@code sqrt(n p (1-p))}) grows with {@code n} - what shrinks is how far apart two
     * neighbouring ranks are in P&amp;L terms, not the rank count itself.
     *
     * @throws IllegalArgumentException if {@code historicalScenarios} is empty or
     *                                  {@code confidenceLevel} is not strictly between 0 and 1
     */
    public QuantileConfidenceInterval valueAtRiskConfidenceInterval(
            Portfolio portfolio, List<MarketShock> historicalScenarios, MarketDataSnapshot market,
            LocalDate asOf, double confidenceLevel) {
        return confidenceInterval(rank(portfolio, historicalScenarios, market, asOf, confidenceLevel));
    }

    private static QuantileConfidenceInterval confidenceInterval(RankedScenarios ranked) {
        int n = ranked.sorted().size();
        double p = (double) ranked.rank() / n;
        double rankStandardError = Math.sqrt(n * p * (1.0 - p));

        int wantedLower = (int) Math.round(ranked.rank() - CONFIDENCE_INTERVAL_Z * rankStandardError);
        int wantedUpper = (int) Math.round(ranked.rank() + CONFIDENCE_INTERVAL_Z * rankStandardError);
        int lowerRank = clampRank(wantedLower, n);
        int upperRank = clampRank(wantedUpper, n);

        // Ascending sort: a smaller rank is a worse (more negative) P&L, so it is the larger
        // loss and the upper bound of the interval; a larger rank is milder and the lower
        // bound. Not swapped - the names refer to the loss magnitude, not the rank order.
        Money milder = lossOrZero(ranked.sorted().get(upperRank - 1));
        Money worse = lossOrZero(ranked.sorted().get(lowerRank - 1));

        // Whether the band wanted a rank the sample does not have. Ten scenarios at 90% put
        // the point estimate at rank 1, so the severe end asks for rank -1 and gets clamped
        // back onto the point estimate - an "upper bound" equal to the number it brackets.
        // Returning that unmarked reads as "the loss is at most this" when it means "this
        // sample holds nothing worse". See QuantileConfidenceInterval.
        return new QuantileConfidenceInterval(milder, worse, wantedUpper > n, wantedLower < 1);
    }

    /**
     * A {@value #CONFIDENCE_INTERVAL_LEVEL}-confidence band around {@link #expectedShortfall}'s
     * own estimate - the same question {@link #valueAtRiskConfidenceInterval} answers for VaR,
     * asked of Expected Shortfall instead.
     *
     * <h2>The method: bootstrap, not rank uncertainty</h2>
     * {@code valueAtRisk} is one order statistic, so its confidence interval falls out of the
     * rank's own sampling distribution - no resampling needed, see
     * {@link #valueAtRiskConfidenceInterval}'s javadoc. Expected Shortfall averages a whole
     * tail of variable size, not one order statistic, so that trick does not carry over: there
     * is no single rank whose uncertainty this could be derived from. A bootstrap needs no
     * distributional assumption either, which is why it was "considered and set aside" for VaR
     * only because the cheaper rank trick existed there - for Expected Shortfall it does not,
     * so the trade-off resolves the other way here.
     *
     * <p>Resamples the scenario P&amp;Ls with replacement {@value #BOOTSTRAP_RESAMPLES} times,
     * computes Expected Shortfall on each resample via the same {@link #expectedShortfall}
     * this class's point estimate uses, and reports the
     * {@value #CONFIDENCE_INTERVAL_LEVEL}-percentile band of the resulting distribution -
     * the same fixed meta-confidence level {@link #valueAtRiskConfidenceInterval} uses, and
     * for the identical reason: keeping it distinct from a caller's own {@code confidenceLevel}
     * so the two are never confused for the same knob.
     *
     * <h2>Reproducibility</h2>
     * Bootstrap resampling needs randomness this class otherwise has none of, so every overload
     * takes an explicit {@code seed} - the same convention
     * {@code com.mercury.simulation.MonteCarloVaRCalculator} already uses, and required by the
     * same project-wide rule: the same seed against the same scenarios gives the same interval,
     * bit for bit, regardless of when or how many times it is called.
     *
     * <h2>Sequential, not parallel - a stated scope choice</h2>
     * Historical scenario counts are hundreds to low thousands, nothing like Monte Carlo's
     * tens of thousands of paths, so {@value #BOOTSTRAP_RESAMPLES} resamples of a list this
     * size runs in milliseconds on one thread. Reaching for
     * {@code com.mercury.simulation.SimulationWorkers} would be new cross-package coupling for
     * a benefit nothing has measured a need for - the same restraint this project applies
     * elsewhere rather than machinery built ahead of a caller that needs it.
     *
     * <p>Unlike {@link #valueAtRiskConfidenceInterval}'s bound, a bootstrap percentile index
     * into {@value #BOOTSTRAP_RESAMPLES} resamples is always well-defined, so
     * {@link QuantileConfidenceInterval#mildBoundAtSampleEdge} and
     * {@link QuantileConfidenceInterval#severeBoundAtSampleEdge} are always {@code false} here
     * - those flags describe VaR's rank-clamping edge case specifically, which has no analogue
     * in a bootstrap distribution of fixed size.
     *
     * @throws IllegalArgumentException if {@code historicalScenarios} is empty or
     *                                  {@code confidenceLevel} is not strictly between 0 and 1
     */
    public QuantileConfidenceInterval expectedShortfallConfidenceInterval(
            Portfolio portfolio, List<MarketShock> historicalScenarios, MarketDataSnapshot market,
            LocalDate asOf, double confidenceLevel, long seed) {
        return expectedShortfallConfidenceInterval(
                rank(portfolio, historicalScenarios, market, asOf, confidenceLevel), seed);
    }

    private static QuantileConfidenceInterval expectedShortfallConfidenceInterval(
            RankedScenarios ranked, long seed) {
        List<Money> sorted = ranked.sorted();
        int n = sorted.size();
        SplittableRandom random = new SplittableRandom(seed);

        List<Money> bootstrapEstimates = new ArrayList<>(BOOTSTRAP_RESAMPLES);
        for (int b = 0; b < BOOTSTRAP_RESAMPLES; b++) {
            List<Money> resample = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                resample.add(sorted.get(random.nextInt(n)));
            }
            resample.sort(null);
            bootstrapEstimates.add(expectedShortfall(new RankedScenarios(resample, ranked.rank())));
        }
        bootstrapEstimates.sort(null);

        int lowerIndex = percentileIndex((1.0 - CONFIDENCE_INTERVAL_LEVEL) / 2.0, BOOTSTRAP_RESAMPLES);
        int upperIndex = percentileIndex(1.0 - (1.0 - CONFIDENCE_INTERVAL_LEVEL) / 2.0, BOOTSTRAP_RESAMPLES);
        Money milder = bootstrapEstimates.get(lowerIndex);
        Money worse = bootstrapEstimates.get(upperIndex);
        return new QuantileConfidenceInterval(milder, worse, false, false);
    }

    private static int percentileIndex(double percentile, int size) {
        int index = (int) Math.floor(percentile * size);
        return Math.max(0, Math.min(index, size - 1));
    }

    private static int clampRank(int rank, int n) {
        return Math.max(1, Math.min(rank, n));
    }

    private static Money lossOrZero(Money profitOrLoss) {
        return profitOrLoss.isNegative() ? profitOrLoss.negated() : Money.zero(profitOrLoss.currency());
    }

    /**
     * Validates, revalues every scenario, sorts the resulting P&amp;Ls ascending, and
     * computes the rank both {@link #valueAtRisk} and {@link #expectedShortfall} read from -
     * shared so the two cannot drift into disagreeing about which observations the
     * confidence level actually selects.
     */
    private RankedScenarios rank(Portfolio portfolio, List<MarketShock> historicalScenarios,
                                 MarketDataSnapshot market, LocalDate asOf, double confidenceLevel) {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(historicalScenarios, "historicalScenarios");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(asOf, "asOf");
        requireScenariosAndConfidence(historicalScenarios.isEmpty(), confidenceLevel);

        return rank(sensitivities.valueChangesUnder(portfolio, historicalScenarios, market, asOf),
                confidenceLevel);
    }

    private static void requireScenariosAndConfidence(boolean noScenarios, double confidenceLevel) {
        if (noScenarios) {
            throw new IllegalArgumentException(
                    "Historical VaR needs at least one historical scenario, but none were given");
        }
        if (!(confidenceLevel > 0.0) || !(confidenceLevel < 1.0)) {
            throw new IllegalArgumentException(
                    "Confidence level must be strictly between 0 and 1, but was " + confidenceLevel);
        }
    }

    private static RankedScenarios rank(List<Money> unsortedProfitAndLosses, double confidenceLevel) {
        List<Money> profitAndLosses = unsortedProfitAndLosses.stream().sorted().toList();

        int n = profitAndLosses.size();
        // Subtracting a tiny epsilon before ceiling guards against floating-point overshoot
        // at an exact integer boundary: (1 - 0.95) * 100 is mathematically 5.0, but computes
        // as 5.000000000000001 in double arithmetic, and Math.ceil of that is 6 rather than
        // 5 - silently taking the sixth-worst scenario instead of the fifth. The same class
        // of error C-1 and E-1 found elsewhere in this codebase, here in a place a test alone
        // would not have caught without a rank that happened to land exactly on an integer.
        int rank = clampRank((int) Math.ceil((1.0 - confidenceLevel) * n - 1e-9), n);

        return new RankedScenarios(profitAndLosses, rank);
    }

    /** The sorted P&amp;L distribution and the rank the confidence level selects within it. */
    private record RankedScenarios(List<Money> sorted, int rank) {
    }
}
