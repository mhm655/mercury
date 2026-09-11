package com.mercury.marketdata;

import java.util.List;
import java.util.Objects;

/**
 * A named, composed {@link MarketShock} - "Market Crash" is equities -30% <em>and</em>
 * volatility +50% <em>and</em> FX -10% <em>and</em> rates +150bp, and a caller applying it
 * should be able to say which scenario it was without repeating the four shocks that make
 * it up.
 *
 * <h2>A factory, not a Builder</h2>
 * {@code docs/DESIGN_PROPOSAL.md} section 6 named Builder for this early, on the same
 * "genuinely many-parameter, many-optional construction" reasoning that's true of
 * {@code Bond} and {@code InterestRateSwap}. It does not hold up against what actually got
 * built: a {@code Scenario} has exactly one optional field ({@code description}) and a
 * repeated shock list, which a varargs factory says in a third of the code with no loss of
 * expressiveness at any call site. The same judgement the project already made once for
 * Template Method on the discounted-cashflow base - justified on paper, dropped once
 * building it showed there was no varying step to override - applies here: waiting for a
 * second genuinely optional field, rather than building the general shape on spec.
 *
 * <h2>Composition is delegated, not reimplemented</h2>
 * {@link #of} folds every shock through {@link MarketShock#composite} rather than inventing
 * a second way to combine shocks - the one mechanism {@code MarketShock} already provides
 * for stress testing, Greeks and (M12) Monte Carlo gets a fourth user here, not a parallel
 * one.
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

    /**
     * @param name a short label - "Market Crash", "Rate Shock" - not a description of what it
     *             does
     * @param description the human-readable "equities -30%, volatility +50%, ..." a report
     *                     prints alongside {@code name}; may be blank
     * @param shocks one or more components, composed together; order does not matter unless
     *               two shocks touch the same key
     * @throws IllegalArgumentException if {@code name} is blank or no shocks are given - a
     *         scenario with nothing in it is not a scenario, and {@link MarketShock#none()}
     *         already exists for callers that genuinely want a no-op
     */
    public static Scenario of(String name, String description, MarketShock... shocks) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        if (name.isBlank()) {
            throw new IllegalArgumentException("A scenario must have a name");
        }
        if (shocks.length == 0) {
            throw new IllegalArgumentException(
                    "Scenario \"" + name + "\" has no shocks - a scenario with nothing in it "
                            + "is not a scenario, and MarketShock.none() already exists for "
                            + "callers that genuinely want a no-op.");
        }
        for (MarketShock shock : shocks) {
            Objects.requireNonNull(shock, "shock");
        }
        return new Scenario(name, description, MarketShock.composite(List.of(shocks)));
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** The composed shock every given component contributed to. */
    public MarketShock shock() {
        return shock;
    }

    @Override
    public String toString() {
        return description.isBlank() ? name : name + " (" + description + ")";
    }
}
