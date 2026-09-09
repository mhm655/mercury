package com.mercury.portfolio;

import com.mercury.core.MercuryException;
import com.mercury.core.money.Currency;
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
 * printed beneath it. The rule, and what it cost to learn, is on {@link Money#fromModelValue};
 * this class is where it is applied.
 *
 * <h2>The market and the valuation date must agree</h2>
 * A snapshot carries the day it describes, and this refuses to value a portfolio against a
 * market from another one. That check could not exist before M5b because the snapshot had no
 * date; it matters more now that it does, because a curve is referenced to a date and reading
 * it from the wrong one shifts every discount factor by that many days of interest.
 *
 * <h2>Multi-currency, since M7</h2>
 * A position in any currency may be held, and is converted into the book's reporting currency
 * at the snapshot's spot rate. This was deferred from M4 through M6 - three milestones of a
 * mismatch throwing rather than converting - because doing it properly needed FX rates in the
 * snapshot, a stated convention for which side of the pair applies, and a decision about where
 * the conversion happens. All three exist now.
 *
 * <p>The conversion sits at the <em>end</em> of the per-position calculation, and that
 * placement is the whole decision. Pricing stays in the instrument's own currency, because that
 * is the currency its model and its discount curve are expressed in; only the finished figure
 * crosses into the reporting currency. A euro bond is priced on the euro curve and then
 * converted, and is never at any point discounted at a dollar rate.
 *
 * <p>A missing FX rate throws rather than defaulting to one. An unconverted foreign position
 * silently added to a dollar total is precisely the plausible wrong number this whole layer is
 * arranged to avoid.
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
     * @throws MarketDateMismatchException if the market describes a different day
     * @throws com.mercury.marketdata.MarketDataSnapshot.MissingMarketDataException
     *         if a position is in a currency the snapshot holds no FX rate for
     */
    public PortfolioValuation value(Portfolio portfolio, MarketDataSnapshot market, LocalDate asOf) {
        Objects.requireNonNull(portfolio, "portfolio");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(asOf, "asOf");
        if (!market.valuationDate().equals(asOf)) {
            throw new MarketDateMismatchException(market.valuationDate(), asOf);
        }

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
        Currency local = instrument.currency();
        Currency reporting = portfolio.reportingCurrency();

        ValuationResult unitValue = pricingService.price(instrument, market, asOf);

        // Multiply THEN convert THEN round. Rounding the per-unit value to cents first and
        // multiplying by the holding magnifies the rounding error by the quantity: a unit value
        // of 24.4987 becomes 24.50, and across 100 contracts that is 0.13 of error in the line.
        // It also quantises the line, which silently destroys any sensitivity computed from it -
        // a numerical delta came out as exactly 50.0 because a 0.0122 move per contract could
        // only round to 0.01 or 0.02.
        //
        // The FX conversion joins that same chain rather than starting a second one. Converting
        // an already-rounded local figure would round twice, once in each currency, with the
        // second rounding applied to a number the first had already moved (ADR 0001).
        double localModelValue = unitValue.value() * position.quantity().value().doubleValue();
        double reportingModelValue = localModelValue * market.fxRate(local, reporting);

        return new PortfolioValuation.PositionValuation(
                instrument,
                position.quantity(),
                unitValue,
                Money.fromModelValue(localModelValue, local),
                Money.fromModelValue(reportingModelValue, reporting),
                reportingModelValue);
    }

    /**
     * Raised when the market and the valuation date disagree about which day it is.
     *
     * <p>Only checkable since M5b, when the snapshot began carrying its own date. Before that
     * a market built for one day and used to value a portfolio on another produced a plausible
     * number and no complaint - and with curves it would be worse than plausible, since every
     * discount factor would be measured from the wrong day.
     */
    public static final class MarketDateMismatchException extends MercuryException {
        MarketDateMismatchException(LocalDate marketDate, LocalDate asOf) {
            super("The market snapshot is as of " + marketDate + " but the portfolio is being "
                    + "valued on " + asOf + ". Curves are referenced to the market date, so "
                    + "discounting would be measured from the wrong day - a small, invisible "
                    + "and entirely wrong answer.");
        }
    }

}
