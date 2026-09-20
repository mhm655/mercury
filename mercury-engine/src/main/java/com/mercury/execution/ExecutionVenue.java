package com.mercury.execution;

import com.mercury.core.time.SimulationClock;
import com.mercury.trade.Trade;
import java.util.List;

/**
 * Somewhere a trade can actually happen: an order book, or a bilateral negotiation.
 *
 * <p>One shared interface for both, per {@code docs/DESIGN_PROPOSAL.md} section A2.1 -
 * {@link OrderBookVenue} and {@link OtcNegotiationVenue} each implement this and are
 * dispatched to by {@link ExecutionRouter}, never by {@code instanceof} on an instrument's
 * concrete type.
 *
 * <h2>Parameterised by the instruction it accepts</h2>
 * A venue takes the one shape of {@link ExecutionInstruction} it can actually execute, and
 * says so in its type. Until M15 this method took the supertype, so both implementations
 * opened by casting and throwing:
 *
 * <pre>
 *   if (!(instruction instanceof OrderBookInstruction obi)) {
 *       throw new IllegalArgumentException("OrderBookVenue only executes ...");
 *   }
 * </pre>
 *
 * and this javadoc documented an {@code @throws IllegalArgumentException} for being handed
 * the wrong shape. {@link ExecutionInstruction} is <em>sealed</em> over exactly two cases,
 * which is enough for the compiler to rule that out entirely - so the design was paying for
 * a sealed hierarchy and then discarding the guarantee at its own boundary. The failure mode
 * the old signature documented is now unrepresentable: there is no call that compiles and
 * hands a venue an instruction it cannot run.
 *
 * <p>Returns a list rather than a single {@link Trade}: an order can produce zero fills
 * (nothing to book), one, or several against different counterparties at different prices
 * as it sweeps a book, and each of those is its own trade - never one blended average. See
 * {@link OrderBookVenue} for why. {@link OtcNegotiationVenue} always returns a singleton
 * list, which keeps this interface genuinely one shape rather than two pretending to be one.
 *
 * @param <I> the instruction shape this venue executes
 */
public interface ExecutionVenue<I extends ExecutionInstruction> {

    /** Executes {@code instruction}, returning every {@link Trade} it produced (possibly none). */
    List<Trade> execute(I instruction, SimulationClock clock);
}
