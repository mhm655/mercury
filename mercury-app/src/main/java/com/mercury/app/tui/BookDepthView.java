package com.mercury.app.tui;

import com.mercury.matching.OrderBook;
import com.mercury.matching.OrderBook.DepthEntry;
import com.mercury.matching.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Formats one order book's top levels as plain text lines - pure data assembly, no ANSI and
 * no terminal, so it is testable on its own. {@code TuiDemo} adds colour and writes the
 * result.
 */
final class BookDepthView {

    private BookDepthView() {
    }

    static List<String> render(String label, OrderBook book, int levels) {
        List<String> lines = new ArrayList<>();
        lines.add(label);
        lines.addAll(sideLines("ASK", book.depth(Side.SELL, levels)));
        lines.addAll(sideLines("BID", book.depth(Side.BUY, levels)));
        return lines;
    }

    private static List<String> sideLines(String side, List<DepthEntry> entries) {
        if (entries.isEmpty()) {
            return List.of(String.format(Locale.ROOT, "  %s (no resting orders)", side));
        }
        List<String> lines = new ArrayList<>();
        for (DepthEntry entry : entries) {
            lines.add(String.format(Locale.ROOT, "  %-4s %10s  %8d  (%d order%s)",
                    side, entry.price(), entry.quantity(), entry.orderCount(),
                    entry.orderCount() == 1 ? "" : "s"));
        }
        return lines;
    }
}
