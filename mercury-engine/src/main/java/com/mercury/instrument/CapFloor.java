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
import java.time.LocalDate;
import java.util.Objects;

/**
 * An interest-rate cap or floor: a strip of options on a floating rate.
 *
 * <h2>The third kind of cashflow</h2>
 * Mercury has held two kinds of payment until now. A bond's coupons are <em>known</em>, so it
 * implements {@link CashflowGenerating}. A swap's floating coupons are <em>projected</em> from
 * a curve, which is why {@link FloatingRateLeg} deliberately does not. A cap pays a third kind:
 * <em>contingent</em>. Each caplet pays only if the index fixes above the strike, and how much
 * it pays is not determined by the curve at all - it depends on the distribution of where the
 * rate might go.
 *
 * <p>So this implements neither cashflow interface, for the same reason and one step further
 * along. There is no honest set of {@code Cashflow}s to hand back, because the amounts are not
 * merely unknown, they are random. The capability interfaces make that structural rather than
 * something a caller has to remember.
 *
 * <h2>A caplet is a call on the forward rate</h2>
 * Which is why this reuses {@link OptionType} rather than introducing a cap-or-floor enum of
 * its own. A cap is a strip of calls on the index; a floor is a strip of puts. The vocabulary
 * already in the engine says exactly the right thing, and
 * {@link OptionType#intrinsicValue(double, double)} is already the payoff a caplet has at its
 * fixing date.
 *
 * <h2>Scope</h2>
 * One strike for the whole strip, no digital or barrier variants, no amortisation. A real cap
 * is quoted against a volatility surface by expiry and strike; this one carries a single
 * volatility, which is the same simplification {@link EuropeanOption} makes and is named in
 * both places rather than assumed.
 *
 * <p>Immutable and thread-safe. The schedule is generated once at construction.
 */
public final class CapFloor implements FinancialInstrument, Maturing {

    private final InstrumentId id;
    private final Money notional;
    private final double strike;
    private final OptionType type;
    private final FloatingRateIndex index;
    private final Schedule schedule;

    private CapFloor(InstrumentId id, Money notional, double strike, OptionType type,
                     FloatingRateIndex index, Schedule schedule) {
        this.id = id;
        this.notional = notional;
        this.strike = strike;
        this.type = type;
        this.index = index;
        this.schedule = schedule;
    }

    public static Builder cap() {
        return new Builder(OptionType.CALL);
    }

    public static Builder floor() {
        return new Builder(OptionType.PUT);
    }

    // ------------------------------------------------------------ instrument

    @Override
    public InstrumentId id() {
        return id;
    }

    @Override
    public Currency currency() {
        return notional.currency();
    }

    @Override
    public AssetClass assetClass() {
        return AssetClass.RATES;
    }

    @Override
    public TradabilityProfile tradability() {
        return TradabilityProfile.OVER_THE_COUNTER;
    }

    /** The final caplet's payment date - when the last money could move. */
    @Override
    public LocalDate maturityDate() {
        return schedule.last().paymentDate();
    }

    @Override
    public String description() {
        return "%s %s%% on %s to %s".formatted(
                isCap() ? "Cap" : "Floor", strike * 100.0, notional, maturityDate());
    }

    // ------------------------------------------------------------- accessors

    public Money notional() {
        return notional;
    }

    /** The strike as a decimal rate: {@code 0.045} is 4.5%. */
    public double strike() {
        return strike;
    }

    /** {@link OptionType#CALL} for a cap, {@link OptionType#PUT} for a floor. */
    public OptionType optionType() {
        return type;
    }

    public FloatingRateIndex index() {
        return index;
    }

    /** One period per caplet. */
    public Schedule schedule() {
        return schedule;
    }

    /** How each caplet accrues, taken from the index that sets it. */
    public DayCountConvention dayCount() {
        return index.dayCount();
    }

    public boolean isCap() {
        return type == OptionType.CALL;
    }

    /**
     * Entity equality: two instruments are the same when their ids match.
     * See {@link FinancialInstrument} for why identity rather than structure.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof CapFloor other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return description();
    }

    /** Builds a cap or a floor, generating the caplet schedule from the index tenor. */
    public static final class Builder {

        private final OptionType type;
        private InstrumentId id;
        private Money notional;
        private Double strike;
        private FloatingRateIndex index;
        private LocalDate effectiveDate;
        private LocalDate maturityDate;
        private Frequency frequency = Frequency.QUARTERLY;

        // Fixed rather than configurable, because nothing asks. Every other dated instrument
        // in the engine defaults to these and none of them has needed anything else; the
        // no-orphaned-API rule flagged the setters the moment they were written, which is
        // exactly the speculative generality it exists to catch. Adding them back is one line
        // on the day a caller wants them.
        private final BusinessDayConvention businessDayConvention =
                BusinessDayConvention.MODIFIED_FOLLOWING;
        private final HolidayCalendar calendar = HolidayCalendar.weekendsOnly();

        private Builder(OptionType type) {
            this.type = type;
        }

        public Builder id(String id) {
            this.id = InstrumentId.of(id);
            return this;
        }

        public Builder notional(Money notional) {
            this.notional = notional;
            return this;
        }

        /** The strike as a decimal rate: {@code 0.045} is 4.5%. */
        public Builder strike(double strike) {
            this.strike = strike;
            return this;
        }

        public Builder index(FloatingRateIndex index) {
            this.index = index;
            return this;
        }

        /** Defaults to quarterly, matching the usual three-month index. */
        public Builder frequency(Frequency frequency) {
            this.frequency = frequency;
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

        public CapFloor build() {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(notional, "notional");
            Objects.requireNonNull(strike, "strike");
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(effectiveDate, "effectiveDate");
            Objects.requireNonNull(maturityDate, "maturityDate");
            Objects.requireNonNull(frequency, "frequency");
            Objects.requireNonNull(businessDayConvention, "businessDayConvention");
            Objects.requireNonNull(calendar, "calendar");

            if (!notional.isPositive()) {
                throw new IllegalArgumentException(
                        "Cap notional must be positive, but was " + notional
                                + ". A sold cap is a negative position, not a negative notional.");
            }
            if (notional.currency() != index.currency()) {
                throw new IllegalArgumentException(
                        "Notional is in " + notional.currency().code() + " but the strip fixes "
                                + "against " + index.name() + ", a " + index.currency().code()
                                + " index. A caplet cannot pay on a rate published for another "
                                + "currency.");
            }
            if (!Double.isFinite(strike)) {
                throw new IllegalArgumentException("Strike must be finite, but was " + strike);
            }
            if (!maturityDate.isAfter(effectiveDate)) {
                throw new IllegalArgumentException(
                        "Cap maturity " + maturityDate + " must be after its start "
                                + effectiveDate);
            }

            Schedule schedule = ScheduleGenerator.generate(
                    effectiveDate, maturityDate, frequency, businessDayConvention, calendar);
            return new CapFloor(id, notional, strike, type, index, schedule);
        }
    }
}
