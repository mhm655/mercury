package com.mercury.marketdata;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import java.util.Objects;

/**
 * Identifies one observable quantity in a {@link MarketDataSnapshot}.
 *
 * <h2>Why this one is sealed, when FinancialInstrument is not</h2>
 * The two decisions look contradictory and are not. Instruments are an <em>open</em> set:
 * the architecture's central claim is that a new instrument can be added without touching
 * existing code, so sealing that interface would make the claim false by construction.
 *
 * <p>Market data types are the opposite. They are a closed vocabulary the engine itself
 * defines - a spot price, a volatility, a discount rate - and adding a kind of market data
 * is a change to the engine, not an extension of it. Sealing buys exhaustive {@code switch}
 * over the cases, so when a new kind is added the compiler lists every place that has to
 * learn about it. Shock application in particular relies on that.
 *
 * <p>The distinction is worth stating because "seal everything" and "seal nothing" are both
 * wrong. Seal what you own and enumerate; leave open what others extend.
 *
 * <h2>Values are doubles</h2>
 * Everything a snapshot holds is model input - a price feeding Black-Scholes, a rate feeding
 * a discount factor. Per ADR 0001 those live on the {@code double} side of the numeric split.
 * They become {@link com.mercury.core.money.Money} only at the valuation boundary.
 */
public sealed interface MarketDataKey
        permits MarketDataKey.SpotPrice, MarketDataKey.Volatility, MarketDataKey.DiscountRate {

    /** Short label for diagnostics and report output. */
    String describe();

    /**
     * The current traded price of an instrument, per unit.
     *
     * <p>Quoted in the instrument's own currency; the key does not repeat it, because the
     * instrument already knows.
     */
    record SpotPrice(InstrumentId instrumentId) implements MarketDataKey {

        public SpotPrice {
            Objects.requireNonNull(instrumentId, "instrumentId");
        }

        @Override
        public String describe() {
            return "spot:" + instrumentId;
        }
    }

    /**
     * Annualised implied volatility for an instrument, as a decimal - {@code 0.25} is 25%.
     *
     * <p>A single number per underlying, not a surface. Real desks quote volatility by strike
     * and expiry, and the smile is a first-order effect on option prices. Flat volatility is
     * a deliberate simplification of this milestone, named here rather than assumed silently.
     */
    record Volatility(InstrumentId instrumentId) implements MarketDataKey {

        public Volatility {
            Objects.requireNonNull(instrumentId, "instrumentId");
        }

        @Override
        public String describe() {
            return "vol:" + instrumentId;
        }
    }

    /**
     * A flat continuously-compounded risk-free rate for a currency, as a decimal.
     *
     * <p>Flat, not a curve. A real discount curve arrives at M5b with bootstrapping; until
     * then every maturity discounts at the same rate. That is exactly the assumption
     * Black-Scholes makes in its textbook form, so it costs nothing for this milestone's
     * pricers and would be wrong for a swap - which is why swaps are not priced yet.
     */
    record DiscountRate(Currency currency) implements MarketDataKey {

        public DiscountRate {
            Objects.requireNonNull(currency, "currency");
        }

        @Override
        public String describe() {
            return "rate:" + currency.code();
        }
    }

    // ------------------------------------------------------------- factories

    static SpotPrice spot(InstrumentId instrumentId) {
        return new SpotPrice(instrumentId);
    }

    static Volatility volatility(InstrumentId instrumentId) {
        return new Volatility(instrumentId);
    }

    static DiscountRate discountRate(Currency currency) {
        return new DiscountRate(currency);
    }
}
