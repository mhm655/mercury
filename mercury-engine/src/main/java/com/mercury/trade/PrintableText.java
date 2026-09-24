package com.mercury.trade;

/**
 * The rule for free text a trade carries and the engine later prints - a counterparty's name,
 * a lifecycle event's reason. Any script is allowed, but no control or format character: those
 * would let the text rewrite the terminal it is printed to, forge a line of output, or reverse
 * the text beside it. Identifiers obey the stricter {@code com.mercury.core.id} rule instead.
 */
final class PrintableText {

    private PrintableText() {
    }

    static void require(String text, String what) {
        text.codePoints()
                .filter(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                .findFirst()
                .ifPresent(c -> {
                    // Reported as a code point, never echoed: echoing it would deliver the very
                    // sequence this check exists to stop.
                    throw new IllegalArgumentException(
                            what + " must not contain control or format characters, but has U+%04X"
                                    .formatted(c));
                });
    }
}
