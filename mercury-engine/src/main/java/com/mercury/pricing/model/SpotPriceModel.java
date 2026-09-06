package com.mercury.pricing.model;

import com.mercury.instrument.Stock;
import com.mercury.marketdata.MarketDataSnapshot;
import com.mercury.pricing.ModelName;
import com.mercury.pricing.PricingModel;
import com.mercury.pricing.ValuationResult;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Values a share at its market price.
 *
 * <p>Trivial, and worth having as a class anyway. A share's value is not <em>computed</em>
 * from anything - it is observed - and that is a genuinely different valuation method from
 * discounting cashflows or solving a closed form. Expressing it as a model keeps the
 * portfolio layer uniform: every position is valued the same way, through the registry,
 * whether the answer takes a table lookup or a Monte Carlo run.
 *
 * <p>It also demonstrates the point of the registry more sharply than a second complicated
 * model would. Two models with nothing in common but their interface, dispatched
 * polymorphically with no {@code instanceof} anywhere, is the whole claim.
 *
 * <p>Stateless, pure and thread-safe.
 */
public final class SpotPriceModel implements PricingModel<Stock> {

    public static final ModelName NAME = ModelName.of("spot");

    @Override
    public Class<Stock> instrumentType() {
        return Stock.class;
    }

    @Override
    public ModelName name() {
        return NAME;
    }

    @Override
    public ValuationResult price(Stock stock, MarketDataSnapshot market, LocalDate asOf) {
        Objects.requireNonNull(stock, "stock");
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(asOf, "asOf");

        // Deliberately ignores asOf: a spot price is what the snapshot says it is. The
        // parameter stays in the signature because the interface is uniform across models,
        // and uniformity is what lets the portfolio layer treat them identically.
        return new ValuationResult(market.spot(stock.id()), stock.currency(), NAME);
    }
}
