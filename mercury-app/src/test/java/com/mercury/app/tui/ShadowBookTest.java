package com.mercury.app.tui;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.money.Price;
import com.mercury.execution.OrderBookInstruction;
import com.mercury.matching.OrderBook;
import com.mercury.matching.Side;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A shadow book must reproduce whatever the real venue's own, otherwise inaccessible book
 * would show - see {@link ShadowBook}'s javadoc. These tests exercise it the same way
 * {@code OrderBookVenue} exercises a real book: submit, cross, check what is left resting.
 */
class ShadowBookTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final CounterpartyId SELLER = CounterpartyId.of("CPTY-SELLER");
    private static final CounterpartyId BUYER = CounterpartyId.of("CPTY-BUYER");

    @Test
    void aMirroredRestingOrderAppearsInDepth() {
        ShadowBook shadow = new ShadowBook(AAPL);

        shadow.mirror(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("195.40"), 500, SELLER));

        List<OrderBook.DepthEntry> asks = shadow.book().depth(Side.SELL, 5);
        assertThat(asks).hasSize(1);
        assertThat(asks.get(0).quantity()).isEqualTo(500);
        assertThat(asks.get(0).price()).isEqualTo(Price.of("195.40"));
    }

    @Test
    void aCrossingMirroredOrderReducesRestingDepthOnTheOtherSide() {
        ShadowBook shadow = new ShadowBook(AAPL);
        shadow.mirror(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("195.40"), 500, SELLER));

        shadow.mirror(OrderBookInstruction.limit(AAPL, Side.BUY, Price.of("195.50"), 300, BUYER));

        List<OrderBook.DepthEntry> asks = shadow.book().depth(Side.SELL, 5);
        assertThat(asks).hasSize(1);
        assertThat(asks.get(0).quantity()).isEqualTo(200);
        assertThat(shadow.book().depth(Side.BUY, 5)).isEmpty();
    }

    @Test
    void twoMirroredOrdersAtTheSamePriceAggregateWithoutIdCollisions() {
        ShadowBook shadow = new ShadowBook(AAPL);

        shadow.mirror(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("195.40"), 100, SELLER));
        shadow.mirror(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("195.40"), 200, SELLER));

        OrderBook.DepthEntry level = shadow.book().depth(Side.SELL, 5).get(0);
        assertThat(level.quantity()).isEqualTo(300);
        assertThat(level.orderCount()).isEqualTo(2);
    }

    @Test
    void aMirroredMarketOrderTakesLiquidityWithoutResting() {
        ShadowBook shadow = new ShadowBook(AAPL);
        shadow.mirror(OrderBookInstruction.limit(AAPL, Side.SELL, Price.of("195.40"), 100, SELLER));

        shadow.mirror(OrderBookInstruction.market(AAPL, Side.BUY, 100, BUYER));

        assertThat(shadow.book().depth(Side.SELL, 5)).isEmpty();
        assertThat(shadow.book().isEmpty()).isTrue();
    }
}
