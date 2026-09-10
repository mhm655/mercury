package com.mercury.execution;

import com.mercury.core.time.SimulationClock;
import com.mercury.trade.Trade;
import java.util.List;

/**
 * Somewhere a trade can actually happen: an order book, or a bilateral negotiation.
 *
 * <p>One shared interface for both, per {@code docs/DESIGN_PROPOSAL.md} section A2.1 -
 * {@link OrderBookVenue} and {@link OtcNegotiationVenue} each implement this and are
 * dispatched to by {@link ExecutionRouter} on the instrument's {@code TradabilityProfile},
 * never by {@code instanceof}.
 *
 * <p>Returns a list rather than a single {@link Trade}: an order can produce zero fills
 * (nothing to book), one, or several against different counterparties at different prices
 * as it sweeps a book, and each of those is its own trade - never one blended average. See
 * {@link OrderBookVenue} for why. {@link OtcNegotiationVenue} always returns a singleton
 * list, which keeps this interface genuinely one shape rather than two pretending to be one.
 */
public interface ExecutionVenue {

    /**
     * Executes {@code instruction}, returning every {@link Trade} it produced (possibly
     * none).
     *
     * @throws IllegalArgumentException if {@code instruction} is not the shape this venue
     *                                  handles
     */
    List<Trade> execute(ExecutionInstruction instruction, SimulationClock clock);
}
