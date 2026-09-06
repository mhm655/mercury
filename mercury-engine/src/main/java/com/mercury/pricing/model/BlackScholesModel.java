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
 *   <li><b>Constant, flat interest rate.</b> Consistent with the flat
 *       {@code MarketDataKey.DiscountRate} this milestone uses.</li>
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
        double rate = market.discountRate(option.currency());
        double years = option.yearsToExpiry(asOf);

        double value = price(option.optionType(), spot, strike, years, rate, volatility);
        return new ValuationResult(value, option.currency(), NAME);
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

        double d1 = (Math.log(spot / strike)
                + (rate + 0.5 * volatility * volatility) * years) / totalVolatility;
        double d2 = d1 - totalVolatility;
        double discountedStrike = strike * Math.exp(-rate * years);

        return type == OptionType.CALL
                ? spot * NormalDistribution.cumulative(d1)
                        - discountedStrike * NormalDistribution.cumulative(d2)
                : discountedStrike * NormalDistribution.cumulative(-d2)
                        - spot * NormalDistribution.cumulative(-d1);
    }
}
