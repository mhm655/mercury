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
 * means a valuation can always answer which holding contributed what, under which model, and -
 * since M7 - in which currency.
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

    /**
     * One position's contribution, in both the currency it trades in and the one the book
     * reports in.
     *
     * <h2>Three values, and why none is redundant</h2>
     * <ul>
     *   <li>{@link #localValue} is what the position is worth to someone standing in its own
     *       market - a euro bond in euros. It is the number a desk quotes.</li>
     *   <li>{@link #marketValue} is the same position converted into the book's reporting
     *       currency, and is what sums to the portfolio total.</li>
     *   <li>{@link #reportingModelValue} is that second figure <em>before</em> rounding to the
     *       cent. Risk is computed from it, for the reason ADR 0001 gives: a delta divides the
     *       difference of two valuations by a very small number, so cent-level quantisation in
     *       the numerator is amplified enormously in the result.</li>
     * </ul>
     *
     * <p>When the two currencies are the same, {@code localValue} and {@code marketValue} are
     * equal, and printing both would be noise - which is why the report shows the local column
     * only for positions where it says something.
     */
    public record PositionValuation(
            FinancialInstrument instrument,
            Quantity quantity,
            ValuationResult unitValue,
            Money localValue,
            Money marketValue,
            double reportingModelValue) {

        public PositionValuation {
            Objects.requireNonNull(instrument, "instrument");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(unitValue, "unitValue");
            Objects.requireNonNull(localValue, "localValue");
            Objects.requireNonNull(marketValue, "marketValue");
            if (!Double.isFinite(reportingModelValue)) {
                throw new IllegalArgumentException(
                        "Position value for " + instrument.id() + " is not finite ("
                                + reportingModelValue + ")");
            }
        }

        /** True when the instrument trades in a currency the book does not report in. */
        public boolean isForeign() {
            return localValue.currency() != marketValue.currency();
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

    /**
     * The total before rounding to currency minor units, in the model domain.
     *
     * <p>Exists for risk, and the distinction matters. {@link #totalValue()} is a ledger fact
     * rounded to the cent, which is right for reporting and wrong as the input to a numerical
     * derivative: a delta divides the difference of two valuations by a very small number, so
     * cent-level quantisation in the numerator is amplified enormously in the result.
     *
     * <p>Sensitivities are therefore computed from this, keeping the whole calculation on the
     * {@code double} side of the numeric split until the answer is reported. That is ADR 0001
     * applied rather than restated: exact decimals for what settles, doubles for what is
     * modelled.
     */
    public double modelTotal() {
        return lines.stream()
                .mapToDouble(PositionValuation::reportingModelValue)
                .sum();
    }

    /**
     * The value of every position denominated in {@code currency}, converted into the book's
     * reporting currency.
     *
     * <p>What "exposure to a currency" means here is deliberately narrow: the value of the
     * positions that <em>settle</em> in it. A dollar-settled FX forward on the euro carries
     * euro risk and does not appear under EUR by this measure - that exposure shows up in the
     * FX delta instead, where it belongs. Two different questions, two different numbers, and
     * conflating them is how a currency report ends up double-counting.
     *
     * <p>Summed from the rounded line values rather than the model figures behind them, so
     * that the exposures add up to {@link #totalValue()} exactly. The first version summed the
     * unrounded numbers and the two currency rows came to a cent more than the total they were
     * decomposing - the same class of defect as a headline that disagrees with its own detail,
     * and this type exists partly to make that impossible. Risk keeps the unrounded figures;
     * see {@link #modelTotal()}.
     */
    public Money exposureTo(com.mercury.core.money.Currency currency) {
        Objects.requireNonNull(currency, "currency");
        return lines.stream()
                .filter(line -> line.localValue().currency() == currency)
                .map(PositionValuation::marketValue)
                .reduce(Money.zero(totalValue.currency()), Money::plus);
    }

    /** Every currency the book actually settles in, in the order positions were valued. */
    public java.util.List<com.mercury.core.money.Currency> currencies() {
        return lines.stream()
                .map(line -> line.localValue().currency())
                .distinct()
                .toList();
    }

    @Override
    public String toString() {
        return "PortfolioValuation(" + portfolioId + " on " + valuationDate
                + " = " + totalValue + ")";
    }
}
