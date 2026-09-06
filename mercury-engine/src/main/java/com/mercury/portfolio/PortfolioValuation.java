package com.mercury.portfolio;

import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.pricing.ValuationResult;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The result of valuing a portfolio: a total, and every line that made it up.
 *
 * <h2>An immutable result, not a mutating calculation</h2>
 * Valuation returns this rather than writing values back onto positions. A portfolio has no
 * single value - it has a value <em>under a given market, on a given date</em> - so storing
 * one on the portfolio would beg the question of which market it came from. Keeping the
 * result separate is what allows the same portfolio to be valued under a base market and a
 * stressed one and the two compared, which is the whole basis of Greeks and scenarios.
 *
 * <h2>Lines are kept, not just the total</h2>
 * A total nobody can decompose is a number nobody can trust. Keeping the per-position detail
 * means a valuation can always answer which holding contributed what, and under which model.
 */
public record PortfolioValuation(
        PortfolioId portfolioId,
        LocalDate valuationDate,
        Money totalValue,
        List<PositionValuation> lines) {

    public PortfolioValuation {
        Objects.requireNonNull(portfolioId, "portfolioId");
        Objects.requireNonNull(valuationDate, "valuationDate");
        Objects.requireNonNull(totalValue, "totalValue");
        lines = List.copyOf(lines);

        // The total must be the sum of its parts. Asserting it here rather than trusting the
        // caller means a valuation object can never exist in a state where the headline
        // number disagrees with the detail behind it.
        Money summed = lines.stream()
                .map(PositionValuation::marketValue)
                .reduce(Money.zero(totalValue.currency()), Money::plus);
        if (!summed.equals(totalValue)) {
            throw new IllegalArgumentException(
                    "Portfolio total " + totalValue + " disagrees with the sum of its "
                            + lines.size() + " lines (" + summed + ")");
        }
    }

    /** One position's contribution. */
    public record PositionValuation(
            FinancialInstrument instrument,
            Quantity quantity,
            ValuationResult unitValue,
            Money marketValue) {

        public PositionValuation {
            Objects.requireNonNull(instrument, "instrument");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(unitValue, "unitValue");
            Objects.requireNonNull(marketValue, "marketValue");
        }

        @Override
        public String toString() {
            return "%-24s %12s @ %-14s = %s".formatted(
                    instrument.id(), quantity, unitValue.value(), marketValue);
        }
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    @Override
    public String toString() {
        return "PortfolioValuation(" + portfolioId + " on " + valuationDate
                + " = " + totalValue + ")";
    }
}
