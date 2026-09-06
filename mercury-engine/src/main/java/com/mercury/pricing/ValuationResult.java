package com.mercury.pricing;

import com.mercury.core.money.Currency;
import java.util.Objects;

/**
 * What a pricing model produced: a value per unit, and a record of what produced it.
 *
 * <h2>Per unit, not per position</h2>
 * The value is for <em>one</em> share, contract or unit of notional. Multiplying by a holding
 * is the portfolio layer's job. Keeping quantity out of pricing means a model never needs to
 * know how much of something is held, and the same result can be reused across every
 * portfolio holding that instrument.
 *
 * <h2>A double, and deliberately no conversion to Money here</h2>
 * Model output is an approximation resting on assumptions that are wrong well before
 * arithmetic precision matters, so it stays a {@code double} (ADR 0001).
 *
 * <p>There is intentionally no {@code toMoney()} on this type. A per-unit value converted to
 * cents and then multiplied by a holding scales the rounding error by the quantity and
 * quantises the line - which silently destroyed a numerical delta here before it was caught.
 * The conversion belongs one level up, after the product with quantity has been formed, and
 * that is where {@code PortfolioValuationService} does it. Removing the convenient method is
 * what stops the convenient mistake.
 *
 * <p>Carrying the {@link ModelName} means a valuation can always answer "which model said
 * so", which matters when two models disagree and someone has to work out why.
 */
public record ValuationResult(double value, Currency currency, ModelName model) {

    public ValuationResult {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(model, "model");
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "Model " + model + " produced a non-finite value (" + value
                            + "). This indicates a broken calculation, not an extreme market.");
        }
    }

    @Override
    public String toString() {
        return "%.6f %s (%s)".formatted(value, currency.code(), model);
    }
}
