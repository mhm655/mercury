package com.mercury.portfolio;

import com.mercury.core.MercuryException;
import com.mercury.core.money.Money;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.PricingService;
import com.mercury.pricing.ValuationResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Values a portfolio under a market.
 *
 * <h2>Why this is a service and not a method on Portfolio</h2>
 * Valuing needs a pricing service, a market snapshot and a valuation date - three
 * collaborators a portfolio has no business knowing about. Putting the method there would
 * make the entity depend on the entire pricing stack and would be the first step toward the
 * god class the design explicitly set out to avoid.
 *
 * <h2>Where the numeric boundary sits</h2>
 * Models return {@code double} per unit; positions are exact quantities; the answer is
 * {@link Money}. The product is formed in the model domain and crosses into {@code Money}
 * once per line, so each line rounds exactly once and the total is an exact sum of
 * exactly-rounded lines - which is what makes a headline total reconcile against the detail
 * printed beneath it.
 *
 * <p>The order matters and was got wrong first time here: rounding the per-unit value to
 * cents and <em>then</em> multiplying by the holding scales the rounding error by the
 * quantity, and quantises the line so badly that any sensitivity derived from it collapses.
 * Round once, and round last (ADR 0001).
 *
 * <h2>Single currency, at M4</h2>
 * Every position must be in the portfolio's reporting currency. Converting is not hard, but
 * doing it properly means FX rates in the snapshot, a stated convention for which side of the
 * pair applies, and a decision about where conversion happens - and getting that wrong
 * silently produces a plausible total. It arrives at M7 with the multi-currency cash account.
 * Until then a mismatch throws rather than being quietly ignored or wrongly converted.
 *
 * <p>Stateless and thread-safe, given a thread-safe {@link PricingService}.
 */
public final class PortfolioValuationService {

    private final PricingService pricingService;
    private final InstrumentCatalog catalog;

    public PortfolioValuationService(PricingService pricingService, InstrumentCatalog catalog) {
        this.pricingService = Objects.requireNonNull(pricingService, "pricingService");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Values every position and sums them.
     *
     * @throws CurrencyNotSupportedException if a position is not in the reporting currency
     */
    public PortfolioValuation value(Portfolio portfolio, MarketDataSnapshot market, LocalDate asOf) {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(asOf, "asOf");

        List<PortfolioValuation.PositionValuation> lines = new ArrayList<>(portfolio.size());
        Money total = Money.zero(portfolio.reportingCurrency());

        for (Position position : portfolio.positions()) {
            PortfolioValuation.PositionValuation line = valuePosition(portfolio, position, market, asOf);
            lines.add(line);
            total = total.plus(line.marketValue());
        }
        return new PortfolioValuation(portfolio.id(), asOf, total, lines);
    }

    /**
     * The market value of one holding: unit value from the model, times quantity.
     *
     * <p>Dispatch is entirely polymorphic - this method never asks what kind of instrument it
     * holds. A stock resolves to a spot lookup and an option to Black-Scholes because the
     * registry says so, not because anything here branches.
     */
    private PortfolioValuation.PositionValuation valuePosition(
            Portfolio portfolio, Position position, MarketDataSnapshot market, LocalDate asOf) {

        FinancialInstrument instrument = catalog.require(position.instrumentId());
        if (instrument.currency() != portfolio.reportingCurrency()) {
            throw new CurrencyNotSupportedException(portfolio, instrument);
        }

        ValuationResult unitValue = pricingService.price(instrument, market, asOf);

        // Multiply THEN round. Rounding the per-unit value to cents first and multiplying by
        // the holding magnifies the rounding error by the quantity: a unit value of 24.4987
        // becomes 24.50, and across 100 contracts that is 0.13 of error in the line. It also
        // quantises the line, which silently destroys any sensitivity computed from it - a
        // numerical delta came out as exactly 50.0 because a 0.0122 move per contract could
        // only round to 0.01 or 0.02. Market value is a model output multiplied by an exact
        // quantity, so the whole product crosses into Money once (ADR 0001).
        double modelValue = unitValue.value() * position.quantity().value().doubleValue();
        Money marketValue = Money.fromModelValue(modelValue, instrument.currency());

        return new PortfolioValuation.PositionValuation(
                instrument, position.quantity(), unitValue, marketValue);
    }

    /** Raised when a position's currency differs from the portfolio's reporting currency. */
    public static final class CurrencyNotSupportedException extends MercuryException {
        CurrencyNotSupportedException(Portfolio portfolio, FinancialInstrument instrument) {
            super("Cannot value " + instrument.id() + ", which is denominated in "
                    + instrument.currency().code() + ", in a portfolio reporting in "
                    + portfolio.reportingCurrency().code()
                    + ". Cross-currency valuation needs FX rates in the snapshot and a stated "
                    + "conversion convention; it arrives at M7. Failing is deliberate - "
                    + "converting at an assumed rate would produce a plausible wrong total.");
        }
    }
}
