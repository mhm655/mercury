package com.mercury.app.tui;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.id.InstrumentId;
import com.mercury.core.id.OrderId;
import com.mercury.core.money.Price;
import com.mercury.matching.Order;
import com.mercury.matching.OrderBook;
import com.mercury.matching.Side;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookDepthViewTest {

    private static final InstrumentId AAPL = InstrumentId.of("AAPL");
    private static final CounterpartyId SELLER = CounterpartyId.of("CPTY-SELLER");
    private static final CounterpartyId BUYER = CounterpartyId.of("CPTY-BUYER");

    @Test
    void anEmptyBookShowsNoRestingOrdersOnBothSides() {
        OrderBook book = new OrderBook(AAPL);

        List<String> lines = BookDepthView.render("AAPL", book, 3);

        assertThat(lines).containsExactly(
                "AAPL",
                "  ASK (no resting orders)",
                "  BID (no resting orders)");
    }

    @Test
    void oneRestingLevelPerSideIsFormattedWithPriceQuantityAndOrderCount() {
        OrderBook book = new OrderBook(AAPL);
        book.submit(Order.limit(OrderId.of("O-1"), AAPL, Side.SELL, Price.of("195.40"), 500, SELLER));
        book.submit(Order.limit(OrderId.of("O-2"), AAPL, Side.BUY, Price.of("195.00"), 100, BUYER));

        List<String> lines = BookDepthView.render("AAPL", book, 3);

        assertThat(lines.get(0)).isEqualTo("AAPL");
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .contains("ASK").contains("195.4").contains("500").contains("1 order"));
        assertThat(lines).anySatisfy(line -> assertThat(line)
                .contains("BID").contains("195").contains("100").contains("1 order"));
    }

    @Test
    void moreThanOneOrderAtALevelIsPluralised() {
        OrderBook book = new OrderBook(AAPL);
        book.submit(Order.limit(OrderId.of("O-1"), AAPL, Side.SELL, Price.of("195.40"), 100, SELLER));
        book.submit(Order.limit(OrderId.of("O-2"), AAPL, Side.SELL, Price.of("195.40"), 200, SELLER));

        List<String> lines = BookDepthView.render("AAPL", book, 3);

        assertThat(lines).anySatisfy(line -> assertThat(line).contains("2 orders"));
    }

    @Test
    void levelCountCapsHowManyRowsAppearPerSide() {
        OrderBook book = new OrderBook(AAPL);
        book.submit(Order.limit(OrderId.of("O-1"), AAPL, Side.SELL, Price.of("195.00"), 100, SELLER));
        book.submit(Order.limit(OrderId.of("O-2"), AAPL, Side.SELL, Price.of("196.00"), 100, SELLER));
        book.submit(Order.limit(OrderId.of("O-3"), AAPL, Side.SELL, Price.of("197.00"), 100, SELLER));

        List<String> lines = BookDepthView.render("AAPL", book, 1);

        long askLines = lines.stream().filter(line -> line.contains("ASK")).count();
        assertThat(askLines).isEqualTo(1);
    }
}
