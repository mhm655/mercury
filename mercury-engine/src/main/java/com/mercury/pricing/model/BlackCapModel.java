package com.mercury.pricing.model;

import com.mercury.core.MercuryException;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.SchedulePeriod;
import com.mercury.curve.YieldCurve;
import com.mercury.instrument.CapFloor;
import com.mercury.instrument.OptionType;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.ModelName;
import com.mercury.pricing.PricingModel;
import com.mercury.pricing.ValuationResult;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Prices a cap or floor as a strip of Black caplets on the forward rate.
 *
 * <h2>Black's model, which is Black-Scholes pointed at a rate</h2>
 * Each caplet is an option on the forward rate for its own accrual period. Assuming that rate
 * is lognormally distributed at its fixing date gives, for a caplet:
 *
 * <pre>
 *   value = N x tau x DF(pay) x [ F x N(d1) - K x N(d2) ]
 *   d1 = [ ln(F/K) + sigma^2 T / 2 ] / (sigma sqrt(T))
 *   d2 = d1 - sigma sqrt(T)
 * </pre>
 *
 * with a floorlet the same expression with the two terms swapped and the normals reflected -
 * which is exactly the call and put of {@link OptionType}, because a caplet <em>is</em> a call
 * on the rate.
 *
 * <p>Two things distinguish this from {@link BlackScholesModel} and are worth naming. The
 * forward rate {@code F} needs no drift term: it is already a forward, so under the right
 * measure it is its own expectation, and no {@code e^(-rT)} appears inside the brackets. And
 * {@code T} is the time to the <em>fixing</em> date - the start of the accrual period - while
 * the discounting runs to the payment date at its end. Using one date for both is a standard
 * way to get a cap slightly and invisibly wrong.
 *
 * <h2>Lognormal, so the forward must be positive</h2>
 * {@code ln(F/K)} has no answer for a negative forward rate, and rates do go negative - this
 * engine says so in {@code MarketDataKey.ZeroRate} and prices swaps through zero without
 * complaint. Black's model simply does not extend there. The honest options are a shifted
 * lognormal (displace both F and K by a positive constant) or the Bachelier normal model, and
 * both are different models rather than adjustments to this one.
 *
 * <p>So a negative forward is refused rather than approximated, and the message says which
 * model would be needed instead. Silently returning the intrinsic value, which is the
 * tempting shortcut, would report a cap as worthless in exactly the market where a floor is
 * worth the most.
 *
 * <h2>Flat volatility</h2>
 * One number for the whole strip, read from the instrument's own volatility in the snapshot.
 * Real caps are quoted against a surface by expiry and strike, and stripping caplet
 * volatilities out of flat cap quotes is a genuine piece of work. Named here, as the same
 * simplification is on {@link BlackScholesModel}.
 *
 * <p>Stateless, pure and thread-safe.
 */
public final class BlackCapModel implements PricingModel<CapFloor> {

    public static final ModelName NAME = ModelName.of("black-cap");

    /** Time to a caplet's fixing is measured as every other year fraction in the engine is. */
    private static final DayCountConvention TIME_CONVENTION = DayCountConvention.ACT_365F;

    @Override
    public Class<CapFloor> instrumentType() {
        return CapFloor.class;
    }

    @Override
    public ModelName name() {
        return NAME;
    }

    @Override
    public ValuationResult price(CapFloor capFloor, MarketDataSnapshot market, LocalDate asOf) {
        Objects.requireNonNull(capFloor, "capFloor");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(asOf, "asOf");

        YieldCurve curve = market.yieldCurve(capFloor.currency());
        double volatility = market.volatility(capFloor.id());
        double notional = capFloor.notional().amount().doubleValue();
        double strike = capFloor.strike();

        double value = 0.0;
        for (SchedulePeriod caplet : capFloor.schedule().unpaidPeriodsAsOf(asOf)) {
            if (caplet.accrualStart().isBefore(asOf)) {
                throw new CapletAlreadyFixedException(capFloor, caplet, asOf);
            }
            double forward = curve.simpleForwardRate(
                    caplet.accrualStart(), caplet.accrualEnd(), capFloor.dayCount());
            double yearsToFixing = TIME_CONVENTION.yearFraction(asOf, caplet.accrualStart());
            double accrual = caplet.yearFraction(capFloor.dayCount());
            double discountFactor = curve.discountFactor(caplet.paymentDate());

            value += notional * accrual * discountFactor
                    * capletValue(capFloor.optionType(), forward, strike, yearsToFixing,
                            volatility, capFloor, caplet);
        }
        return new ValuationResult(value, capFloor.currency(), NAME);
    }

