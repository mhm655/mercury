package com.mercury.execution;

import com.mercury.core.id.InstrumentId;
import com.mercury.instrument.TradabilityProfile;
import com.mercury.matching.Side;

/**
 * What a caller wants to do: buy or sell some quantity of an instrument, through whichever
 * venue actually handles it.
 *
 * <p>A sealed interface with exactly the two shapes {@code docs/DESIGN_PROPOSAL.md} section
 * A2.1 calls for - {@link OrderBookInstruction} for exchange-traded instruments,
 * {@link OtcInstruction} for OTC ones - rather than one instruction record carrying fields
 * that mean something for one venue and nothing for the other.
 *
 * <p>Sealed rather than merely abstract, and that now earns its keep:
 * {@link ExecutionRouter} matches over these two cases in a {@code switch} the compiler
 * checks for exhaustiveness, so adding a third shape is a compile error at the router rather
 * than a runtime surprise at a venue.
 */
public sealed interface ExecutionInstruction permits OrderBookInstruction, OtcInstruction {

    InstrumentId instrumentId();

    Side side();

    /**
     * The tradability an instrument must have for this instruction to be the right shape for
     * it.
     *
     * <p>Stated by the instruction rather than inferred by the router, because it is a fact
     * about the instruction: an {@link OrderBookInstruction} names a price and a time in
     * force, which only mean something on a book. {@link ExecutionRouter} checks it against
     * the instrument's own {@code tradability()} - the two disagreeing means the caller built
     * the wrong kind of instruction, and that is worth saying plainly rather than discovering
     * as a cast failure inside whichever venue it reached.
     */
    TradabilityProfile requiredProfile();
}
