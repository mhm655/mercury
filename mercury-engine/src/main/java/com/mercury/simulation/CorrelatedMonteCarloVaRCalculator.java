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
 * {@link MonteCarloVaRCalculator}'s multi-factor sibling, M20: several risk factors' spots
 * simulated jointly, correlated through a {@link CorrelationMatrix}, rather than one factor in
 * isolation.
 *
 * <h2>The single-factor version's own javadoc named this gap</h2>
 * {@code MonteCarloVaRCalculator}: "a real multi-factor portfolio VaR would need correlated
 * draws across every risk factor at once - a covariance matrix, a Cholesky decomposition, a
 * joint distribution this class does not have." This class is that answer, built alongside
 * rather than in place of the single-factor one: a book with only one real driver of risk has
 * no correlation to draw, and the simpler class stays the right tool for it.
 *
 * <h2>Same mechanism as the single-factor version, wider</h2>
 * Still one {@link MarketShock} per simulated path handed to
 * {@link SensitivityCalculator#valueChangesUnder}, still ranked once by
 * {@link HistoricalVaRCalculator}, still parallelised over {@link SimulationWorkers} with
 * {@link PathBlocks} splitting the random stream deterministically - none of that changes. What
 * changes is what one path's shock actually is: instead of one
 * {@link GeometricBrownianMotion#terminalValue}, a path here draws one independent standard
 * normal per factor, correlates them through {@link CorrelationMatrix#choleskyLower()} (the
 * standard {@code L * Z} construction - {@code L} the Cholesky factor, {@code Z} the independent
 * draws), feeds each factor's own correlated normal to
 * {@link GeometricBrownianMotion#terminalValueFromStandardNormal}, and combines every factor's
 * resulting shock into one {@link MarketShock#composite} for that path - reusing
 * {@code MarketShock}'s existing composability rather than inventing a joint shock type.
 *
 * <h2>Reproducibility</h2>
 * Draws happen in a fixed order - the order {@code factors} lists them, every path, every block
 * - so a block's random stream (deterministic given {@link PathBlocks}) produces the same
 * correlated shocks regardless of which worker runs it. The same seed therefore gives a
 * bit-identical result on one worker or twelve, exactly the property
 * {@code MonteCarloVaRCalculator} already has.
 *
 * <p>Stateless (the seed and workers are configuration, not mutable state) and thread-safe.
 */
public final class CorrelatedMonteCarloVaRCalculator {

    private final SensitivityCalculator sensitivities;
    private final HistoricalVaRCalculator delegate;
    private final long seed;
    private final SimulationWorkers workers;

    /** Runs every path on the calling thread - see {@link SimulationWorkers#sequential()}. */
    public CorrelatedMonteCarloVaRCalculator(SensitivityCalculator sensitivities, long seed) {
        this(sensitivities, seed, SimulationWorkers.sequential());
    }

    /**
     * @param workers where the paths are drawn and revalued; the caller owns them and closes
     *                them. Changes how long {@link #simulate} takes, never what it returns.
     */
    public CorrelatedMonteCarloVaRCalculator(SensitivityCalculator sensitivities, long seed,
                                             SimulationWorkers workers) {
        this.sensitivities = Objects.requireNonNull(sensitivities, "sensitivities");
        this.delegate = new HistoricalVaRCalculator(sensitivities);
        this.seed = seed;
        this.workers = Objects.requireNonNull(workers, "workers");
    }

    /**
     * One simulated risk factor: which underlying's spot, and the GBM parameters driving it -
     * the per-factor equivalent of {@code MonteCarloVaRCalculator.simulate}'s own
     * {@code underlyingId}/{@code drift}/{@code volatility} parameters, grouped into one type
     * because {@link #simulate} now needs a list of them rather than one of each.
     *
     * @param drift      annualised drift; real-world for a real-world VaR (often assumed zero
     *                   over a short horizon), risk-neutral only if that is genuinely the
     *                   question being asked - this type does not decide which
     * @param volatility annualised volatility; must be non-negative
     */
    public record RiskFactor(InstrumentId underlyingId, double drift, double volatility) {

        public RiskFactor {
            Objects.requireNonNull(underlyingId, "underlyingId");
            if (!Double.isFinite(drift)) {
                throw new IllegalArgumentException("drift must be finite, but was " + drift);
            }
            if (!Double.isFinite(volatility) || !(volatility >= 0.0)) {
                throw new IllegalArgumentException(
                        "volatility must be finite and non-negative, but was " + volatility);
            }
        }
    }

    /**
     * Simulates {@code pathCount} jointly correlated GBM terminal values across every factor in
     * {@code factors} and reports VaR and Expected Shortfall of the resulting portfolio
     * P&amp;L.
     *
     * @param factors     the risk factors to simulate, in the order {@code correlation} indexes
     *                    them - {@code factors.get(i)} correlates against {@code factors.get(j)}
     *                    at {@code correlation}'s row {@code i}, column {@code j}
     * @param correlation a validated correlation structure across exactly {@code factors.size()}
     *                    factors, in the same order
     * @param years       the VaR horizon in years, e.g. {@code 1.0 / 365} for one day
     * @param pathCount   number of simulated scenarios; more paths narrow the Monte Carlo
     *                    sampling error, at the cost of this call's runtime
     * @param confidenceLevel strictly between 0 and 1, e.g. {@code 0.99} for 99%
     * @throws IllegalArgumentException if {@code factors} is empty, {@code factors.size()} does
     *                                  not equal {@code correlation.size()}, {@code pathCount}
     *                                  is not positive, or {@code confidenceLevel} is not
     *                                  strictly between 0 and 1
     * @throws com.mercury.marketdata.MarketDataSnapshot.MissingMarketDataException
     *         if the market holds no spot for one of {@code factors}' underlyings
     */
    public MonteCarloRiskResult simulate(Portfolio portfolio, List<RiskFactor> factors,
                                         CorrelationMatrix correlation, double years, int pathCount,
                                         MarketDataSnapshot market, LocalDate asOf,
                                         double confidenceLevel) {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(factors, "factors");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(asOf, "asOf");
        if (factors.isEmpty()) {
            throw new IllegalArgumentException("factors must not be empty");
        }
        if (factors.size() != correlation.size()) {
            throw new IllegalArgumentException(
                    "factors has " + factors.size() + " entries but correlation covers "
                            + correlation.size() + " - they must describe the same risk factors, "
                            + "in the same order");
        }
        if (pathCount <= 0) {
            throw new IllegalArgumentException("pathCount must be positive, but was " + pathCount);
        }
        // Checked before any worker starts, so a bad confidence level costs no simulation.
        if (!(confidenceLevel > 0.0) || !(confidenceLevel < 1.0)) {
            throw new IllegalArgumentException(
                    "Confidence level must be strictly between 0 and 1, but was " + confidenceLevel);
        }

        List<RiskFactor> orderedFactors = List.copyOf(factors);
        double[] spots = new double[orderedFactors.size()];
        for (int i = 0; i < orderedFactors.size(); i++) {
            spots[i] = market.spot(orderedFactors.get(i).underlyingId());
        }
        double[][] choleskyLower = correlation.choleskyLower();

        List<Supplier<List<Money>>> blocks = new ArrayList<>();
        for (PathBlocks.Block block : PathBlocks.of(pathCount, seed)) {
            blocks.add(() -> sensitivities.valueChangesUnder(portfolio,
                    correlatedScenarios(orderedFactors, spots, choleskyLower, years, block),
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

    private static List<MarketShock> correlatedScenarios(List<RiskFactor> factors, double[] spots,
                                                          double[][] choleskyLower, double years,
                                                          PathBlocks.Block block) {
        SplittableRandom rng = block.rng();
        int n = factors.size();
        List<MarketShock> scenarios = new ArrayList<>(block.size());
        double[] independent = new double[n];
        for (int path = 0; path < block.size(); path++) {
            // Drawn in factor order, every path - what keeps this reproducible regardless of
            // which worker runs the block. L * Z: independent[k] up to and including k = i
            // contributes to correlated[i], per the standard Cholesky construction.
            for (int i = 0; i < n; i++) {
                independent[i] = rng.nextGaussian();
            }

            List<MarketShock> shocksForThisPath = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double correlatedZ = 0.0;
                for (int k = 0; k <= i; k++) {
                    correlatedZ += choleskyLower[i][k] * independent[k];
                }
                RiskFactor factor = factors.get(i);
                double spot = spots[i];
                double terminal = GeometricBrownianMotion.terminalValueFromStandardNormal(
                        spot, factor.drift(), factor.volatility(), years, correlatedZ);
                shocksForThisPath.add(MarketShock.scaleSpot(factor.underlyingId(), terminal / spot));
            }
            scenarios.add(MarketShock.composite(shocksForThisPath));
        }
        return scenarios;
    }
}
