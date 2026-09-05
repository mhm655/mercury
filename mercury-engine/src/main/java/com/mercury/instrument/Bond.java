package com.mercury.instrument;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.time.BusinessDayConvention;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.Frequency;
import com.mercury.core.time.HolidayCalendar;
import com.mercury.core.time.Schedule;
import com.mercury.core.time.ScheduleGenerator;
import com.mercury.core.time.SchedulePeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A fixed-rate bullet bond: periodic fixed coupons, principal repaid in full at maturity.
 *
 * <h2>Scope</h2>
 * Deliberately only the vanilla case. No amortisation, no call or put schedules, no
 * floating-rate notes, no inflation linkage. Each of those is a real product and each would
 * add branching here for no architectural gain - the interesting design question (how does
 * a cashflow-bearing instrument expose itself to a generic pricer) is already answered by
 * the vanilla case. Naming the restriction is more honest than implying full coverage.
 *
 * <h2>Coupon calculation</h2>
 * Each coupon is {@code faceValue x couponRate x yearFraction}, where the year fraction
 * comes from the bond's day count convention over its accrual period. The coupon rate is a
 * {@link BigDecimal} because it is a contract term and exact ("5%" means exactly 5%); the
 * year fraction is a {@code double} because it is computed. They are multiplied together
 * before touching {@code Money}, so the amount rounds exactly once - see ADR 0001.
 *
 * <p>Immutable and thread-safe. The schedule is generated once at construction.
 */
public final class Bond implements FinancialInstrument, CashflowGenerating, Maturing {

    private final InstrumentId id;
    private final String name;
    private final Money faceValue;
    private final BigDecimal couponRate;
    private final Frequency couponFrequency;
    private final DayCountConvention dayCount;
    private final LocalDate issueDate;
    private final LocalDate maturityDate;
    private final Schedule schedule;

