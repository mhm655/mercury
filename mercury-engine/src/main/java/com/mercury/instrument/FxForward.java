package com.mercury.instrument;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * An agreement to exchange two currencies on a future date at a rate fixed today.
 *
 * <h2>What the fields mean</h2>
 * The holder buys {@code baseNotional} of the pair's base currency and pays for it in the
 * quote currency at {@code forwardRate}, on {@code settlementDate}. A negative
 * {@code baseNotional} sells the base currency instead - direction lives in the sign, for
 * the same reason it does on {@link com.mercury.core.money.Quantity}: it makes the two
 * cases one piece of arithmetic rather than two branches.
 *
 * <p>Following the market convention encoded in {@link CurrencyPair}, the rate is quote
 * units per one base unit. EUR/USD at 1.08 means one euro costs 1.08 dollars, so buying
 * 1,000,000 EUR costs 1,080,000 USD.
 *
 * <h2>Why the cashflows are known</h2>
 * Both legs are fixed at trade time, so this implements {@link CashflowGenerating} exactly:
 * a positive amount in the base currency and a negative one in the quote currency, both on
 * the settlement date. That the two cashflows are in <em>different currencies</em> is why
 * {@link Cashflow} carries {@link Money} rather than a bare number - a single-currency
 * cashflow type could not express this instrument at all.
 *
 * <p>Note the value of this contract still moves, despite both legs being fixed: it depends
 * on where the forward rate has gone since. Fixed cashflows do not mean a fixed price.
 *
 * <p>Immutable and thread-safe.
 */
public record FxForward(
        InstrumentId id,
        CurrencyPair currencyPair,
        BigDecimal baseNotional,
        BigDecimal forwardRate,
        LocalDate settlementDate)
        implements FinancialInstrument, CashflowGenerating, Maturing {

    public FxForward {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currencyPair, "currencyPair");
        Objects.requireNonNull(baseNotional, "baseNotional");
        Objects.requireNonNull(forwardRate, "forwardRate");
        Objects.requireNonNull(settlementDate, "settlementDate");

        if (baseNotional.signum() == 0) {
            throw new IllegalArgumentException(
                    "FX forward notional must not be zero; the sign carries the direction, "
                            + "so a zero notional has neither size nor side");
        }
        if (forwardRate.signum() <= 0) {
            throw new IllegalArgumentException(
                    "FX forward rate must be positive, but was " + forwardRate.toPlainString());
        }
        // Rejecting a zero notional is not enough: a notional small enough to round away at
        // the currency's scale produces a live instrument whose legs are both zero. Validate
        // the amounts that actually settle, not the raw figures they are derived from.
        Money base = Money.of(baseNotional, currencyPair.base());
        Money quote = Money.of(baseNotional.multiply(forwardRate), currencyPair.quote());
        if (base.isZero() || quote.isZero()) {
            throw new IllegalArgumentException(
                    "FX forward notional " + baseNotional.toPlainString() + " "
                            + currencyPair.base().code() + " at " + forwardRate.toPlainString()
                            + " rounds to " + base + " / " + quote
                            + " at these currencies' minor units, so the trade would settle "
                            + "nothing. Increase the notional.");
        }
    }

    /** Buys {@code baseNotional} of the base currency. */
    public static FxForward buy(String id, CurrencyPair pair, String baseNotional,
                                String forwardRate, LocalDate settlementDate) {
        return new FxForward(InstrumentId.of(id), pair, new BigDecimal(baseNotional),
                new BigDecimal(forwardRate), settlementDate);
    }

    /** Sells {@code baseNotional} of the base currency. */
    public static FxForward sell(String id, CurrencyPair pair, String baseNotional,
                                 String forwardRate, LocalDate settlementDate) {
        return new FxForward(InstrumentId.of(id), pair, new BigDecimal(baseNotional).negate(),
                new BigDecimal(forwardRate), settlementDate);
    }

    // ------------------------------------------------------------ instrument

    /**
     * The quote currency of the pair.
     *
     * <p>An FX forward settles in two currencies, so "its" currency is a convention rather
     * than a fact. The quote currency is the one the deal's value is conventionally measured
     * in - a EUR/USD forward is a dollar-denominated position on the euro. Converting into a
     * portfolio's reporting currency is the portfolio layer's job.
     */
    @Override
    public Currency currency() {
        return currencyPair.quote();
    }

    @Override
    public AssetClass assetClass() {
        return AssetClass.FX;
    }

    @Override
    public TradabilityProfile tradability() {
        return TradabilityProfile.OVER_THE_COUNTER;
    }

    @Override
    public LocalDate maturityDate() {
        return settlementDate;
    }

    @Override
    public String description() {
        String side = isBuyingBase() ? "Buy" : "Sell";
        return "%s %s %s @ %s %s".formatted(
                side, baseNotional.abs().toPlainString(), currencyPair.base().code(),
                forwardRate.toPlainString(), settlementDate);
    }

    // ------------------------------------------------------------- cashflows

    /**
     * The two settlement legs, or nothing once settled.
     *
     * <p>Both fall on the settlement date and are returned base leg first. Their currencies
     * differ, so they must not be summed without an FX conversion - which is exactly what
     * {@link Money}'s refusal to add across currencies enforces.
     */
    @Override
    public List<Cashflow> cashflows(LocalDate from) {
        Objects.requireNonNull(from, "from");
        if (!settlementDate.isAfter(from)) {
            return List.of();
        }
        return List.of(
                new Cashflow(settlementDate, baseAmount()),
                new Cashflow(settlementDate, quoteAmount()));
    }

    /** The base-currency leg. Positive when buying the base currency. */
    public Money baseAmount() {
        return Money.of(baseNotional, currencyPair.base());
    }

    /**
     * The quote-currency leg, always opposite in sign to the base leg: buying one currency
     * means paying the other.
     */
    public Money quoteAmount() {
        return Money.of(baseNotional.multiply(forwardRate).negate(), currencyPair.quote());
    }

    /** True if the holder is long the base currency. */
    public boolean isBuyingBase() {
        return baseNotional.signum() > 0;
    }

    @Override
    public String toString() {
        return description();
    }

    /**
     * Entity equality: two instruments are the same when their ids match.
     * See {@link FinancialInstrument} for why identity rather than structure.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof FxForward other && id.equals(other.id());
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