    /**
     * One caplet's value per unit of notional-times-accrual, undiscounted.
     *
     * <p>Exposed as a separate step so the boundary cases stay legible: a caplet that fixes
     * today, or one on an underlying with no volatility, is worth its intrinsic value and
     * nothing more, and neither case should reach a logarithm.
     */
    private static double capletValue(OptionType type, double forward, double strike,
                                      double yearsToFixing, double volatility,
                                      CapFloor capFloor, SchedulePeriod caplet) {
        if (yearsToFixing <= 0.0 || volatility == 0.0) {
            return type.intrinsicValue(forward, strike);
        }
        if (forward <= 0.0 || strike <= 0.0) {
            throw new NegativeForwardException(capFloor, caplet, forward, strike);
        }

        double standardDeviation = volatility * Math.sqrt(yearsToFixing);
        double d1 = (Math.log(forward / strike) + 0.5 * standardDeviation * standardDeviation)
                / standardDeviation;
        double d2 = d1 - standardDeviation;

        return type == OptionType.CALL
                ? forward * NormalDistribution.cumulative(d1)
                        - strike * NormalDistribution.cumulative(d2)
                : strike * NormalDistribution.cumulative(-d2)
                        - forward * NormalDistribution.cumulative(-d1);
    }

    /**
     * Raised when a caplet has already fixed, so its payoff is history rather than a
     * distribution.
     *
     * <p>The same situation {@code SwapModel} refuses for a floating coupon, and for the same
     * reason: an index fixing is market data, the snapshot does not hold it, and inventing one
     * produces a valuation indistinguishable from a correct one.
     *
     * <p>It is a <em>separate</em> exception only because this class was added under a
     * constraint - see {@code docs/EXTENSIBILITY.md} - that no existing file be modified, and
     * the swap's version is nested inside {@code SwapModel}. Left to itself the right shape is
     * one shared {@code MissingFixingException} in the pricing package, and that is what should
     * happen when fixings arrive at M8. Recording the duplication is better than pretending the
     * constraint was free.
     */
    public static final class CapletAlreadyFixedException extends MercuryException {
        CapletAlreadyFixedException(CapFloor capFloor, SchedulePeriod caplet, LocalDate asOf) {
            super("The caplet " + caplet + " of " + capFloor.id() + " fixed on "
                    + caplet.accrualStart() + ", before the valuation date " + asOf
                    + ". Its payoff is a published figure rather than a distribution, and "
                    + "Mercury holds no fixing history. Fixings arrive with the trade lifecycle "
                    + "at M8; until then a cap must be valued on or before its start date.");
        }
    }

    /** Raised when the lognormal assumption meets a rate that has gone through zero. */
    public static final class NegativeForwardException extends MercuryException {
        NegativeForwardException(CapFloor capFloor, SchedulePeriod caplet, double forward,
                                 double strike) {
            super("Black's model cannot price the caplet " + caplet + " of " + capFloor.id()
                    + ": it assumes the rate is lognormal, and the forward is " + forward
                    + " against a strike of " + strike + ". A lognormal variable cannot be "
                    + "negative, so this is not a numerical difficulty but the wrong model - a "
                    + "shifted-lognormal or Bachelier normal model is what the market uses "
                    + "here. Returning the intrinsic value instead would report a cap as "
                    + "worthless in precisely the market where a floor is worth the most.");
        }
    }
}
