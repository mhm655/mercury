package com.mercury.simulation;

import com.mercury.instrument.EuropeanOption;
import com.mercury.instrument.OptionType;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.ModelName;
import com.mercury.pricing.PricingModel;
import com.mercury.pricing.ValuationResult;
import java.time.LocalDate;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Prices a European option by simulating {@code pathCount} risk-neutral GBM terminal values
 * and averaging the discounted payoff - a second, independent route to the same number
 * {@code BlackScholesModel} computes in closed form.
 *
 * <h2>Why this exists</h2>
 * {@code PricingModel}'s own javadoc names the reason pricing is a registry rather than
 * {@code instrument.price(market)}: "an option must be priceable by Black-Scholes and by a
 * binomial tree, so the two can be cross-checked - which is one of the strongest correctness
 * tests available on a pricer." This is that second route for {@link EuropeanOption} -
 * registered under its own {@link ModelName} so {@code PricingService.price(instrument,
 * modelName, ...)} can ask for either, and {@code MonteCarloOptionModelTest} cross-validates
 * the two agree. `docs/DESIGN_PROPOSAL.md` section 8 names exactly this as a cross-validation
 * test: "Monte Carlo option price converging to the closed form."
 *
 * <h2>Purity despite the randomness</h2>
 * {@code PricingModel} requires "no randomness that is not derived from an argument" -
 * {@code price(instrument, market, asOf)} must give the same answer every time it is called
 * against an unchanged market, the same way {@code BlackScholesModel} does. A field holding
 * a live, mutating {@code RandomGenerator} would break that: the second call would draw from
 * wherever the first left off. So nothing here is mutable - {@code seed} is a fixed
 * constructor value, and {@link #price} creates a fresh {@code SplittableRandom(seed)} on
 * every call. Same market, same date, same answer, always - injected-seed reproducibility,
 * not read-once state, the same discipline {@code SimulationClock} enforces for time.
 *
 * <p>{@code SplittableRandom} specifically because M13's parallel Monte Carlo needs a
 * generator that splits deterministically per task; choosing it now means M13 costs no
 * rework here, even though M12 itself is single-threaded and never calls {@code split()}.
 *
 * <p>Stateless (the seed and path count are configuration, not mutable state) and
 * thread-safe: concurrent calls to {@link #price} each construct their own generator, so
 * there is nothing to contend over.
 */
public final class MonteCarloOptionModel implements PricingModel<EuropeanOption> {

    public static final ModelName NAME = ModelName.of("monte-carlo");

    private final long seed;
    private final int pathCount;

    public MonteCarloOptionModel(long seed, int pathCount) {
        if (pathCount <= 0) {
            throw new IllegalArgumentException("pathCount must be positive, but was " + pathCount);
        }
        this.seed = seed;
        this.pathCount = pathCount;
    }

    @Override
    public Class<EuropeanOption> instrumentType() {
        return EuropeanOption.class;
    }

    @Override
    public ModelName name() {
        return NAME;
    }

    @Override
    public ValuationResult price(EuropeanOption option, MarketDataSnapshot market, LocalDate asOf) {
        Objects.requireNonNull(option, "option");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(asOf, "asOf");

        double spot = market.spot(option.underlyingId());
        double strike = option.strike().value().doubleValue();
        double volatility = market.volatility(option.underlyingId());
        double years = option.yearsToExpiry(asOf);

        // The same curve-at-this-option's-own-expiry rate BlackScholesModel reads, so the
        // two models price against identical inputs and any difference between them is pure
        // simulation error, not a market-data disagreement.
        double rate = market.yieldCurve(option.currency()).zeroRate(years);

        double perShare = priceOneShare(option.optionType(), spot, strike, years, rate, volatility);
        double perContract = perShare * option.contractMultiplier();
        return new ValuationResult(perContract, option.currency(), NAME);
    }

    /**
     * The Monte Carlo per-share value, exposed separately so the convergence tests can drive
     * it with the exact textbook inputs {@code BlackScholesModel.price} takes, the same
     * reason that method exposes its own static twin.
     *
     * @param years time to expiry in years; zero or negative means expired. Unlike
     *              {@code BlackScholesModel}, this needs no explicit boundary branch for it:
     *              that model divides by {@code sigma sqrt(T)} and must special-case the
     *              point where that is zero, while this one never divides by volatility at
     *              all - at zero volatility every simulated path collapses to the same
     *              deterministic forward value, and the average is already the right answer.
     */
    public double priceOneShare(OptionType type, double spot, double strike, double years,
                                double rate, double volatility) {
        double horizon = Math.max(years, 0.0);
        SplittableRandom rng = new SplittableRandom(seed);

        double payoffSum = 0.0;
        for (int i = 0; i < pathCount; i++) {
            // Risk-neutral drift: the whole point of pricing under the risk-neutral measure
            // is that the expected discounted payoff equals today's price, which is exactly
            // what Black-Scholes computes in closed form - the fact this converges to it is
            // the cross-validation, not an assumption baked in to make it converge.
            double terminal = GeometricBrownianMotion.terminalValue(spot, rate, volatility, horizon, rng);
            payoffSum += type == OptionType.CALL
                    ? Math.max(terminal - strike, 0.0)
                    : Math.max(strike - terminal, 0.0);
        }
        double averagePayoff = payoffSum / pathCount;
        return averagePayoff * Math.exp(-rate * horizon);
    }
}
