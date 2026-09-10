package com.mercury.execution;

import com.mercury.core.id.InstrumentId;
import com.mercury.matching.Side;

/**
 * What a caller wants to do: buy or sell some quantity of an instrument, through whichever
 * venue actually handles it.
 *
 * <p>A sealed interface with exactly the two shapes {@code docs/DESIGN_PROPOSAL.md} section
 * A2.1 calls for - {@link OrderBookInstruction} for exchange-traded instruments,
 * {@link OtcInstruction} for OTC ones - rather than one instruction record carrying fields
 * that mean something for one venue and nothing for the other.
 */
public sealed interface ExecutionInstruction permits OrderBookInstruction, OtcInstruction {

    InstrumentId instrumentId();

    Side side();
}
