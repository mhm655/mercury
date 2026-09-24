package com.mercury.trade;

import com.mercury.core.id.CounterpartyId;
import java.util.Objects;

/**
 * A named counterparty Mercury faces on OTC trades - "an entity with credit limits", per
 * {@code docs/DESIGN_PROPOSAL.md} section 3.4.
 *
 * <h2>Data only, deliberately</h2>
 * This carries a stated {@link CreditLimit} and nothing that checks a proposed trade against
 * it. Section A2.8 of the design doc argued for adding the entity before anything enforced it
 * - "cheap to add now, invasive to retrofit" - and M9 supplied the enforcement without
 * touching this type: {@code CounterpartyExposureLimit} does the pro-forma projection and
 * {@code OtcNegotiationVenue} rejects the breach. The limit is stated here and applied
 * elsewhere, which is why adding it early cost nothing to keep.
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
        // Unlike an id, a name may be any script - but never a control or format character,
        // which would let it rewrite the terminal or reverse the text it is printed beside.
        name.codePoints()
                .filter(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                .findFirst()
                .ifPresent(c -> {
                    throw new IllegalArgumentException(
                            "A counterparty name must not contain control or format characters, "
                                    + "but has U+%04X".formatted(c));
                });
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
