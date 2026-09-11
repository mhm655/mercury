package com.mercury.marketdata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A named, composed {@link MarketShock} - "Market Crash" is equities -30% <em>and</em>
 * volatility +50% <em>and</em> FX -10% <em>and</em> rates +150bp, and a caller applying it
 * should be able to say which scenario it was without repeating the four shocks that make
 * it up. {@code docs/DESIGN_PROPOSAL.md} section 6 names this pattern early - Composite,
 * same shape as {@link MarketShock} itself, and Builder, for the same "genuinely
 * many-parameter, many-optional construction" reason {@code Bond} and
 * {@code InterestRateSwap} use one.
 *
 * <h2>Composition is delegated, not reimplemented</h2>
 * {@link Builder#build()} folds every added shock through {@link MarketShock#composite}
 * rather than inventing a second way to combine shocks - the one mechanism {@code MarketShock}
 * already provides for stress testing, Greeks and (M12) Monte Carlo gets a fourth user here,
 * not a parallel one.
 *
 * <h2>What a caller does with one</h2>
 * {@code SensitivityCalculator.valueChangeUnder(portfolio, scenario.shock(), market, asOf)} -
 * the same "shock, revalue, difference" primitive every other risk number in this engine is
 * built from.
 *
 * <p>Immutable and thread-safe.
 */
public final class Scenario {

    private final String name;
    private final String description;
    private final MarketShock shock;

    private Scenario(String name, String description, MarketShock shock) {
        this.name = name;
        this.description = description;
        this.shock = shock;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** A short label - "Market Crash", "Rate Shock" - not a description of what it does. */
    public String name() {
        return name;
    }

    /** The human-readable "equities -30%, volatility +50%, ..." a report prints alongside {@link #name()}. */
    public String description() {
        return description;
    }

    /** The composed shock every component builder call contributed to. */
    public MarketShock shock() {
        return shock;
    }

    @Override
    public String toString() {
        return description.isBlank() ? name : name + " (" + description + ")";
    }

    /** Accumulates shock components, combining them into one on {@link #build()}. */
    public static final class Builder {

        private final String name;
        private String description = "";
        private final List<MarketShock> shocks = new ArrayList<>();

        private Builder(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("A scenario must have a name");
            }
            this.name = name;
        }

        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        /** Adds one component. Order does not matter unless two shocks touch the same key. */
        public Builder shock(MarketShock shock) {
            shocks.add(Objects.requireNonNull(shock, "shock"));
            return this;
        }

        public Scenario build() {
            if (shocks.isEmpty()) {
                throw new IllegalStateException(
                        "Scenario \"" + name + "\" has no shocks - a scenario with nothing in it "
                                + "is not a scenario, and MarketShock.none() already exists for "
                                + "callers that genuinely want a no-op.");
            }
            return new Scenario(name, description, MarketShock.composite(shocks));
        }
    }
}
