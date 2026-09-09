package com.mercury.pricing.model;

import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.time.SchedulePeriod;
import com.mercury.curve.YieldCurve;
import com.mercury.instrument.Cashflow;
import com.mercury.instrument.FloatingRateLeg;
import com.mercury.instrument.InterestRateSwap;
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
 *   <li><b>No fixing history.</b> A period already under way has, in reality, fixed its rate
 *       at the start - and Mercury stores no past fixings. Such a period is projected from the
 *       valuation date instead, which is exact for a swap starting today and an approximation
 *       for a seasoned one. Named here rather than silently applied.</li>
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

        // The floating leg, unsigned - the par rate is a property of the schedule and the
        // curve, not of which way round the holder trades it.
        double floating = Math.abs(CashflowDiscounting.presentValue(
                projectedCashflows(swap.floatingLeg(), market, asOf), market, currency));

        return floating / (annuity * notional);
    }

    /**
     * Each unpaid floating period, projected off the curve and turned into a dated amount.
     *
     * <p>A period already under way is projected from the valuation date rather than from its
     * own start. Mercury holds no fixing history, and a curve cannot discount a date in the
     * past - it would compound it forward, inflating a rate that was set weeks ago. Clamping
     * is exact for a swap that starts today and an approximation for a seasoned one.
     */
    private static List<Cashflow> projectedCashflows(FloatingRateLeg leg,
                                                     MarketDataSnapshot market, LocalDate asOf) {
        YieldCurve curve = market.yieldCurve(leg.currency());
        List<SchedulePeriod> remaining = leg.unpaidPeriods(asOf);
        List<Cashflow> cashflows = new ArrayList<>(remaining.size());

        for (SchedulePeriod period : remaining) {
            LocalDate start = period.accrualStart().isBefore(asOf) ? asOf : period.accrualStart();
            double projected = curve.simpleForwardRate(start, period.accrualEnd(), leg.dayCount());
            Money coupon = leg.couponFor(period, projected);
            cashflows.add(new Cashflow(period.paymentDate(), coupon));
        }
        return cashflows;
    }
}
