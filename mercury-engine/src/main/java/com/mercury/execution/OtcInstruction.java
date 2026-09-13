package com.mercury.execution;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.BasisPoints;
import com.mercury.core.money.Quantity;
import com.mercury.matching.Side;
import java.util.Objects;

/**
 * An instruction to negotiate a bilateral trade against a named counterparty, through
 * {@link OtcNegotiationVenue}.
 *
 * <p>{@code quantity} is a {@link Quantity}, not the matching engine's primitive
 * {@code long}: OTC notionals are fractional in a way exchange-traded lot sizes are not
 * (see {@code Order}'s own javadoc on that split). {@code spread} is what the venue applies
 * around the instrument's priced mid to produce the traded rate - buyer pays above it,
 * seller receives below it.
 */
public record OtcInstruction(
        InstrumentId instrumentId,
        Side side,
        Quantity quantity,
        CounterpartyId counterparty,
        BasisPoints spread) implements ExecutionInstruction {

    public OtcInstruction {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(counterparty, "counterparty");
        Objects.requireNonNull(spread, "spread");
        if (!quantity.isLong()) {
            throw new IllegalArgumentException(
                    "OTC instruction quantity must be positive, but was " + quantity
                            + ". Direction is carried by Side, not by the sign of the quantity.");
        }
        if (spread.value() < 0) {
            throw new IllegalArgumentException(
                    "Spread must not be negative, but was " + spread);
        }
        if (spread.value() >= MAX_SPREAD_EXCLUSIVE.value()) {
            throw new IllegalArgumentException(
                    "Spread must be below 100%, but was " + spread + ". A seller receives mid x "
                            + "(1 - spread), so 100% gives the instrument away and anything above "
                            + "it has the seller paying the buyer.");
        }
    }

    /** 100%, as basis points. See the constructor for why a spread must stay below it. */
    private static final BasisPoints MAX_SPREAD_EXCLUSIVE = BasisPoints.ofPercent(100.0);
}
