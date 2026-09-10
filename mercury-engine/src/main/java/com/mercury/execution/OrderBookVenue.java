package com.mercury.execution;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.OrderId;
import com.mercury.core.id.TradeId;
import com.mercury.core.money.Currency;
import com.mercury.core.money.Money;
import com.mercury.core.money.Quantity;
import com.mercury.core.time.SimulationClock;
import com.mercury.matching.Fill;
import com.mercury.matching.MatchResult;
import com.mercury.matching.Order;
import com.mercury.matching.OrderBook;
import com.mercury.matching.Side;
import com.mercury.portfolio.InstrumentCatalog;
import com.mercury.trade.Trade;
import com.mercury.trade.TradeStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The exchange-traded {@link ExecutionVenue}: routes an {@link OrderBookInstruction} into a
 * price-time-priority {@link OrderBook} for its instrument, and turns every {@link Fill}
 * that results into {@link Trade}s.
 *
 * <h2>One {@code Trade} per side, per fill</h2>
 * A single fill crosses two orders, and each side is a real trade for its own owner - the
 * resting order's owner traded exactly as much as the aggressor did. Both get a
 * {@code Trade}, each with an empty {@code counterparty}: a central limit order book is
 * anonymous by design (see {@code TradabilityProfile}), so neither side is told who filled
 * against them, exactly as a real exchange member only ever receives its own execution
 * report. Each is derived directly from that fill's own {@code price() x quantity()} -
 * never from an order-level average, which would risk drifting a cent from the sum of the
 * real fills once an order sweeps more than one price level.
 *
 * <h2>Only fills become trades</h2>
 * An order that rests unfilled, or is cancelled with nothing filled, produces no
 * {@code Trade} at all - that outcome is already fully represented by the
 * {@code OrderStatus} on the {@link MatchResult} this venue receives from the book.
 *
 * <h2>Order ids: G-1</h2>
 * {@code OrderBookInstruction} never carries an id; this venue mints one from its own
 * {@link OrderIdGenerator} for every order it submits, which is what makes an id reused
 * after a fill (G-1) unreachable through this, the sole sanctioned entry point. See that
 * class's javadoc.
 */
public final class OrderBookVenue implements ExecutionVenue {

    private final OrderIdGenerator orderIdGenerator = new OrderIdGenerator("OB-");
    private final TradeIdGenerator tradeIdGenerator;
    private final InstrumentCatalog instruments;
    private final Map<InstrumentId, OrderBook> books = new HashMap<>();

    /**
     * Every order this venue has ever submitted, by id, so a resting order's owner can
     * still be recovered once it has filled and left the book. Bounded by how many orders
     * a single venue instance ever submits over its lifetime - acceptable at this
     * milestone's scale, and the same caveat {@link OrderIdGenerator} already carries.
     */
    private final Map<OrderId, CounterpartyId> owners = new HashMap<>();

    public OrderBookVenue(TradeIdGenerator tradeIdGenerator, InstrumentCatalog instruments) {
        this.tradeIdGenerator = Objects.requireNonNull(tradeIdGenerator, "tradeIdGenerator");
        this.instruments = Objects.requireNonNull(instruments, "instruments");
    }

    @Override
    public List<Trade> execute(ExecutionInstruction instruction, SimulationClock clock) {
        if (!(instruction instanceof OrderBookInstruction obi)) {
            throw new IllegalArgumentException(
                    "OrderBookVenue only executes OrderBookInstruction, but received "
                            + instruction.getClass().getSimpleName());
        }
        Objects.requireNonNull(clock, "clock");

        OrderId orderId = orderIdGenerator.next();
        Order order = new Order(orderId, obi.instrumentId(), obi.side(), obi.type(),
                obi.limitPrice(), obi.quantity(), obi.timeInForce(), obi.participant());
        owners.put(orderId, obi.participant());

        OrderBook book = books.computeIfAbsent(obi.instrumentId(), OrderBook::new);
        MatchResult result = book.submit(order);

        Currency currency = instruments.require(obi.instrumentId()).currency();
        List<Trade> trades = new ArrayList<>();
        for (Fill fill : result.fills()) {
            trades.add(tradeFor(fill, fill.aggressorSide(), fill.aggressingOrderId(), currency, clock));
            Side restingSide = fill.aggressorSide().isBuy() ? Side.SELL : Side.BUY;
            trades.add(tradeFor(fill, restingSide, fill.restingOrderId(), currency, clock));
        }
        return List.copyOf(trades);
    }

    /**
     * One side of one fill, as an executed {@code Trade}. {@code side} is this party's own
     * side of the trade (not necessarily the fill's aggressor side), which is what decides
     * the sign of {@code delta} and {@code consideration}.
     */
    private Trade tradeFor(Fill fill, Side side, OrderId orderId, Currency currency,
                           SimulationClock clock) {
        CounterpartyId owner = owners.get(orderId);
        long signedQuantity = side.isBuy() ? fill.quantity() : -fill.quantity();
        Quantity delta = Quantity.of(signedQuantity);
        // Multiply in BigDecimal first, convert to Money once - the same rule
        // PortfolioLedger.tradeAt follows, and for the same reason: rounding the per-unit
        // price to the currency's minor units before multiplying by quantity would scale
        // the rounding error by the fill size instead of rounding the final total once.
        Money consideration = Money.of(
                fill.price().value().multiply(java.math.BigDecimal.valueOf(signedQuantity)), currency);

        Trade trade = Trade.newTrade(tradeIdGenerator.next(), fill.instrumentId(), owner, delta,
                consideration, clock.today(), Optional.empty(), Optional.empty());
        return trade.transitionTo(TradeStatus.VALIDATED, "matched on the order book", clock)
                .transitionTo(TradeStatus.BOOKED, "booked to the ledger", clock)
                .transitionTo(TradeStatus.EXECUTED, "matched in full at " + fill.price(), clock);
    }
}
