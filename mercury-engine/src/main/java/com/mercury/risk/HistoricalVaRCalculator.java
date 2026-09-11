package com.mercury.risk;

import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.marketdata.MarketShock;
import com.mercury.portfolio.Portfolio;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Value at Risk by historical simulation: revalue the portfolio under each of a set of
 * actual historical daily moves, and report a percentile of the resulting P&amp;L
 * distribution as the loss.
 *
 * <h2>One mechanism, another feature</h2>
 * {@link SensitivityCalculator#valueChangeUnder} already does "shock the market, revalue,
 * report the difference" - the primitive {@code docs/DESIGN_PROPOSAL.md} section 5.3 built
 * for stress testing and Greeks. Historical VaR is a third use of exactly that primitive,
 * repeated over many shocks instead of one: no new revaluation machinery, only a percentile
 * over the results.
 *
 * <h2>Historical, not Monte Carlo</h2>
 * This is a genuinely different technique from the Monte Carlo VaR arriving at M12, not an
 * early duplicate of it. Historical simulation uses moves that actually happened - each
 * {@link MarketShock} here should represent one real historical day's observed change - so
 * it needs no distributional assumption about returns at all, at the cost of only ever
 * seeing scenarios that occurred in the sampled history. Monte Carlo instead simulates paths
 * from an assumed process, which can explore combinations of moves history never happened to
 * produce.
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
 * <p>Stateless and thread-safe, given a thread-safe {@link SensitivityCalculator}.
 */
public final class HistoricalVaRCalculator {

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
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(historicalScenarios, "historicalScenarios");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(asOf, "asOf");
        if (historicalScenarios.isEmpty()) {
            throw new IllegalArgumentException(
                    "Historical VaR needs at least one historical scenario, but none were given");
        }
        if (!(confidenceLevel > 0.0) || !(confidenceLevel < 1.0)) {
            throw new IllegalArgumentException(
                    "Confidence level must be strictly between 0 and 1, but was " + confidenceLevel);
        }

        List<Money> profitAndLosses = historicalScenarios.stream()
                .map(shock -> sensitivities.valueChangeUnder(portfolio, shock, market, asOf))
                .sorted()
                .toList();

        int n = profitAndLosses.size();
        // Subtracting a tiny epsilon before ceiling guards against floating-point overshoot
        // at an exact integer boundary: (1 - 0.95) * 100 is mathematically 5.0, but computes
        // as 5.000000000000001 in double arithmetic, and Math.ceil of that is 6 rather than
        // 5 - silently taking the sixth-worst scenario instead of the fifth. The same class
        // of error C-1 and E-1 found elsewhere in this codebase, here in a place a test alone
        // would not have caught without a rank that happened to land exactly on an integer.
        int rank = (int) Math.ceil((1.0 - confidenceLevel) * n - 1e-9);
        rank = Math.max(1, Math.min(rank, n));
        Money worstAtConfidence = profitAndLosses.get(rank - 1);

        Currency currency = worstAtConfidence.currency();
        return worstAtConfidence.isNegative() ? worstAtConfidence.negated() : Money.zero(currency);
    }
}
