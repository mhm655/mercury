package com.mercury.app.tui;

/**
 * The handful of ANSI escape sequences this UI needs - hand-rolled per ADR 0007, not a
 * library, because a few panels redrawn on each step is cursor math and colour codes, not
 * a windowing toolkit's worth of layout and resize handling.
 */
final class AnsiScreen {

    /** Clears the visible screen and moves the cursor to the top-left corner. */
    static final String CLEAR_AND_HOME = "\033[2J\033[H";

    static final String BOLD = "\033[1m";
    static final String CYAN = "\033[36m";
    static final String RESET = "\033[0m";

    private AnsiScreen() {
    }
}
