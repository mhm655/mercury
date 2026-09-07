package com.mercury.app;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.CurrencyPair;
import java.util.List;
import java.util.Objects;

/**
 * The risk factors a report should measure the portfolio against.
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
 * <p>Grouping them in one type rather than passing three lists keeps
 * {@link ValuationReport#render} at six parameters instead of eight, and gives the three
 * kinds of risk a single place to grow when curve tenors arrive at M5b.
 */
public record RiskFactors(
        List<InstrumentId> spots,
        List<CurrencyPair> fxPairs,
        List<Currency> rateCurrencies) {

    public RiskFactors {
        spots = List.copyOf(Objects.requireNonNull(spots, "spots"));
        fxPairs = List.copyOf(Objects.requireNonNull(fxPairs, "fxPairs"));
        rateCurrencies = List.copyOf(Objects.requireNonNull(rateCurrencies, "rateCurrencies"));
    }

    /** Equity risk only - the shape of the report before M5 added rates and FX. */
    public static RiskFactors ofSpots(List<InstrumentId> spots) {
        return new RiskFactors(spots, List.of(), List.of());
    }
}
