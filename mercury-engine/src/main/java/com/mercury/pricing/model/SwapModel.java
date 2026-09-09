package com.mercury.pricing.model;

import com.mercury.core.MercuryException;
import com.mercury.core.money.Currency;
import com.mercury.core.time.SchedulePeriod;
import com.mercury.curve.YieldCurve;
import com.mercury.instrument.Cashflow;
import com.mercury.instrument.FloatingRateLeg;
import com.mercury.instrument.InterestRateSwap;
import com.mercury.instrument.PayReceive;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.ModelName;
import com.mercury.pricing.PricingModel;
import com.mercury.pricing.ValuationResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Values an interest-rate swap by discounting one leg and projecting the other.
 *
 * <h2>The instrument the capability interface was designed around</h2>
 * A swap is why {@code CashflowGenerating} stops where it does. Its fixed leg knows its
 * amounts at trade time and implements the interface; its floating leg cannot, because each
 * coupon is a rate that has not been set yet. This model is where that asymmetry is finally
 * paid off: the fixed side goes straight through the same discounting a bond uses, and the
 * floating side gets the one extra step - projection - that made it a separate type.
 *
 * <pre>
 *   PV = PV(fixed leg)  +  PV(floating leg)
 * </pre>
 *
 * Both legs are signed from the holder's point of view, so a payer swap has a negative fixed
 * leg and a positive floating one, and they simply add.
 *
 * <h2>Projecting a floating coupon</h2>
 * Each unpaid period is projected at the <b>simple</b> forward rate implied by the curve over
 * that period, on the leg's own day count - see
 * {@link YieldCurve#simpleForwardRate}. Adding the leg's spread and accruing gives the coupon,
 * which is then discounted like any other.
 *
 * <p>The choice of simple rather than continuously-compounded forward is what makes the
 * arithmetic exact rather than approximately right. Coupon {@code N x L x tau} paid at the
 * period end discounts to {@code N x (DF(start) - DF(end))}: the accrual fraction cancels, the
 * sum telescopes, and the whole floating leg is worth
 * {@code N x (DF(first start) - DF(final end))} whatever its payment frequency. That is the
 * identity behind a par swap being worth exactly zero, and it is why the par-rate round trip
 * in the tests is a real check on this model rather than a restatement of it.
 *
 * <h2>Two simplifications, named</h2>
 * <ul>
 *   <li><b>Single curve.</b> The same curve projects the floating leg and discounts both. Real
 *       desks have discounted on OIS and projected on a separate index curve since 2008, and
 *       the basis between them is its own quoted market. Listed in {@code KNOWN_GAPS.md}.</li>
 *   <li><b>No fixing history.</b> A period already under way fixed its rate at the start, and
 *       Mercury stores no past fixings, so such a swap is <em>refused</em> rather than
 *       approximated - see {@link MissingFixingException}. Swaps valued on or before their
 *       start date, which is every swap at the moment it is traded, are unaffected.</li>
 * </ul>
 *
 * <p>Stateless, pure and thread-safe.
 */
public final class SwapModel implements PricingModel<InterestRateSwap> {

    public static final ModelName NAME = ModelName.of("swap-discounting");

    @Override
    public Class<InterestRateSwap> instrumentType() {
        return InterestRateSwap.class;
    }

    @Override
    public ModelName name() {
        return NAME;
    }

    @Override
    public ValuationResult price(InterestRateSwap swap, MarketDataSnapshot market, LocalDate asOf) {
        Objects.requireNonNull(swap, "swap");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(asOf, "asOf");

        Currency currency = swap.currency();
        double fixed = CashflowDiscounting.presentValue(
                swap.fixedLeg().cashflows(asOf), market, currency);
        double floating = CashflowDiscounting.presentValue(
                projectedCashflows(swap.floatingLeg(), market, asOf), market, currency);

        return new ValuationResult(fixed + floating, currency, NAME);
    }

    /**
     * The par rate: the fixed rate at which this swap would be worth nothing.
     *
     * <p>Solved algebraically rather than numerically, because it can be. The swap is worth
     * {@code +/- (S x A - F)} where {@code A} is the fixed leg's annuity - the present value of
     * one unit of rate - and {@code F} is the floating leg's value, so
     * {@code S = F / A} directly. Reaching for a root finder here would be reaching past an
     * exact answer.
     *
     * <p>Both quantities are computed on the swap's own conventions, so the answer is the par
     * rate for <em>this</em> schedule and day count, not a generic market quote. Two swaps on
     * the same curve with different fixed frequencies have different par rates, and the
     * difference is real rather than noise.
     *
     * @throws IllegalArgumentException if the swap has no remaining fixed coupons to solve over
     */
    public double parRate(InterestRateSwap swap, MarketDataSnapshot market, LocalDate asOf) {
        Objects.requireNonNull(swap, "swap");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(asOf, "asOf");

        Currency currency = swap.currency();
        YieldCurve curve = market.yieldCurve(currency);
        double notional = swap.notional().amount().doubleValue();

        double annuity = 0.0;
        for (SchedulePeriod period : swap.fixedLeg().schedule().unpaidPeriodsAsOf(asOf)) {
            annuity += period.yearFraction(swap.fixedLeg().dayCount())
                    * curve.discountFactor(period.paymentDate());
        }
        if (annuity == 0.0) {
            throw new IllegalArgumentException(
                    "Swap " + swap.id() + " has no fixed coupons left after " + asOf
                            + ", so it has no par rate: there is nothing left for a fixed rate "
                            + "to be paid on.");
        }

        // The par rate is a property of the schedule and the curve, not of which way round the
        // holder trades it - so the floating leg is normalised to "as if received".
        //
        // By DIRECTION, not by magnitude. Math.abs looks like it does the same job and does
        // not: a floating leg's present value is signed twice over, once by whether the holder
        // receives it and once by the curve. In a negative-rate market a received floating leg
        // is worth less than nothing, and abs threw that second sign away - reporting +0.30%
        // for a market quoting -0.30%, and pricing a swap struck at its own reported par rate
        // at -302,828 on ten million of notional. See E-1 in KNOWN_GAPS.md.
        double floating = CashflowDiscounting.presentValue(
                projectedCashflows(swap.floatingLeg(), market, asOf), market, currency);
        if (swap.floatingLeg().payReceive() == PayReceive.PAY) {
            floating = -floating;
        }

        return floating / (annuity * notional);
    }

    /**
     * Each unpaid floating period, projected off the curve and turned into a dated amount.
     *
     * @throws MissingFixingException if a period has already begun, so its rate was set
     *         before the valuation date and cannot be projected
     */
    private static List<Cashflow> projectedCashflows(FloatingRateLeg leg,
                                                     MarketDataSnapshot market, LocalDate asOf) {
        YieldCurve curve = market.yieldCurve(leg.currency());
        List<SchedulePeriod> remaining = leg.unpaidPeriods(asOf);
        List<Cashflow> cashflows = new ArrayList<>(remaining.size());

        for (SchedulePeriod period : remaining) {
            if (period.accrualStart().isBefore(asOf)) {
                throw new MissingFixingException(leg, period, asOf);
            }
            double projected = curve.simpleForwardRate(
                    period.accrualStart(), period.accrualEnd(), leg.dayCount());
            cashflows.add(new Cashflow(period.paymentDate(), leg.couponFor(period, projected)));
        }
        return cashflows;
    }

    /**
     * Raised when a floating period has already started, so its rate is history rather than a
     * projection.
     *
     * <h2>Why this refuses rather than approximates</h2>
     * The first version clamped: it projected the rate from the valuation date to the period
     * end and then accrued that rate over the <em>whole</em> period. Those are two different
     * lengths of time. On a swap seasoned six weeks into a three-month period it charged a
     * 48-day rate for 92 days of accrual - dimensionally wrong, quietly plausible, and worth
     * tens of thousands on a ten-million notional.
     *
     * <p>Every alternative that returns a number invents one. Accruing only over the remaining
     * stub silently drops the interest already earned; assuming the index fixed at today's
     * equivalent-tenor rate makes up a fixing that is a matter of public record. Both produce a
     * valuation that looks exactly as authoritative as a correct one.
     *
     * <p>So this follows the rule the rest of the engine already follows: an index fixing is
     * market data, the snapshot does not hold it, and missing market data is an error rather
     * than a zero - the same reasoning as
     * {@code MarketDataSnapshot.MissingMarketDataException}. A fixing store is bookkeeping
     * rather than a design question, and arrives with the trade lifecycle at M8.
     *
     * <p>Swaps that start on or after the valuation date - every swap at the moment it is
     * traded - are unaffected.
     */
    public static final class MissingFixingException extends MercuryException {
        MissingFixingException(FloatingRateLeg leg, SchedulePeriod period, LocalDate asOf) {
            super("The floating period " + period + " on a " + leg.index()
                    + " leg began before the valuation date " + asOf + ", so its rate was fixed "
                    + "on " + period.accrualStart() + " and is a published figure rather than "
                    + "something a curve can project. Mercury holds no fixing history, and "
                    + "guessing the rate would produce a plausible wrong coupon: the accrual "
                    + "runs for the whole period, so any rate covering only the remainder is "
                    + "charged over a longer time than it applies to. Fixings arrive with the "
                    + "trade lifecycle at M8; until then a swap must be valued on or before its "
                    + "start date.");
        }
    }
}
