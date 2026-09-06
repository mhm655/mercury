package com.mercury.pricing;

import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
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
 * <h2>A double, until the boundary</h2>
 * Model output is an approximation resting on assumptions that are wrong well before
 * arithmetic precision matters, so it stays a {@code double} (ADR 0001). {@link #toMoney()}
 * is the single crossing into exact decimal, and it rounds exactly once.
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

    /** Converts to an exact amount, rounding once at the currency's minor units. */
    public Money toMoney() {
        return Money.fromModelValue(value, currency);
    }

    @Override
    public String toString() {
        return "%.6f %s (%s)".formatted(value, currency.code(), model);
    }
}
