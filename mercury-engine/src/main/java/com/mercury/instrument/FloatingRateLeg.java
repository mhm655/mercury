package com.mercury.instrument;

import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Money;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.Schedule;
import com.mercury.core.time.SchedulePeriod;
import java.util.List;
import java.util.Objects;

/**
 * A swap leg paying a benchmark rate that resets each period, plus an optional spread.
 *
 * <h2>Why this does not implement CashflowGenerating</h2>
 * It cannot, honestly. Each coupon is
 * {@code notional x (indexRate + spread) x yearFraction}, and {@code indexRate} is a
 * forward rate that has to be projected from a discount curve. An instrument definition has
 * no curve and should not: pricing is a pure function of instrument <em>and</em> market
 * data, and an instrument that reached out for market data on its own would break that.
 *
 * <p>The alternative - implementing the interface and returning zeros, or last-known rates,
 * or estimates - would be worse than not implementing it. Callers would receive
 * {@link Cashflow}s carrying real-looking {@link Money} amounts that are not the contract's
 * amounts, and nothing in the type would warn them. Leaving the interface unimplemented
 * makes the compiler enforce the distinction instead.
 *
 * <p>So this leg exposes what a pricer needs in order to do the projection itself -
 * schedule, index, spread, day count - and the projection happens at M6 where the curve
 * exists. This is the concrete case behind the correction noted in
 * {@link CashflowGenerating}.
 *
 * <p>Immutable and thread-safe.
 */
public record FloatingRateLeg(
        Money notional,
        FloatingRateIndex index,
        BasisPoints spread,
        Schedule schedule,
        PayReceive payReceive,
        DayCountConvention dayCount)
        implements SwapLeg {

    public FloatingRateLeg {
        Objects.requireNonNull(notional, "notional");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(spread, "spread");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(payReceive, "payReceive");
        Objects.requireNonNull(dayCount, "dayCount");

        if (!notional.isPositive()) {
            throw new IllegalArgumentException(
                    "Swap leg notional must be positive (direction is carried by PayReceive), "
                            + "but was " + notional);
        }
        if (notional.currency() != index.currency()) {
            throw new IllegalArgumentException(
                    "Floating leg notional is in " + notional.currency().code()
                            + " but resets against " + index.name() + ", a "
                            + index.currency().code() + " index. A leg cannot accrue a rate "
                            + "published for another currency.");
        }
    }

    /** A leg with no spread over the index. */
    public static FloatingRateLeg of(Money notional, FloatingRateIndex index, Schedule schedule,
                                     PayReceive payReceive) {
        return new FloatingRateLeg(notional, index, BasisPoints.ZERO, schedule, payReceive,
                index.dayCount());
    }

    /**
     * The coupon for one period, once the index rate for it is known.
     *
     * <p>Takes the projected rate as an argument rather than fetching it: that keeps this
     * type a pure description of the contract, and puts the responsibility for projection
     * with the pricer that holds the curve.
     *
     * @param period          the accrual period
     * @param projectedRate   the index fixing as a decimal, e.g. {@code 0.0425} for 4.25%
     * @return the signed coupon, negative if this leg is paid
     */
    public Money couponFor(SchedulePeriod period, double projectedRate) {
        Objects.requireNonNull(period, "period");
        if (!Double.isFinite(projectedRate)) {
            throw new IllegalArgumentException(
                    "Projected rate must be finite, but was " + projectedRate);
        }
        double effectiveRate = projectedRate + spread.asDecimal();
        double yearFraction = period.yearFraction(dayCount);
        Money gross = notional.multipliedBy(
                java.math.BigDecimal.valueOf(effectiveRate * yearFraction));
        return payReceive == PayReceive.PAY ? gross.negated() : gross;
    }

    /** Periods still to be paid as of {@code valuationDate}, for a pricer to project over. */
    public List<SchedulePeriod> unpaidPeriods(java.time.LocalDate valuationDate) {
        return schedule.unpaidPeriodsAsOf(valuationDate);
    }

    @Override
    public String toString() {
        String spreadText = spread.value() == 0.0 ? "" : " + " + spread;
        return "%s %s%s on %s".formatted(payReceive.displayName(), index, spreadText, notional);
    }
}
