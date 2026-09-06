package com.mercury.risk;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Money;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.marketdata.MarketShock;
import com.mercury.portfolio.Portfolio;
import com.mercury.portfolio.PortfolioValuationService;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Computes sensitivities by shocking the market and revaluing.
 *
 * <h2>Why numerically, and what it buys</h2>
 * Delta could be read off the Black-Scholes closed form, which would be faster and exact for
 * that model. It is computed by revaluation instead because <b>the same code then works for
 * every instrument that has a pricer</b> - including ones with no closed form at all, and
 * including instruments that do not exist yet. Add a convertible bond with a Monte Carlo
 * pricer and it has a delta immediately, with nothing written here.
 *
 * <p>That is the payoff of {@link MarketShock}: one mechanism serving stress testing, Greeks
 * and Monte Carlo. Analytic Greeks arrive at M10 as an <em>optimisation</em> with the
 * numerical result as its cross-check - two independent implementations agreeing is far
 * stronger evidence than either alone.
 *
 * <h2>Central differences, and why the bump size matters</h2>
 * Delta is estimated as
 *
 * <pre>
 *   delta ~ [ V(S(1+e)) - V(S(1-e)) ] / (2 e S)
 * </pre>
 *
 * Central rather than forward differences: the error is O(e^2) instead of O(e), for one extra
 * revaluation.
 *
 * <p>The bump is <em>relative</em> to spot, not absolute. An absolute bump of 0.01 is a
 * reasonable perturbation on a 50 stock and a meaningless one on an index at 5000, so a fixed
 * absolute size cannot be well conditioned across a real portfolio.
 *
 * <p>Choosing {@code e} is a genuine tension and not a detail. Too large and the estimate
 * measures curvature rather than slope; too small and the two valuations agree to within
 * floating-point noise, and dividing that noise by a tiny denominator amplifies it. The
 * default {@value #DEFAULT_RELATIVE_BUMP} sits in the flat region between those failures for
 * double-precision pricing. Second-order Greeks are far more delicate again - see
 * {@code DESIGN_PROPOSAL.md} section 5.3.1, which is why gamma is deliberately not here yet.
 *
 * <p>Stateless and thread-safe.
 */
public final class SensitivityCalculator {

    /**
     * One basis point of spot. Large enough that the two revaluations differ well above
     * floating-point noise, small enough that curvature has not yet distorted the slope.
     */
    public static final double DEFAULT_RELATIVE_BUMP = 1e-4;

    private final PortfolioValuationService valuationService;
    private final double relativeBump;

    public SensitivityCalculator(PortfolioValuationService valuationService) {
        this(valuationService, DEFAULT_RELATIVE_BUMP);
    }

    public SensitivityCalculator(PortfolioValuationService valuationService, double relativeBump) {
        this.valuationService = Objects.requireNonNull(valuationService, "valuationService");
        if (!(relativeBump > 0) || !Double.isFinite(relativeBump)) {
            throw new IllegalArgumentException(
                    "Relative bump must be positive and finite, but was " + relativeBump);
        }
        this.relativeBump = relativeBump;
    }

    /**
     * How much the portfolio's value changes for a one-unit rise in {@code underlyingId}'s
     * spot price.
     *
     * <p>Reported in the portfolio's reporting currency per unit of spot. A portfolio holding
     * 100 shares has a delta of 100 with respect to that share: each unit the price rises adds
     * 100 to its value.
     *
     * @throws com.mercury.marketdata.MarketDataSnapshot.MissingMarketDataException
     *         if the market holds no spot for {@code underlyingId}
     */
    public double delta(Portfolio portfolio, InstrumentId underlyingId,
                        MarketDataSnapshot market, LocalDate asOf) {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(underlyingId, "underlyingId");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(asOf, "asOf");

        // Reading the spot first means an unknown underlying fails here, rather than silently
        // producing a delta of zero because neither shocked market differed from the base.
        double spot = market.spot(underlyingId);

        // Unrounded model totals, not Money. A delta divides the difference of two valuations
        // by 2 e S - a very small number - so any quantisation in the numerator is amplified
        // in the result. Computing from cent-rounded totals produced a delta of exactly 50.0
        // where the analytic answer was 61.23, because the per-position move was smaller than
        // a cent. Risk stays in the model domain until it is reported.
        double up = revalue(portfolio, market, underlyingId, 1.0 + relativeBump, asOf);
        double down = revalue(portfolio, market, underlyingId, 1.0 - relativeBump, asOf);

        return (up - down) / (2.0 * relativeBump * spot);
    }

    /**
     * The change in portfolio value under an arbitrary shock - the building block of stress
     * testing, exposed here because it is exactly what a scenario needs at M11.
     */
    public Money valueChangeUnder(Portfolio portfolio, MarketShock shock,
                                  MarketDataSnapshot market, LocalDate asOf) {
        Objects.requireNonNull(shock, "shock");
        Money base = valuationService.value(portfolio, market, asOf).totalValue();
        Money shocked = valuationService.value(portfolio, market.withShock(shock), asOf).totalValue();
        return shocked.minus(base);
    }

    private double revalue(Portfolio portfolio, MarketDataSnapshot market,
                           InstrumentId underlyingId, double factor, LocalDate asOf) {
        MarketDataSnapshot shocked = market.withShock(MarketShock.scaleSpot(underlyingId, factor));
        return valuationService.value(portfolio, shocked, asOf).modelTotal();
    }
}
