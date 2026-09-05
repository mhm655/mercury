package com.mercury.instrument;

import com.mercury.core.money.Money;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.Schedule;
import com.mercury.core.time.SchedulePeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A swap leg paying a fixed rate on a fixed notional.
 *
 * <p>Every coupon is known at trade time - {@code notional x rate x yearFraction} - so this
 * implements {@link CashflowGenerating} and can be discounted by the same engine that
 * prices a bond. That reuse is the point of the capability interface.
 *
 * <p>Note there is no principal exchange. A single-currency interest-rate swap exchanges
 * interest only; the notionals are equal and in the same currency, so exchanging them would
 * be two identical payments cancelling out. This is a real difference from a bond, which is
 * otherwise the same cashflow shape, and it is why a swap is not modelled as a pair of
 * bonds.
 *
 * <p>Immutable and thread-safe.
 */
public record FixedRateLeg(
        Money notional,
        BigDecimal fixedRate,
        Schedule schedule,
        PayReceive payReceive,
        DayCountConvention dayCount)
        implements SwapLeg, CashflowGenerating {

    public FixedRateLeg {
        Objects.requireNonNull(notional, "notional");
        Objects.requireNonNull(fixedRate, "fixedRate");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(payReceive, "payReceive");
        Objects.requireNonNull(dayCount, "dayCount");

        if (!notional.isPositive()) {
            throw new IllegalArgumentException(
                    "Swap leg notional must be positive (direction is carried by PayReceive), "
                            + "but was " + notional);
        }
    }

    /**
     * The fixed coupons falling after {@code from}, signed by direction.
     *
     * <p>A paid leg yields negative amounts and a received leg positive ones, so the two
     * legs of a swap can simply be summed to get its net cashflow profile.
     */
    @Override
    public List<Cashflow> cashflows(LocalDate from) {
        Objects.requireNonNull(from, "from");
        List<SchedulePeriod> remaining = schedule.unpaidPeriodsAsOf(from);
        List<Cashflow> cashflows = new ArrayList<>(remaining.size());
        for (SchedulePeriod period : remaining) {
            cashflows.add(new Cashflow(period.paymentDate(), couponFor(period)));
        }
        return List.copyOf(cashflows);
    }

    /** The signed coupon for one accrual period. */
    public Money couponFor(SchedulePeriod period) {
        double yearFraction = period.yearFraction(dayCount);
        Money gross = notional.multipliedBy(fixedRate.multiply(BigDecimal.valueOf(yearFraction)));
        return payReceive == PayReceive.PAY ? gross.negated() : gross;
    }

    @Override
    public String toString() {
        return "%s fixed %s%% on %s".formatted(
                payReceive.displayName(),
                fixedRate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString(),
                notional);
    }
}
