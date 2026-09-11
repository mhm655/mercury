package com.mercury.pricing.model;

import com.mercury.instrument.EuropeanOption;
import com.mercury.instrument.OptionType;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.ModelName;
import com.mercury.pricing.PricingModel;
import com.mercury.pricing.ValuationResult;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Prices a European option by the Black-Scholes-Merton closed form.
 *
 * <h2>The formulas</h2>
 * With spot {@code S}, strike {@code K}, time to expiry {@code T} in years, continuously
 * compounded rate {@code r} and annualised volatility {@code sigma}:
 *
 * <pre>
 *   d1   = [ ln(S/K) + (r + sigma^2 / 2) T ] / (sigma sqrt(T))
 *   d2   = d1 - sigma sqrt(T)
 *
 *   call = S N(d1) - K e^(-rT) N(d2)
 *   put  = K e^(-rT) N(-d2) - S N(-d1)
 * </pre>
 *
 * <h2>Assumptions, stated rather than implied</h2>
 * The result is only as good as these, and every one of them is false in some market:
 *
 * <ul>
 *   <li><b>No dividends.</b> The underlying pays nothing over the option's life. A real
 *       dividend yield {@code q} would discount the spot term by {@code e^(-qT)}. Mercury
 *       does not model dividends at all yet, so this is consistent rather than an omission.</li>
 *   <li><b>Constant volatility.</b> One number per underlying, no smile and no term
 *       structure. Real markets charge more for out-of-the-money strikes; this is the single
 *       largest source of error against traded prices.</li>
 *   <li><b>A single interest rate.</b> The formula takes one rate for the whole life of the
 *       option, which is intrinsic to Black-Scholes and not a limitation of the market data.
 *       Since M5b the rate is read off the currency's curve <em>at the option's own
 *       expiry</em>, so a one-year and a five-year option on the same underlying now discount
 *       at different rates - which is more nearly right than the one flat rate they shared
 *       before, and still not a stochastic term structure.</li>
 *   <li><b>Lognormal returns, continuous trading, no transaction costs, European
 *       exercise.</b> The last is enforced by the type - an American option would need a
 *       lattice, which is why it is a separate class rather than a flag.</li>
 * </ul>
 *
 * <h2>Boundary cases</h2>
 * At expiry, or with zero volatility, the formula degenerates: {@code sigma sqrt(T)} is zero
 * and {@code d1} divides by zero. Both are handled explicitly and return the correct limiting
 * value - the discounted intrinsic payoff - rather than producing NaN. Zero volatility means
 * the forward is known with certainty, so the option is worth its discounted intrinsic value.
 *
 * <h2>Per contract, not per share</h2>
 * {@link #price} returns the textbook per-share value so it can be checked against published
 * examples directly. The {@link #price(EuropeanOption, MarketDataSnapshot, java.time.LocalDate)}
 * entry point multiplies by the option's contract multiplier, because a
 * {@code ValuationResult} is the value of one unit of the instrument and a listed contract is
 * a hundred shares.
 *
 * <p>Stateless, pure and thread-safe.
 */
public final class BlackScholesModel implements PricingModel<EuropeanOption> {

    public static final ModelName NAME = ModelName.of("black-scholes");

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

        // The zero rate to this option's own expiry, not a single rate for the whole market.
        // Black-Scholes needs one number, and the honest one is the curve's answer for the
        // horizon that actually matters here.
        double rate = market.yieldCurve(option.currency()).zeroRate(years);

        double perShare = price(option.optionType(), spot, strike, years, rate, volatility);

        // Scale to one CONTRACT. A ValuationResult is the value of one unit of the instrument,
        // and a listed equity option contract covers 100 shares - so a position of 5 is five
        // contracts, not five options on one share. Omitting this understated every option leg
        // by the multiplier, and it showed up as an option position contributing 117 to a
        // portfolio where it should have contributed 11,710. Applying it here rather than in
        // the portfolio keeps the multiplier where the contract terms live: the valuation
        // layer stays a uniform quantity-times-unit-value and knows nothing about contracts.
        double perContract = perShare * option.contractMultiplier();
        return new ValuationResult(perContract, option.currency(), NAME);
    }

    /**
     * The closed-form value, exposed separately so tests can drive it with textbook inputs
     * and so the analytic Greeks at M10 can share exactly these conventions.
     *
     * @param years time to expiry in years; zero or negative means expired
     */
    public static double price(OptionType type, double spot, double strike,
                               double years, double rate, double volatility) {
        double totalVolatility = volatility * Math.sqrt(Math.max(years, 0.0));
        if (totalVolatility <= 0.0) {
            // Expired, or a certain outcome. Either way the payoff is known, so the option is
            // worth its discounted intrinsic value. Falling through would divide by zero.
            double discountedStrike = strike * Math.exp(-rate * Math.max(years, 0.0));
            return type == OptionType.CALL
                    ? Math.max(spot - discountedStrike, 0.0)
                    : Math.max(discountedStrike - spot, 0.0);
        }

        double d1 = d1(spot, strike, years, rate, volatility, totalVolatility);
        double d2 = d1 - totalVolatility;
        double discountedStrike = strike * Math.exp(-rate * years);

        return type == OptionType.CALL
                ? spot * NormalDistribution.cumulative(d1)
                        - discountedStrike * NormalDistribution.cumulative(d2)
                : discountedStrike * NormalDistribution.cumulative(-d2)
                        - spot * NormalDistribution.cumulative(-d1);
    }

    /**
     * The closed-form per-share Delta - {@code N(d1)} for a call, {@code N(d1) - 1} for a
     * put - exposed at M10 for {@code SensitivityCalculator}'s numerical Delta to be
     * cross-validated against, per {@code docs/DESIGN_PROPOSAL.md} section 5.3.
     *
     * <p>At the same expired-or-certain boundary {@link #price} handles explicitly, the
     * option is either fully in the money or fully out of it: Delta is exactly 1, -1 or 0
     * depending on moneyness, not a limit of the formula above (which would divide by zero).
     *
     * @param years time to expiry in years; zero or negative means expired
     */
    public static double delta(OptionType type, double spot, double strike,
                               double years, double rate, double volatility) {
        double totalVolatility = volatility * Math.sqrt(Math.max(years, 0.0));
        if (totalVolatility <= 0.0) {
            double discountedStrike = strike * Math.exp(-rate * Math.max(years, 0.0));
            boolean inTheMoney = type == OptionType.CALL
                    ? spot > discountedStrike
                    : spot < discountedStrike;
            if (!inTheMoney) {
                return 0.0;
            }
            return type == OptionType.CALL ? 1.0 : -1.0;
        }

        double d1 = d1(spot, strike, years, rate, volatility, totalVolatility);
        return type == OptionType.CALL
                ? NormalDistribution.cumulative(d1)
                : NormalDistribution.cumulative(d1) - 1.0;
    }

    /**
     * The closed-form per-share Gamma, {@code N'(d1) / (S sigma sqrt(T))} - identical for a
     * call and a put, since they differ only by a forward that Gamma does not see. Exposed at
     * M10 as the validation {@code docs/DESIGN_PROPOSAL.md} section 5.3.1 calls for "written
     * early... because if this test is flaky, the section 5.3 story is weaker than
     * advertised" - Gamma is the numerically delicate one, so this is the number the
     * numerical estimate is checked against.
     *
     * <p>Zero at the same expired-or-certain boundary {@link #price} handles: a payoff that
     * is already known with certainty has no more curvature to be sensitive to.
     *
     * @param years time to expiry in years; zero or negative means expired
     */
    public static double gamma(double spot, double strike, double years, double rate,
                               double volatility) {
        double totalVolatility = volatility * Math.sqrt(Math.max(years, 0.0));
        if (totalVolatility <= 0.0) {
            return 0.0;
        }
        double d1 = d1(spot, strike, years, rate, volatility, totalVolatility);
        return NormalDistribution.density(d1) / (spot * totalVolatility);
    }

    /**
     * The closed-form per-share Vega, {@code S N'(d1) sqrt(T)} - identical for a call and a
     * put, and per <em>unit</em> (100%) change in volatility, the textbook convention.
     * {@code SensitivityCalculator.vega} reports per one vol point (1%) instead, to match the
     * size of the shock it actually applies - a caller comparing the two must scale this
     * result by {@code 0.01}, which the cross-validation test does explicitly rather than
     * silently, so the convention mismatch cannot be missed by future eyes.
     *
     * <p>Zero at the same expired-or-certain boundary {@link #price} handles: a known payoff
     * has no sensitivity left to how uncertain the outcome was assumed to be.
     *
     * @param years time to expiry in years; zero or negative means expired
     */
    public static double vega(double spot, double strike, double years, double rate,
                              double volatility) {
        double totalVolatility = volatility * Math.sqrt(Math.max(years, 0.0));
        if (totalVolatility <= 0.0) {
            return 0.0;
        }
        double d1 = d1(spot, strike, years, rate, volatility, totalVolatility);
        return spot * NormalDistribution.density(d1) * Math.sqrt(years);
    }

    private static double d1(double spot, double strike, double years, double rate,
                             double volatility, double totalVolatility) {
        return (Math.log(spot / strike) + (rate + 0.5 * volatility * volatility) * years)
                / totalVolatility;
    }
}
