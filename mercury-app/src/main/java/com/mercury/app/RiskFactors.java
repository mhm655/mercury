package com.mercury.app;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import com.mercury.core.time.Tenor;
import java.util.List;
import java.util.Objects;

/**
 * What a report measures the portfolio against, and where it samples the curves.
 *
 * <h2>Why they are listed rather than discovered</h2>
 * The engine could infer them - walk the positions, collect every underlying, currency and
 * pair they touch. It deliberately does not. Which factors a book is reported against is a
 * risk-management decision, not a consequence of what it happens to hold: a desk may want a
 * currency it has no position in today shown as zero, and may not want a line for every
 * incidental exposure. Inferring the list would take that choice away and make the report's
 * shape depend on the portfolio's contents, which is also the fastest way to a golden master
 * that changes for uninteresting reasons.
 *
 * <p>Grouping them in one type rather than passing four lists keeps
 * {@link ValuationReport#render} at six parameters instead of nine.
 *
 * <p>{@code curveTenors} is the one member that is not a risk factor: it is where the report
 * samples each discount curve for display. It lives here because it answers the same question
 * as the others - what should this report show - and a second parameter object holding one
 * list would be ceremony.
 *
 * <h2>{@code volatilitySpots}, M10</h2>
 * Deliberately a separate list from {@code spots}, not a filter applied to it. Delta and
 * Gamma are well-defined for any underlying a portfolio holds spot exposure to - a stock has
 * a Gamma of exactly zero, which is itself informative. Vega is different: it needs a quoted
 * volatility, which only an underlying with a traded option actually has, and
 * {@code SensitivityCalculator.vega} fails loudly rather than reporting zero for one that
 * does not. Reusing {@code spots} for Vega would mean the report's shape depends on which
 * underlyings the market snapshot happens to quote a volatility for - the same "inferred list"
 * problem this record's own design already rejects for the others.
 */
public record RiskFactors(
        List<InstrumentId> spots,
        List<InstrumentId> volatilitySpots,
        List<CurrencyPair> fxPairs,
        List<Currency> rateCurrencies,
        List<Tenor> curveTenors) {

    public RiskFactors {
        spots = List.copyOf(Objects.requireNonNull(spots, "spots"));
        volatilitySpots = List.copyOf(Objects.requireNonNull(volatilitySpots, "volatilitySpots"));
        fxPairs = List.copyOf(Objects.requireNonNull(fxPairs, "fxPairs"));
        rateCurrencies = List.copyOf(Objects.requireNonNull(rateCurrencies, "rateCurrencies"));
        curveTenors = List.copyOf(Objects.requireNonNull(curveTenors, "curveTenors"));
    }
}
