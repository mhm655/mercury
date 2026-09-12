package com.mercury.simulation;

import com.mercury.core.id.InstrumentId;
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

/**
 * Value at Risk and Expected Shortfall by Monte Carlo simulation - {@code pathCount} GBM
 * terminal values stand in for {@code pathCount} "historical" days, and
 * {@link HistoricalVaRCalculator} does the rest.
 *
 * <h2>This is historical VaR wearing a different scenario source</h2>
 * Not a coincidence, and not a separate statistic reimplemented here: historical and Monte
 * Carlo VaR are the same percentile computation over a P&amp;L distribution. They differ only
 * in where the scenario list comes from - observed days for one, simulated ones for the
 * other. So this class's entire job is generating that list (one
 * {@link MarketShock#scaleSpot} per simulated path, from {@link GeometricBrownianMotion}) and
 * handing it to a {@link HistoricalVaRCalculator} it owns, rather than re-deriving the
 * sort-and-rank math this codebase already has, tested, in one place.
 *
 * <h2>One risk factor at a time</h2>
 * Simulates a single underlying's spot, the same way {@code SensitivityCalculator.delta} /
 * {@code .gamma} / {@code .vega} are all per-factor rather than joint. A real multi-factor
 * portfolio VaR would need correlated draws across every risk factor at once - a covariance
 * matrix, a Cholesky decomposition, a joint distribution this class does not have and no
 * caller needs yet. Recorded in {@code docs/KNOWN_GAPS.md} rather than built speculatively.
 *
 * <h2>Reproducibility</h2>
 * {@code seed} is fixed at construction; {@link #simulate} draws a fresh
 * {@code SplittableRandom(seed)} every call, so the same inputs always produce the same
 * result - the same injected-seed discipline {@link MonteCarloOptionModel} already follows,
 * for the same reason.
 *
 * <p>Stateless (the seed is configuration, not mutable state) and thread-safe.
 */
public final class MonteCarloVaRCalculator {

    private final HistoricalVaRCalculator delegate;
    private final long seed;

    public MonteCarloVaRCalculator(SensitivityCalculator sensitivities, long seed) {
        this.delegate = new HistoricalVaRCalculator(
                Objects.requireNonNull(sensitivities, "sensitivities"));
        this.seed = seed;
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
        Objects.requireNonNull(underlyingId, "underlyingId");
        Objects.requireNonNull(market, "market");
        if (pathCount <= 0) {
            throw new IllegalArgumentException("pathCount must be positive, but was " + pathCount);
        }

        List<MarketShock> scenarios = simulatedScenarios(
                underlyingId, drift, volatility, years, pathCount, market);

        var valueAtRisk = delegate.valueAtRisk(portfolio, scenarios, market, asOf, confidenceLevel);
        var expectedShortfall = delegate.expectedShortfall(portfolio, scenarios, market, asOf, confidenceLevel);
        return new MonteCarloRiskResult(valueAtRisk, expectedShortfall, pathCount, seed);
    }

    private List<MarketShock> simulatedScenarios(InstrumentId underlyingId, double drift,
                                                  double volatility, double years, int pathCount,
                                                  MarketDataSnapshot market) {
        double spot = market.spot(underlyingId);
        SplittableRandom rng = new SplittableRandom(seed);

        List<MarketShock> scenarios = new ArrayList<>(pathCount);
        for (int i = 0; i < pathCount; i++) {
            double terminal = GeometricBrownianMotion.terminalValue(spot, drift, volatility, years, rng);
            scenarios.add(MarketShock.scaleSpot(underlyingId, terminal / spot));
        }
        return scenarios;
    }
}
