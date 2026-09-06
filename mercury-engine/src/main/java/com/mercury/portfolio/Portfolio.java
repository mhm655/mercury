package com.mercury.portfolio;

import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.PortfolioId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Quantity;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A set of positions, reported in one currency.
 *
 * <h2>State and invariants only</h2>
 * This class holds positions and nothing else. It does not value itself, compute P&amp;L, or
 * measure exposure. The rule applied throughout: <em>if computing something requires a
 * collaborator the entity should not know about - a pricer, an FX rate, a clock - it is a
 * service, not a method here.</em>
 *
 * <p>That is what keeps it from becoming the god class the design set out to avoid. Valuation
 * lives in {@link PortfolioValuationService}; realised P&amp;L and exposure arrive at M7 as
 * their own collaborators, not as more methods on this.
 *
 * <p>The opposite failure - an anemic bag of getters - is avoided by keeping the invariants
 * here: a position is never stored flat, and quantities for one instrument are combined
 * rather than duplicated.
 *
 * <h2>Scope at M4</h2>
 * Positions only. No cash account, and no trade application - {@code apply(Trade)} arrives
 * with the trade lifecycle at M7/M8. Building a portfolio is done through the builder, which
 * is enough to value one.
 *
 * <p>Immutable and thread-safe.
 */
public final class Portfolio {

    private final PortfolioId id;
    private final Currency reportingCurrency;
    private final Map<InstrumentId, Position> positions;

    private Portfolio(PortfolioId id, Currency reportingCurrency,
                      Map<InstrumentId, Position> positions) {
        this.id = id;
        this.reportingCurrency = reportingCurrency;
        this.positions = positions;
    }

    public static Builder builder(PortfolioId id, Currency reportingCurrency) {
        return new Builder(id, reportingCurrency);
    }

    public PortfolioId id() {
        return id;
    }

    /**
     * The currency this portfolio's value is reported in.
     *
     * <p>At M4 every position must already be in it - see
     * {@link PortfolioValuationService} for why cross-currency valuation waits for M7.
     */
    public Currency reportingCurrency() {
        return reportingCurrency;
    }

    /**
     * Every non-flat position, in the order they were added. Unmodifiable.
     *
     * <p>The ordering is a guarantee, not an accident: valuation lines follow it, and a
     * report whose rows reorder between runs cannot be diffed or golden-mastered.
     */
    public Collection<Position> positions() {
        return positions.values();
    }

    public Optional<Position> positionIn(InstrumentId instrumentId) {
        return Optional.ofNullable(positions.get(instrumentId));
    }

    public boolean isEmpty() {
        return positions.isEmpty();
    }

    public int size() {
        return positions.size();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Portfolio other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Portfolio(" + id + ", " + positions.size() + " positions, "
                + reportingCurrency.code() + ")";
    }

    /** Accumulates positions, combining repeats and discarding flat ones. */
    public static final class Builder {

        private final PortfolioId id;
        private final Currency reportingCurrency;
        private final Map<InstrumentId, Position> positions = new LinkedHashMap<>();

        private Builder(PortfolioId id, Currency reportingCurrency) {
            this.id = Objects.requireNonNull(id, "id");
            this.reportingCurrency = Objects.requireNonNull(reportingCurrency, "reportingCurrency");
        }

        /**
         * Adds a holding, combining it with any existing position in the same instrument.
         *
         * <p>Combining rather than replacing is the invariant that makes a portfolio a
         * portfolio: two purchases of the same stock are one position of the summed quantity,
         * not two entries that a valuation would have to remember to add up.
         */
        public Builder position(InstrumentId instrumentId, Quantity quantity) {
            Objects.requireNonNull(instrumentId, "instrumentId");
            Objects.requireNonNull(quantity, "quantity");
            positions.merge(instrumentId, Position.of(instrumentId, quantity),
                    (existing, added) -> existing.plus(added.quantity()));
            return this;
        }

        public Builder position(InstrumentId instrumentId, long quantity) {
            return position(instrumentId, Quantity.of(quantity));
        }

        public Portfolio build() {
            // A flat position is not a position. Keeping zeros would make an emptied portfolio
            // report holdings it does not have, and would put meaningless zero rows in every
            // valuation report.
            Map<InstrumentId, Position> live = new LinkedHashMap<>();
            positions.forEach((instrumentId, position) -> {
                if (!position.isFlat()) {
                    live.put(instrumentId, position);
                }
            });
            // Collections.unmodifiableMap over a LinkedHashMap, NOT Map.copyOf. Map.copyOf
            // gives no iteration-order guarantee - it deliberately randomises to discourage
            // relying on one - which would make valuation lines come out in a different order
            // between runs and the golden-master test flaky. A test asserting on line order
            // caught this; the javadoc claiming insertion order was simply wrong.
            return new Portfolio(id, reportingCurrency,
                    Collections.unmodifiableMap(new LinkedHashMap<>(live)));
        }
    }
}