    private Bond(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.faceValue = builder.faceValue;
        this.couponRate = builder.couponRate;
        this.couponFrequency = builder.couponFrequency;
        this.dayCount = builder.dayCount;
        this.issueDate = builder.issueDate;
        this.maturityDate = builder.maturityDate;
        this.schedule = ScheduleGenerator.generate(
                builder.issueDate, builder.maturityDate, builder.couponFrequency,
                builder.businessDayConvention, builder.calendar);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------ instrument

    @Override
    public InstrumentId id() {
        return id;
    }

    @Override
    public Currency currency() {
        return faceValue.currency();
    }

    @Override
    public AssetClass assetClass() {
        return AssetClass.RATES;
    }

    @Override
    public TradabilityProfile tradability() {
        return TradabilityProfile.EXCHANGE_TRADED;
    }

    @Override
    public String description() {
        return "%s %s%% %s".formatted(
                name, couponRate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString(),
                maturityDate);
    }

    @Override
    public LocalDate maturityDate() {
        return maturityDate;
    }

    // ------------------------------------------------------------- cashflows

    /**
     * Coupons falling after {@code from}, with the principal added to the final payment.
     *
     * <p>Principal and the last coupon share a payment date by construction - the schedule
     * is generated backwards from maturity precisely so that they coincide (ADR 0003). They
     * are returned as a single combined cashflow rather than two on the same date, because
     * that is what actually settles.
     */
    @Override
    public List<Cashflow> cashflows(LocalDate from) {
        Objects.requireNonNull(from, "from");
        List<SchedulePeriod> remaining = schedule.unpaidPeriodsAsOf(from);
        List<Cashflow> cashflows = new ArrayList<>(remaining.size());

        for (int i = 0; i < remaining.size(); i++) {
            SchedulePeriod period = remaining.get(i);
            Money amount = couponFor(period);
            boolean isFinal = i == remaining.size() - 1
                    && period.accrualEnd().equals(schedule.maturityDate());
            if (isFinal) {
                amount = amount.plus(faceValue);
            }
            cashflows.add(new Cashflow(period.paymentDate(), amount));
        }
        return List.copyOf(cashflows);
    }

    /** The coupon for one accrual period. */
    public Money couponFor(SchedulePeriod period) {
        double yearFraction = period.yearFraction(dayCount);
        return faceValue.multipliedBy(couponRate.multiply(BigDecimal.valueOf(yearFraction)));
    }

    /**
     * Accrued interest as of {@code valuationDate} - the portion of the current coupon the
     * seller has earned but not yet been paid.
     *
     * <p>Bond prices are quoted "clean", excluding this; the buyer pays the clean price plus
     * accrued. Returns zero outside the bond's life.
     */
    public Money accruedInterest(LocalDate valuationDate) {
        Objects.requireNonNull(valuationDate, "valuationDate");
        for (SchedulePeriod period : schedule.periods()) {
            boolean inPeriod = !valuationDate.isBefore(period.accrualStart())
                    && valuationDate.isBefore(period.accrualEnd());
            if (inPeriod) {
                double accrued = dayCount.yearFraction(period.accrualStart(), valuationDate);
                return faceValue.multipliedBy(couponRate.multiply(BigDecimal.valueOf(accrued)));
            }
        }
        return Money.zero(currency());
    }

    // ------------------------------------------------------------- accessors

    public String name() {
        return name;
    }

    public Money faceValue() {
        return faceValue;
    }

    /** The annual coupon rate as a decimal: 5% is {@code 0.05}. */
    public BigDecimal couponRate() {
        return couponRate;
    }

    public Frequency couponFrequency() {
        return couponFrequency;
    }

    public DayCountConvention dayCount() {
        return dayCount;
    }

    public LocalDate issueDate() {
        return issueDate;
    }

    public Schedule schedule() {
        return schedule;
    }

    /** True if this bond pays no coupons and redeems at par. */
    public boolean isZeroCoupon() {
        return couponRate.signum() == 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Bond other && id.equals(other.id);
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
     * Builds a {@link Bond}.
     *
     * <p>A builder is justified here, unlike for {@link Stock}: there are eight parameters,
     * three of them optional with sensible market defaults, and several share a type. A
     * constructor taking {@code (LocalDate, LocalDate)} and
     * {@code (Frequency, DayCountConvention, BusinessDayConvention)} positionally is a
     * standing invitation to swap two arguments and compile cleanly.
     */
    public static final class Builder {

        private InstrumentId id;
        private String name;
        private Money faceValue;
        private BigDecimal couponRate;
        private Frequency couponFrequency = Frequency.SEMI_ANNUAL;
        private DayCountConvention dayCount = DayCountConvention.THIRTY_360_US;
        private BusinessDayConvention businessDayConvention = BusinessDayConvention.MODIFIED_FOLLOWING;
        private HolidayCalendar calendar = HolidayCalendar.weekendsOnly();
        private LocalDate issueDate;
        private LocalDate maturityDate;

        private Builder() {
        }

        public Builder id(InstrumentId id) {
            this.id = id;
            return this;
        }

        public Builder id(String id) {
            return id(InstrumentId.of(id));
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder faceValue(Money faceValue) {
            this.faceValue = faceValue;
            return this;
        }

        /** The annual coupon rate as a decimal: 5% is {@code 0.05}. */
        public Builder couponRate(BigDecimal couponRate) {
            this.couponRate = couponRate;
            return this;
        }

        /** The annual coupon rate as a decimal string: {@code "0.05"} for 5%. */
        public Builder couponRate(String couponRate) {
            return couponRate(new BigDecimal(couponRate));
        }

        /** Defaults to semi-annual, the US and UK government bond convention. */
        public Builder couponFrequency(Frequency couponFrequency) {
            this.couponFrequency = couponFrequency;
            return this;
        }

        /** Defaults to 30/360 US, the corporate bond convention. */
        public Builder dayCount(DayCountConvention dayCount) {
            this.dayCount = dayCount;
            return this;
        }

        /** Defaults to Modified Following. */
        public Builder businessDayConvention(BusinessDayConvention convention) {
            this.businessDayConvention = convention;
            return this;
        }

        /** Defaults to weekends only. */
        public Builder calendar(HolidayCalendar calendar) {
            this.calendar = calendar;
            return this;
        }

        public Builder issueDate(LocalDate issueDate) {
            this.issueDate = issueDate;
            return this;
        }

        public Builder maturityDate(LocalDate maturityDate) {
            this.maturityDate = maturityDate;
            return this;
        }

        public Bond build() {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(faceValue, "faceValue");
            Objects.requireNonNull(couponRate, "couponRate");
            Objects.requireNonNull(issueDate, "issueDate");
            Objects.requireNonNull(maturityDate, "maturityDate");
            Objects.requireNonNull(couponFrequency, "couponFrequency");
            Objects.requireNonNull(dayCount, "dayCount");
            Objects.requireNonNull(businessDayConvention, "businessDayConvention");
            Objects.requireNonNull(calendar, "calendar");

            if (name == null) {
                name = id.value();
            }
            if (!faceValue.isPositive()) {
                throw new IllegalArgumentException(
                        "Bond face value must be positive, but was " + faceValue);
            }
            if (couponRate.signum() < 0) {
                throw new IllegalArgumentException(
                        "Bond coupon rate must not be negative, but was " + couponRate
                                + ". A negative-yielding bond is priced by its market price, "
                                + "not by a negative contractual coupon.");
            }
            if (!maturityDate.isAfter(issueDate)) {
                throw new IllegalArgumentException(
                        "Bond maturity " + maturityDate + " must be after issue date " + issueDate);
            }
            return new Bond(this);
        }
    }
}
