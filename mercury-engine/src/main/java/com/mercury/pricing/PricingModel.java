package com.mercury.pricing;

import com.mercury.instrument.FinancialInstrument;
import com.mercury.marketdata.MarketDataSnapshot;
import java.time.LocalDate;

/**
 * Values one kind of instrument by one method.
 *
 * <h2>Why pricing lives outside the instrument</h2>
 * The obvious design is {@code instrument.price(market)}. It is rejected for two reasons.
 * First, it gives each instrument exactly one valuation method, when the requirement is the
 * opposite: an option must be priceable by Black-Scholes <em>and</em> by a binomial tree, so
 * the two can be cross-checked - which is one of the strongest correctness tests available on
 * a pricer. Second, it turns a small immutable value into a service that depends on market
 * data, which is how instruments end up reaching for state they should have been handed.
 *
 * <h2>Purity is a hard requirement</h2>
 * An implementation must be a pure function of its three arguments. No clock, no cached
 * state, no randomness that is not derived from an argument. Everything downstream depends on
 * it: bump-and-revalue Greeks assume that revaluing an unchanged market gives an unchanged
 * answer, and Monte Carlo assumes thousands of threads can call this concurrently with no
 * synchronisation.
 *
 * @param <T> the instrument type this model prices
 */
public interface PricingModel<T extends FinancialInstrument> {

    /**
     * The exact instrument class this model handles.
     *
     * <p>Reported by the model rather than inferred, because generic type arguments are erased
     * at runtime and the registry needs the class to key on. Registration checks it, which is
     * what makes the registry's single unchecked cast safe.
     */
    Class<T> instrumentType();

    /** Identifies this model where an instrument has more than one. */
    ModelName name();

    /**
     * The value of one unit of {@code instrument}.
     *
     * @param asOf the valuation date; never read from a clock
     */
    ValuationResult price(T instrument, MarketDataSnapshot market, LocalDate asOf);
}
