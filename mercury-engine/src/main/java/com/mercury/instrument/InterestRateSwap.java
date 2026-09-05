package com.mercury.instrument;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.time.BusinessDayConvention;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.Frequency;
import com.mercury.core.time.HolidayCalendar;
import com.mercury.core.time.Schedule;
import com.mercury.core.time.ScheduleGenerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A vanilla single-currency fixed-for-floating interest rate swap.
 *
 * <h2>Composition, not inheritance</h2>
 * A swap <em>has</em> two legs. It is not a kind of leg, and neither leg is a kind of swap.
 * Composing {@link FixedRateLeg} and {@link FloatingRateLeg} rather than building an
 * inheritance hierarchy means a basis swap (two floating legs) or a cross-currency swap
 * (legs in different currencies) is a different composition of the same parts, not a new
 * subclass. Neither is implemented here, but neither would require restructuring what is.
 *
 * <h2>Why this is not CashflowGenerating</h2>
 * Half of its cashflows are unknown until a curve projects them, so the swap as a whole
 * cannot honestly promise contractual cashflows. {@link #fixedLeg()} can and does; the
 * floating leg exposes its terms for a pricer to project. See {@link CashflowGenerating} for
 * the full reasoning - this is the instrument that motivated it.
 *
 * <h2>Direction</h2>
 * "Paying fixed" is the market's shorthand for paying the fixed leg and receiving the
 * floating one; a payer swap gains value when rates rise. {@link #isPayerSwap()} reports it.
 *
 * <h2>Scope</h2>
 * Single currency, equal notionals on both legs, no amortisation, no principal exchange.
 * Simplifications named rather than implied.
 *
 * <p>Immutable and thread-safe.
 */
public final class InterestRateSwap implements FinancialInstrument, Maturing {

    private final InstrumentId id;
    private final FixedRateLeg fixedLeg;
    private final FloatingRateLeg floatingLeg;

    private InterestRateSwap(InstrumentId id, FixedRateLeg fixedLeg, FloatingRateLeg floatingLeg) {
        this.id = id;
        this.fixedLeg = fixedLeg;
        this.floatingLeg = floatingLeg;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Assembles a swap from two legs built elsewhere.
     *
     * @throws IllegalArgumentException if the legs disagree on currency, notional or
     *                                  direction, or if they mature on different dates
     */
    public static InterestRateSwap of(InstrumentId id, FixedRateLeg fixedLeg,
                                      FloatingRateLeg floatingLeg) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fixedLeg, "fixedLeg");
        Objects.requireNonNull(floatingLeg, "floatingLeg");

        if (fixedLeg.currency() != floatingLeg.currency()) {
            throw new IllegalArgumentException(
                    "Both legs of a single-currency swap must share a currency, but the fixed leg "
                            + "is " + fixedLeg.currency().code() + " and the floating leg is "
                            + floatingLeg.currency().code()
                            + ". A cross-currency swap is a different instrument.");
        }
        if (!fixedLeg.notional().equals(floatingLeg.notional())) {
            throw new IllegalArgumentException(
                    "Both legs must share a notional, but the fixed leg is "
                            + fixedLeg.notional() + " and the floating leg is "
                            + floatingLeg.notional());
        }
        if (fixedLeg.payReceive() == floatingLeg.payReceive()) {
            throw new IllegalArgumentException(
                    "A swap exchanges cashflows, so its legs must run in opposite directions, "
                            + "but both are set to " + fixedLeg.payReceive().displayName());
        }
        if (!fixedLeg.schedule().maturityDate().equals(floatingLeg.schedule().maturityDate())) {
            throw new IllegalArgumentException(
                    "Both legs must mature together, but the fixed leg matures "
                            + fixedLeg.schedule().maturityDate() + " and the floating leg "
                            + floatingLeg.schedule().maturityDate());
        }
        return new InterestRateSwap(id, fixedLeg, floatingLeg);
    }

    // ------------------------------------------------------------ instrument

    @Override
    public InstrumentId id() {
        return id;
    }

    @Override
    public Currency currency() {
        return fixedLeg.currency();
    }

    @Override
    public AssetClass assetClass() {
        return AssetClass.RATES;
    }

    @Override
    public TradabilityProfile tradability() {
        return TradabilityProfile.OVER_THE_COUNTER;
    }

    @Override
    public LocalDate maturityDate() {
        return fixedLeg.schedule().maturityDate();
    }

    @Override
    public String description() {
        return "%s %s%% %s swap to %s".formatted(
                isPayerSwap() ? "Pay" : "Receive",
                fixedLeg.fixedRate().multiply(BigDecimal.valueOf(100))
                        .stripTrailingZeros().toPlainString(),
                notional(), maturityDate());
    }

    // ------------------------------------------------------------- accessors

    /** The leg whose cashflows are known at trade time. */
    public FixedRateLeg fixedLeg() {
        return fixedLeg;
    }

    /** The leg whose cashflows must be projected from a curve. */
    public FloatingRateLeg floatingLeg() {
        return floatingLeg;
    }

    /** Shared by both legs. */
    public Money notional() {
        return fixedLeg.notional();
    }

    /** The contractual fixed rate as a decimal: 4.25% is {@code 0.0425}. */
    public BigDecimal fixedRate() {
        return fixedLeg.fixedRate();
    }

    /** True if the holder pays fixed and receives floating - a position that gains as rates rise. */
    public boolean isPayerSwap() {
        return fixedLeg.payReceive() == PayReceive.PAY;
    }

    public LocalDate effectiveDate() {
        return fixedLeg.schedule().effectiveDate();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof InterestRateSwap other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return description();
    }

    /**
     * Builds a vanilla swap from its economic terms, generating both schedules.
     *
     * <p>A builder earns its place here more clearly than anywhere else in the project:
     * eleven parameters, two independent frequencies, two independent day counts, and a
     * direction. Positional construction would be unreadable and trivially misordered - the
     * two {@code Frequency} arguments alone could be swapped silently.
     *
     * <p>Defaults follow the USD vanilla convention: annual 30/360 fixed against quarterly
     * ACT/360 SOFR. The two legs conventionally pay at different frequencies, which is why
     * they are configured separately rather than sharing one setting.
     */
    public static final class Builder {

        private InstrumentId id;
        private Money notional;
        private BigDecimal fixedRate;
        private PayReceive fixedLegDirection = PayReceive.PAY;
        private Frequency fixedFrequency = Frequency.ANNUAL;
        private DayCountConvention fixedDayCount = DayCountConvention.THIRTY_360_US;
        private Frequency floatingFrequency = Frequency.QUARTERLY;
        private FloatingRateIndex index;
        private BasisPoints spread = BasisPoints.ZERO;
        private LocalDate effectiveDate;
        private LocalDate maturityDate;
        private BusinessDayConvention businessDayConvention = BusinessDayConvention.MODIFIED_FOLLOWING;
        private HolidayCalendar calendar = HolidayCalendar.weekendsOnly();

        private Builder() {
        }

        public Builder id(InstrumentId id) {
            this.id = id;
            return this;
        }

        public Builder id(String id) {
            return id(InstrumentId.of(id));
        }

        public Builder notional(Money notional) {
            this.notional = notional;
            return this;
        }

        /** The fixed rate as a decimal: {@code "0.0425"} for 4.25%. */
        public Builder fixedRate(String fixedRate) {
            this.fixedRate = new BigDecimal(fixedRate);
            return this;
        }

        public Builder fixedRate(BigDecimal fixedRate) {
            this.fixedRate = fixedRate;
            return this;
        }

        /** Pay fixed, receive floating. Gains value as rates rise. This is the default. */
        public Builder payingFixed() {
            this.fixedLegDirection = PayReceive.PAY;
            return this;
        }

        /** Receive fixed, pay floating. Gains value as rates fall. */
        public Builder receivingFixed() {
            this.fixedLegDirection = PayReceive.RECEIVE;
            return this;
        }

        /** Defaults to annual. */
        public Builder fixedFrequency(Frequency frequency) {
            this.fixedFrequency = frequency;
            return this;
        }

        /** Defaults to 30/360 US. */
        public Builder fixedDayCount(DayCountConvention dayCount) {
            this.fixedDayCount = dayCount;
            return this;
        }

        /** Defaults to quarterly. */
        public Builder floatingFrequency(Frequency frequency) {
            this.floatingFrequency = frequency;
            return this;
        }

        /** The floating leg accrues on the index's own day count convention. */
        public Builder index(FloatingRateIndex index) {
            this.index = index;
            return this;
        }

        /** Spread over the index. Defaults to zero. */
        public Builder spread(BasisPoints spread) {
            this.spread = spread;
            return this;
        }

        public Builder effectiveDate(LocalDate effectiveDate) {
            this.effectiveDate = effectiveDate;
            return this;
        }

        public Builder maturityDate(LocalDate maturityDate) {
            this.maturityDate = maturityDate;
            return this;
        }

        public Builder businessDayConvention(BusinessDayConvention convention) {
            this.businessDayConvention = convention;
            return this;
        }

        public Builder calendar(HolidayCalendar calendar) {
            this.calendar = calendar;
            return this;
        }

        public InterestRateSwap build() {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(notional, "notional");
            Objects.requireNonNull(fixedRate, "fixedRate");
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(effectiveDate, "effectiveDate");
            Objects.requireNonNull(maturityDate, "maturityDate");

            Schedule fixedSchedule = ScheduleGenerator.generate(
                    effectiveDate, maturityDate, fixedFrequency, businessDayConvention, calendar);
            Schedule floatingSchedule = ScheduleGenerator.generate(
                    effectiveDate, maturityDate, floatingFrequency, businessDayConvention, calendar);

            FixedRateLeg fixed = new FixedRateLeg(
                    notional, fixedRate, fixedSchedule, fixedLegDirection, fixedDayCount);
            FloatingRateLeg floating = new FloatingRateLeg(
                    notional, index, spread, floatingSchedule,
                    fixedLegDirection.opposite(), index.dayCount());

            return of(id, fixed, floating);
        }
    }
}
