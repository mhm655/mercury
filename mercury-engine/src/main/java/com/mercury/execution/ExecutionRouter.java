package com.mercury.execution;

import com.mercury.core.time.SimulationClock;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.trade.Trade;
import java.util.List;
import java.util.Objects;

/**
 * Routes an instruction to whichever venue actually handles the instrument.
 *
 * <h2>Two things have to agree, and both are checked</h2>
 * An instrument states how it trades ({@code TradabilityProfile}) and an instruction states
 * what it needs ({@link ExecutionInstruction#requiredProfile()}). Routing is only well
 * defined when they match, and this is where that is established - never by
 * {@code instanceof} on the instrument's concrete type, which is what
 * {@code FinancialInstrument.tradability()} exists to make unnecessary.
 *
 * <p>The venue itself is then chosen by matching over the sealed instruction hierarchy, in a
 * {@code switch} with no {@code default}: the compiler checks it covers every case, so a
 * third instruction shape would fail to compile here rather than failing at runtime in a
 * venue. Together those two steps replace what used to be a raw-typed pick followed by a
 * cast inside each venue - see {@link ExecutionVenue}.
 */
public final class ExecutionRouter {

    private final OrderBookVenue orderBookVenue;
    private final OtcNegotiationVenue otcNegotiationVenue;

    public ExecutionRouter(OrderBookVenue orderBookVenue, OtcNegotiationVenue otcNegotiationVenue) {
        this.orderBookVenue = Objects.requireNonNull(orderBookVenue, "orderBookVenue");
        this.otcNegotiationVenue = Objects.requireNonNull(otcNegotiationVenue, "otcNegotiationVenue");
    }

    public List<Trade> execute(FinancialInstrument instrument, ExecutionInstruction instruction,
                               SimulationClock clock) {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(instruction, "instruction");
        if (!instruction.instrumentId().equals(instrument.id())) {
            throw new IllegalArgumentException(
                    "Instruction is for " + instruction.instrumentId() + " but was routed as "
                            + instrument.id() + "; the venue would trade the instruction's "
                            + "instrument on the other one's venue");
        }
        if (instrument.tradability() != instruction.requiredProfile()) {
            throw new IllegalArgumentException(
                    instrument.id() + " is " + instrument.tradability() + " but was given an "
                            + "instruction built for " + instruction.requiredProfile()
                            + ". The instruction carries terms the instrument's venue cannot "
                            + "honour, so it was built for the wrong one - say so here rather "
                            + "than letting a venue discover it.");
        }
        return switch (instruction) {
            case OrderBookInstruction onBook -> orderBookVenue.execute(onBook, clock);
            case OtcInstruction otc -> otcNegotiationVenue.execute(otc, clock);
        };
    }
}
