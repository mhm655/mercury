package com.mercury.curve;

import com.mercury.core.time.BusinessDayConvention;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.HolidayCalendar;
import com.mercury.core.time.Tenor;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A money-market deposit: cash lent for one short period at a simple rate.
 *
 * <h2>What builds the short end of a curve</h2>
 * Deposits are how the near-dated pillars are quoted - overnight out to a year - because for
 * a single period with no intermediate payments there is nothing to bootstrap iteratively.
 * The discount factor follows in one step:
 *
 * <pre>
 *   DF = 1 / (1 + r x tau)
 * </pre>
 *
 * <h2>Simple interest, and why that is not a detail</h2>
 * A deposit rate is quoted on <b>simple</b> interest over the period: not compounded, and not
 * continuous. The curve stores continuously-compounded zero rates. Those are different
 * numbers for the same market - 5% simple over a year is 4.879% continuously compounded, a
 * gap of twelve basis points that would be invisible in the output and wrong in every price.
 *
 * <p>Reading a quote on the wrong compounding basis is one of the most common errors in curve
 * construction precisely because nothing complains: the curve builds, the numbers look
 * plausible, and everything is slightly off. The conversion happens here, in the type that
 * knows what basis its own quote is on, rather than in a solver that would have to be told.
 *
 * <h2>ACT/360, because that is the market convention</h2>
 * USD and EUR money markets accrue ACT/360, so a "one year" deposit accrues 365/360 of a
 * year's interest. Defaulted rather than assumed, and overridable for markets that use
 * ACT/365 - sterling, among others.
 *
 * <p>Immutable and thread-safe.
 */
public record DepositQuote(
        Tenor tenor,
        double simpleRate,
        DayCountConvention dayCount,
        BusinessDayConvention businessDayConvention,
        HolidayCalendar calendar) implements CurveInstrument {

    public DepositQuote {
        Objects.requireNonNull(tenor, "tenor");
        Objects.requireNonNull(dayCount, "dayCount");
        Objects.requireNonNull(businessDayConvention, "businessDayConvention");
        Objects.requireNonNull(calendar, "calendar");
        if (!Double.isFinite(simpleRate)) {
            throw new IllegalArgumentException(
                    "Deposit rate at " + tenor + " must be finite, but was " + simpleRate);
        }
    }

    /** A deposit on the money-market convention: ACT/360, modified following over weekends. */
    public static DepositQuote of(Tenor tenor, double simpleRate) {
        return new DepositQuote(tenor, simpleRate, DayCountConvention.ACT_360,
                BusinessDayConvention.MODIFIED_FOLLOWING, HolidayCalendar.weekendsOnly());
    }

    /**
     * The adjusted maturity - when the cash actually comes back.
     *
     * <p>Rolled to a business day, not left on the nominal tenor date. A deposit maturing on a
     * Saturday does not exist, and putting the pillar where the money is not would repeat the
     * mistake documented on {@link CurveInstrument}.
     */
    @Override
    public LocalDate pillarDate(LocalDate referenceDate) {
        Objects.requireNonNull(referenceDate, "referenceDate");
        return businessDayConvention.adjust(tenor.addTo(referenceDate), calendar);
    }

    @Override
    public double repricingError(YieldCurve curve) {
        Objects.requireNonNull(curve, "curve");
        LocalDate reference = curve.referenceDate();
        LocalDate maturity = pillarDate(reference);

        // Two day counts, on purpose. The accrual fraction uses the deposit's own convention,
        // because that is what the counterparty actually pays interest on. The curve's own
        // horizon uses the curve's convention, because that is where the pillar sits. Using
        // one for both is a quiet way to misplace the pillar by a few days.
        double accrualFraction = dayCount.yearFraction(reference, maturity);
        double quotedDiscountFactor = 1.0 / (1.0 + simpleRate * accrualFraction);

        return curve.discountFactor(maturity) - quotedDiscountFactor;
    }

    @Override
    public String describe() {
        return "deposit " + tenor + " @ " + simpleRate;
    }
}
