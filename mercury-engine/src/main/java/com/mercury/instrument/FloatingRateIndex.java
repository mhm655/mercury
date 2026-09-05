package com.mercury.instrument;

import com.mercury.core.money.Currency;
import com.mercury.core.time.DayCountConvention;
import com.mercury.core.time.Tenor;
import java.util.Objects;

/**
 * A published benchmark interest rate that a floating leg resets against.
 *
 * <p>An index is identified by its currency, its tenor and its accrual convention -
 * "3-month USD SOFR" is a different index from "6-month USD SOFR", and they generally have
 * different rates. Modelling it as a value rather than a bare string means a floating leg
 * cannot accidentally be built against an index in the wrong currency, and the day count
 * used to accrue the coupon travels with the index that set it.
 *
 * <p>Immutable and thread-safe.
 */
public record FloatingRateIndex(String name, Currency currency, Tenor tenor,
                                DayCountConvention dayCount) {

    public FloatingRateIndex {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(tenor, "tenor");
        Objects.requireNonNull(dayCount, "dayCount");
        name = name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Floating rate index name must not be blank");
        }
    }

    /** 3-month USD SOFR, accruing on Actual/360 as USD money-market rates do. */
    public static FloatingRateIndex usdSofr3M() {
        return new FloatingRateIndex("USD-SOFR", Currency.USD, Tenor.months(3),
                DayCountConvention.ACT_360);
    }

    /** 6-month EURIBOR, Actual/360. */
    public static FloatingRateIndex euribor6M() {
        return new FloatingRateIndex("EURIBOR", Currency.EUR, Tenor.months(6),
                DayCountConvention.ACT_360);
    }

    /** 6-month SONIA, Actual/365 Fixed as sterling rates conventionally accrue. */
    public static FloatingRateIndex gbpSonia6M() {
        return new FloatingRateIndex("GBP-SONIA", Currency.GBP, Tenor.months(6),
                DayCountConvention.ACT_365F);
    }

    @Override
    public String toString() {
        return name + "-" + tenor;
    }
}
