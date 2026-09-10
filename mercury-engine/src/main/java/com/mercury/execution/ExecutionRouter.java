package com.mercury.execution;

import com.mercury.core.time.SimulationClock;
import com.mercury.instrument.FinancialInstrument;
import com.mercury.trade.Trade;
import java.util.List;
import java.util.Objects;

/**
 * Routes an instruction to whichever venue actually handles the instrument, by
 * {@code TradabilityProfile} - never by {@code instanceof} on the instrument's concrete
 * type. This is what makes {@code FinancialInstrument.tradability()} a real, called method
 * rather than the orphaned accessor its own javadoc warned it would become if M8 did not
 * read it.
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
        ExecutionVenue venue = instrument.tradability().isExchangeTraded()
                ? orderBookVenue : otcNegotiationVenue;
        return venue.execute(instruction, clock);
    }
}
