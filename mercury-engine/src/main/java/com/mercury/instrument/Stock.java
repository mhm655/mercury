package com.mercury.instrument;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import java.util.Objects;

/**
 * An ordinary share.
 *
 * <p>The simplest instrument in the system, and useful precisely for that: it implements
 * {@link FinancialInstrument} and nothing else. No cashflows (dividends are not modelled),
 * no maturity, no underlying. That it needs no capability interfaces is evidence the
 * interfaces are carrying real distinctions rather than being applied uniformly.
 *
 * <p>Constructed by a static factory rather than a builder. Three fields do not need one,
 * and a builder here would be ceremony - see {@code DESIGN_PROPOSAL.md} section 6, where
 * Builder is listed as justified for {@code Bond} and {@code InterestRateSwap} and
 * explicitly not for this.
 *
 * <p>Immutable and thread-safe.
 */
public record Stock(InstrumentId id, String ticker, Currency currency) implements FinancialInstrument {

    public Stock {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(ticker, "ticker");
        ticker = ticker.trim();
        if (ticker.isEmpty()) {
            throw new IllegalArgumentException("Stock ticker must not be blank");
        }
    }

    /** Creates a stock whose identifier is its ticker. */
    public static Stock of(String ticker, Currency currency) {
        return new Stock(InstrumentId.of(ticker), ticker, currency);
    }

    public static Stock of(InstrumentId id, String ticker, Currency currency) {
        return new Stock(id, ticker, currency);
    }

    @Override
    public AssetClass assetClass() {
        return AssetClass.EQUITY;
    }

    @Override
    public TradabilityProfile tradability() {
        return TradabilityProfile.EXCHANGE_TRADED;
    }

    @Override
    public String description() {
        return ticker + " (" + currency.code() + " equity)";
    }
}
