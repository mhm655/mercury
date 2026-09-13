package com.mercury.simulation;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Money;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.marketdata.MarketShock;
import com.mercury.portfolio.Portfolio;
import com.mercury.risk.HistoricalVaRCalculator;
import com.mercury.risk.SensitivityCalculator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.Supplier;

/**
 * Value at Risk, Expected Shortfall, and a confidence interval on the VaR estimate itself, by
 * Monte Carlo simulation - {@code pathCount} GBM terminal values stand in for
 * {@code pathCount} "historical" days, and {@link HistoricalVaRCalculator} does the rest.
 *
 * <h2>This is historical VaR wearing a different scenario source</h2>
 * Not a coincidence, and not a separate statistic reimplemented here: historical and Monte
 * Carlo VaR are the same percentile computation over a P&amp;L distribution. They differ only
 * in where the scenario list comes from - observed days for one, simulated ones for the
 * other. So this class's entire job is producing that distribution (one
 * {@link MarketShock#scaleSpot} per simulated path, from {@link GeometricBrownianMotion}) and
 * handing it to a {@link HistoricalVaRCalculator} it owns, rather than re-deriving the
 * sort-and-rank math this codebase already has, tested, in one place.
 *
 * <h2>Parallel, M13</h2>
 * Revaluation is the whole cost of a VaR run - a GBM draw is nanoseconds, pricing a book is
 * not - so it is the revaluation that runs on {@link SimulationWorkers}, not only the draws.
 * Each {@link PathBlocks} block draws its paths from its own split stream and revalues them on
 * one worker; the blocks' P&amp;Ls are concatenated in block order and ranked once. The
 * portfolio, the market snapshot and the valuation service are all immutable, so the workers
 * share them without a single lock (`docs/DESIGN_PROPOSAL.md` section 5.6 b).
 *
 * <p>Each block values the unshocked base once through
 * {@link SensitivityCalculator#valueChangesUnder}: one extra valuation per
 * {@value PathBlocks#BLOCK_SIZE} paths, taken rather than widening that class's API to accept
 * a precomputed base.
 *
 * <h2>One risk factor at a time</h2>
 * Simulates a single underlying's spot, the same way {@code SensitivityCalculator.delta} /
 * {@code .gamma} / {@code .vega} are all per-factor rather than joint. A real multi-factor
 * portfolio VaR would need correlated draws across every risk factor at once - a covariance
 * matrix, a Cholesky decomposition, a joint distribution this class does not have and no
 * caller needs yet. Recorded in {@code docs/KNOWN_GAPS.md} rather than built speculatively.
 *
 * <h2>Reproducibility</h2>
 * {@code seed} is fixed at construction; {@link #simulate} splits fresh generators from it
 * every call, so the same inputs always produce the same result on any number of workers -
 * the same injected-seed discipline {@link MonteCarloOptionModel} already follows, for the
 * same reason.
 *
 * <p>Stateless (the seed and workers are configuration, not mutable state) and thread-safe.
 */
public final class MonteCarloVaRCalculator {

    private final SensitivityCalculator sensitivities;
    private final HistoricalVaRCalculator delegate;
    private final long seed;
    private final SimulationWorkers workers;

    /** Runs every path on the calling thread - see {@link SimulationWorkers#sequential()}. */
    public MonteCarloVaRCalculator(SensitivityCalculator sensitivities, long seed) {
        this(sensitivities, seed, SimulationWorkers.sequential());
    }

    /**
     * @param workers where the paths are drawn and revalued; the caller owns them and closes
     *                them. Changes how long {@link #simulate} takes, never what it returns.
     */
    public MonteCarloVaRCalculator(SensitivityCalculator sensitivities, long seed,
                                   SimulationWorkers workers) {
        this.sensitivities = Objects.requireNonNull(sensitivities, "sensitivities");
        this.delegate = new HistoricalVaRCalculator(sensitivities);
        this.seed = seed;
        this.workers = Objects.requireNonNull(workers, "workers");
    }

    /**
     * Simulates {@code pathCount} GBM terminal values for {@code underlyingId} and reports
     * VaR and Expected Shortfall of the resulting portfolio P&amp;L.
     *
     * @param drift      annualised drift; real-world for a real-world VaR (often assumed
     *                   zero over a short horizon), risk-neutral only if that is genuinely
     *                   the question being asked - this method does not decide which
     * @param volatility annualised volatility
     * @param years      the VaR horizon in years, e.g. {@code 1.0 / 365} for one day
     * @param pathCount  number of simulated scenarios; more paths narrow the Monte Carlo
     *                   sampling error, at the cost of this call's runtime
     * @param confidenceLevel strictly between 0 and 1, e.g. {@code 0.99} for 99%
     * @throws IllegalArgumentException if {@code pathCount} is not positive, or
     *                                  {@code confidenceLevel} is not strictly between 0 and 1
     * @throws com.mercury.marketdata.MarketDataSnapshot.MissingMarketDataException
     *         if the market holds no spot for {@code underlyingId}
     */
    public MonteCarloRiskResult simulate(Portfolio portfolio, InstrumentId underlyingId,
                                         double drift, double volatility, double years,
                                         int pathCount, MarketDataSnapshot market,
                                         LocalDate asOf, double confidenceLevel) {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(underlyingId, "underlyingId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(asOf, "asOf");
        if (pathCount <= 0) {
            throw new IllegalArgumentException("pathCount must be positive, but was " + pathCount);
        }
        // Checked before any worker starts, so a bad confidence level costs no simulation.
        if (!(confidenceLevel > 0.0) || !(confidenceLevel < 1.0)) {
            throw new IllegalArgumentException(
                    "Confidence level must be strictly between 0 and 1, but was " + confidenceLevel);
        }
        double spot = market.spot(underlyingId);

        List<Supplier<List<Money>>> blocks = new ArrayList<>();
        for (PathBlocks.Block block : PathBlocks.of(pathCount, seed)) {
            blocks.add(() -> sensitivities.valueChangesUnder(portfolio,
                    simulatedScenarios(underlyingId, spot, drift, volatility, years, block),
                    market, asOf));
        }

        List<Money> profitAndLosses = new ArrayList<>(pathCount);
        for (List<Money> blockProfitAndLosses : workers.run(blocks)) {
            profitAndLosses.addAll(blockProfitAndLosses);
        }

        HistoricalVaRCalculator.Measures measures = delegate.measure(profitAndLosses, confidenceLevel);
        return new MonteCarloRiskResult(measures.valueAtRisk(), measures.expectedShortfall(),
                measures.valueAtRiskConfidenceInterval(), pathCount, seed);
    }

    private static List<MarketShock> simulatedScenarios(InstrumentId underlyingId, double spot,
                                                        double drift, double volatility,
                                                        double years, PathBlocks.Block block) {
        SplittableRandom rng = block.rng();
        List<MarketShock> scenarios = new ArrayList<>(block.size());
        for (int i = 0; i < block.size(); i++) {
            double terminal = GeometricBrownianMotion.terminalValue(spot, drift, volatility, years, rng);
            scenarios.add(MarketShock.scaleSpot(underlyingId, terminal / spot));
        }
        return scenarios;
    }
}
