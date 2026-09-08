package com.mercury.marketdata;

import com.mercury.core.MercuryException;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import java.time.LocalDate;
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
 * <h2>Each key owns what its values may be</h2>
 * A spot price is positive, a volatility is not negative, a rate may be either. Those rules
 * live here, on {@link #requireValidValue}, rather than on the snapshot's builder - because
 * the builder is not the only way a snapshot comes into existence. It was, at M5, the only
 * place that checked: {@code MarketDataSnapshot.withShock} produced new snapshots without
 * consulting it, so a shock could build a market the builder would have refused. A spot
 * scaled to -195.50 then failed four layers down inside the cumulative normal with the
 * message "N(x) is undefined for NaN", naming neither the instrument nor the shock.
 *
 * <p>Putting the rule on the key rather than on a construction path is what makes it
 * unavoidable: every route into a snapshot goes through a key, so there is no second place to
 * forget. It also removes the oddity of a builder that knew the rules for types it did not
 * own.
 *
 * <h2>Values are doubles</h2>
 * Everything a snapshot holds is model input - a price feeding Black-Scholes, a rate feeding
 * a discount factor. Per ADR 0001 those live on the {@code double} side of the numeric split.
 * They become {@link com.mercury.core.money.Money} only at the valuation boundary.
 */
public sealed interface MarketDataKey
        permits MarketDataKey.SpotPrice, MarketDataKey.Volatility,
                MarketDataKey.ZeroRate, MarketDataKey.FxRate {

    /** Short label for diagnostics and report output. */
    String describe();

    /**
     * Rejects a value this kind of observation cannot take.
     *
     * <p>Called on every path that puts a value into a snapshot - the builder and
     * {@code withShock} alike - so a scenario cannot manufacture a market that could not have
     * been quoted.
     *
     * @throws InvalidMarketDataException if {@code value} is not legal for this key
     */
    void requireValidValue(double value);

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

        /** Strictly positive: nothing trades at or below zero, and Black-Scholes takes a log. */
        @Override
        public void requireValidValue(double value) {
            requireFinite(this, value);
            if (value <= 0) {
                throw new InvalidMarketDataException(this, value, "be positive",
                        "Model a total wipeout as a small positive fraction of spot rather "
                                + "than zero; a price of exactly zero puts log(S/K) at "
                                + "negative infinity.");
            }
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

        /** Zero is legal - a deterministic underlying - but negative volatility is not. */
        @Override
        public void requireValidValue(double value) {
            requireFinite(this, value);
            if (value < 0) {
                throw new InvalidMarketDataException(this, value, "not be negative",
                        "Black-Scholes takes the square root of variance, so a negative "
                                + "volatility yields NaN rather than an extreme price.");
            }
        }
    }

    /**
     * One point on a currency's discount curve: the continuously-compounded zero rate out to
     * {@code pillarDate}, as a decimal.
     *
     * <h2>One key per pillar, rather than one key per curve</h2>
     * A curve could have been a single opaque value in the snapshot. Storing it pillar by
     * pillar instead is what keeps {@link MarketShock} the only mechanism the engine needs for
     * scenarios, Greeks and Monte Carlo: a parallel shift is a shock matching every pillar of
     * a currency, and a key-rate bump is a shock matching one. Neither needs code that knows
     * what a curve is.
     *
     * <p>Before M5b this was a single flat {@code DiscountRate} per currency, and every
     * maturity discounted at the same rate. A flat curve is now just a curve with one pillar -
     * the degenerate case of the same type rather than a separate path through the engine.
     *
     * <h2>Keyed by date, not tenor</h2>
     * Tenors are how a rate is quoted; dates are where the money is. A pillar fitted to a 2Y
     * par swap belongs on the date that swap actually settles, which is a business day and not
     * necessarily the day two years from now - see {@code CurveInstrument} for the basis-point
     * error that distinction caused. Keying by date means a bootstrapped curve round-trips
     * through a snapshot exactly, instead of being re-resolved onto slightly different days.
     */
    record ZeroRate(Currency currency, LocalDate pillarDate) implements MarketDataKey {

        public ZeroRate {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(pillarDate, "pillarDate");
        }

        @Override
        public String describe() {
            return "zero:" + currency.code() + "@" + pillarDate;
        }

        /**
         * Any finite value. Negative rates are unusual but real - EUR and JPY policy rates
         * have been below zero - and rejecting them would encode a market condition as a
         * validation rule.
         */
        @Override
        public void requireValidValue(double value) {
            requireFinite(this, value);
        }
    }

    /**
     * The spot exchange rate for a currency pair: units of {@code quote} per one unit of
     * {@code base}, following the convention {@link CurrencyPair} encodes.
     *
     * <p>Only one direction is stored. EUR/USD at 1.10 fully determines USD/EUR at 1/1.10, so
     * holding both would create a second place for them to disagree - and a snapshot whose
     * EUR/USD and USD/EUR were not exact reciprocals would offer a risk-free arbitrage that
     * exists only in the data. {@code MarketDataSnapshot.fxRate} inverts on read instead.
     */
    record FxRate(CurrencyPair pair) implements MarketDataKey {

        public FxRate {
            Objects.requireNonNull(pair, "pair");
        }

        @Override
        public String describe() {
            return "fx:" + pair;
        }

        /** Strictly positive: the reciprocal is taken on read, and 1/0 is not a rate. */
        @Override
        public void requireValidValue(double value) {
            requireFinite(this, value);
            if (value <= 0) {
                throw new InvalidMarketDataException(this, value, "be positive",
                        "The opposite direction is derived by inversion, so a zero or "
                                + "negative rate would make one side of the pair infinite "
                                + "or backwards.");
            }
        }
    }

    // ------------------------------------------------------------- factories

    static SpotPrice spot(InstrumentId instrumentId) {
        return new SpotPrice(instrumentId);
    }

    static Volatility volatility(InstrumentId instrumentId) {
        return new Volatility(instrumentId);
    }

    static ZeroRate zeroRate(Currency currency, LocalDate pillarDate) {
        return new ZeroRate(currency, pillarDate);
    }

    static FxRate fxRate(CurrencyPair pair) {
        return new FxRate(pair);
    }

    /** No observation of any kind may be NaN or infinite. */
    private static void requireFinite(MarketDataKey key, double value) {
        if (!Double.isFinite(value)) {
            throw new InvalidMarketDataException(key, value, "be finite",
                    "A non-finite observation is a broken calculation upstream, not an "
                            + "extreme market.");
        }
    }

    /**
     * Raised when a value is not legal for the key it is stored under.
     *
     * <p>Names the key, the offending value and the rule, because the failure this replaced
     * named none of them: it surfaced as a NaN deep inside a pricer, with nothing to say
     * which observation had gone wrong or how.
     */
    final class InvalidMarketDataException extends MercuryException {

        private final transient MarketDataKey key;

        InvalidMarketDataException(MarketDataKey key, double value, String rule, String why) {
            super(key.describe() + " must " + rule + ", but was " + value + ". " + why
                    + " Market data invariants are enforced wherever a snapshot is built, "
                    + "including after a shock, so a scenario cannot produce a market that "
                    + "could not have been quoted in the first place.");
            this.key = key;
        }

        public MarketDataKey key() {
            return key;
        }
    }
}
