package com.mercury.trade;

import com.mercury.core.id.CounterpartyId;
import java.util.Objects;

/**
 * A named counterparty Mercury faces on OTC trades - "an entity with credit limits", per
 * {@code docs/DESIGN_PROPOSAL.md} section 3.4.
 *
 * <h2>Data only, at this milestone</h2>
 * This carries a stated {@link CreditLimit} and nothing that checks a proposed trade
 * against it. Section A2.8 of the design doc argues for adding the entity now regardless -
 * "cheap to add now, invasive to retrofit" - while the pro-forma exposure projection and
 * breach-rejection machinery it needs arrive at M9.
 *
 * <h2>Entity equality</h2>
 * Two counterparties are the same counterparty if they share an id, regardless of what
 * else differs - the same reasoning {@code InstrumentIdentityTest} established for
 * {@code Bond} and {@code InterestRateSwap} (an entity's identity is its id, not its
 * fields).
 *
 * <p>Immutable and thread-safe.
 */
public record Counterparty(CounterpartyId id, String name, CreditLimit creditLimit) {

    public Counterparty {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(creditLimit, "creditLimit");
        if (name.isBlank()) {
            throw new IllegalArgumentException("A counterparty must have a name");
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Counterparty other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return name + " (" + id + "), limit " + creditLimit;
    }
}
