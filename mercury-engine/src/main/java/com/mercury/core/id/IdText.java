package com.mercury.core.id;

import java.util.Objects;

/**
 * The one rule every identifier's text obeys: trimmed, non-blank, at most {@link #MAX_LENGTH}
 * characters, and printable ASCII only.
 *
 * <p>Identifiers are printed verbatim into the valuation report, the TUI and every exception
 * message, and are compared by exact text. Arbitrary text would let an id carry an ANSI escape
 * that rewrites the reader's terminal, a newline that forges a line of output, a bidi override
 * that reverses what is displayed, or a look-alike character - a Greek capital alpha standing
 * in for the A of {@code AAPL} - that prints identically to a different instrument while
 * keying a separate book. ASCII is not a limitation here: every id this engine mints or uses
 * is ASCII.
 */
final class IdText {

    static final int MAX_LENGTH = 64;

    private IdText() {
    }

    static String require(String value, String what) {
        Objects.requireNonNull(value, "value");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    what + " must be at most " + MAX_LENGTH + " characters, but was "
                            + trimmed.length());
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                // The offending character is reported as a code point, never echoed: echoing
                // it would deliver the very escape sequence this check exists to stop.
                throw new IllegalArgumentException(
                        what + " must be printable ASCII, but has U+%04X at index %d"
                                .formatted((int) c, i));
            }
        }
        return trimmed;
    }
}
