package com.mercury.instrument;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Price;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A European call or put on a single underlying, exercisable only at expiry.
 *
 * <h2>Why European-ness is in the class name</h2>
 * Exercise style could have been a field - {@code ExerciseStyle.EUROPEAN} versus
 * {@code AMERICAN} - but it is not, and that is deliberate. Exercise style changes how the
 * instrument is <em>priced</em>, not just what it reports: a European option has a
 * closed-form Black-Scholes value, while an American option needs a lattice or a
 * least-squares Monte Carlo because early exercise has to be evaluated at every node.
 *
 * <p>Since the pricing architecture dispatches on instrument type, making the style a field
 * would force a single pricer to branch internally on it - the {@code instanceof} chain
 * moved inside a class and renamed. Making it a type means the American option gets its own
 * pricer through ordinary registration, and it is exactly why an American option is the
 * planned M15 extensibility proof: a new class plus a binomial model, with no existing file
 * modified.
 *
 * <h2>Not cashflow-generating</h2>
 * An option's payoff is contingent, not contractual - it depends on where the underlying
 * finishes. So it implements {@link OptionTerms} and {@link HasUnderlying} but not
 * {@link CashflowGenerating}, which promises known amounts.
 *
 * <p>Immutable and thread-safe.
 */
public record EuropeanOption(
        InstrumentId id,
        InstrumentId underlyingId,
        OptionType optionType,
        Price strike,
        LocalDate expiryDate,
        int contractMultiplier,
        Currency currency)
        implements FinancialInstrument, HasUnderlying, OptionTerms, Maturing {

    /** Listed equity options are conventionally 100 shares per contract. */
    public static final int STANDARD_EQUITY_MULTIPLIER = 100;

    public EuropeanOption {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(underlyingId, "underlyingId");
        Objects.requireNonNull(optionType, "optionType");
        Objects.requireNonNull(strike, "strike");
        Objects.requireNonNull(expiryDate, "expiryDate");
        Objects.requireNonNull(currency, "currency");

        if (contractMultiplier <= 0) {
            throw new IllegalArgumentException(
                    "Contract multiplier must be positive, but was " + contractMultiplier);
        }
        if (id.equals(underlyingId)) {
            throw new IllegalArgumentException(
                    "An option cannot be its own underlying (" + id + ")");
        }
    }

    /** A call with the standard equity contract multiplier. */
    public static EuropeanOption call(String id, InstrumentId underlyingId, Price strike,
                                      LocalDate expiryDate, Currency currency) {
        return new EuropeanOption(InstrumentId.of(id), underlyingId, OptionType.CALL, strike,
                expiryDate, STANDARD_EQUITY_MULTIPLIER, currency);
    }

    /** A put with the standard equity contract multiplier. */
    public static EuropeanOption put(String id, InstrumentId underlyingId, Price strike,
                                     LocalDate expiryDate, Currency currency) {
        return new EuropeanOption(InstrumentId.of(id), underlyingId, OptionType.PUT, strike,
                expiryDate, STANDARD_EQUITY_MULTIPLIER, currency);
    }

    // ------------------------------------------------------------ instrument

    @Override
    public AssetClass assetClass() {
        return AssetClass.EQUITY;
    }

    @Override
    public TradabilityProfile tradability() {
        return TradabilityProfile.OVER_THE_COUNTER;
    }

    @Override
    public LocalDate maturityDate() {
        return expiryDate;
    }

    @Override
    public String description() {
        return "%s %s %s %s".formatted(
                underlyingId, optionType.displayName(), strike, expiryDate);
    }

    /**
     * The option with the opposite type, same terms.
     *
     * <p>Exists for the put-call parity tests at M6, which need the matching put for a given
     * call. Parity is one of the strongest correctness checks available on an option pricer,
     * because it must hold regardless of the model used.
     */
    public EuropeanOption withOppositeType() {
        return new EuropeanOption(
                InstrumentId.of(id.value() + "-" + optionType.opposite().name()),
                underlyingId, optionType.opposite(), strike, expiryDate,
                contractMultiplier, currency);
    }

    @Override
    public String toString() {
        return description();
    }
}
