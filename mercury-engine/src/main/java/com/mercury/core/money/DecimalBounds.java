package com.mercury.core.money;

import java.math.BigDecimal;

/**
 * Refuses decimals whose size would make normalising them expensive, before any value type
 * tries.
 *
 * <p>{@code new BigDecimal("1e20000000")} parses instantly - it is a one-digit number with an
 * exponent - but {@code setScale(2)} then has to build its twenty-million-digit unscaled
 * value, which took 7.7 seconds from a ten-character string. The same is true in reverse of
 * a huge positive scale being rounded away. Every value type here normalises its scale on
 * construction, so every one of them was a way to turn a short input into seconds of CPU and
 * a large allocation. That is harmless in a demo and a denial of service the moment amounts
 * arrive from outside the process, as the planned REST phase will have them do.
 *
 * <p>The limits are far beyond anything legitimate: a hundred integer digits is a
 * googol, and a scale of a thousand leaves room for {@code BigDecimal.valueOf} on the
 * smallest positive double (a scale of a few hundred), which {@link Money#fromModelValue}
 * can meet on its way to rounding it to zero.
 */
final class DecimalBounds {

    static final int MAX_INTEGER_DIGITS = 100;
    static final int MAX_SCALE = 1_000;

    private DecimalBounds() {
    }

    /** @throws IllegalArgumentException if {@code value} is outside the bounds above */
    static void requireReasonable(BigDecimal value, String what) {
        int scale = value.scale();
        // precision - scale is the count of digits left of the decimal point; checking the
        // scale first keeps that subtraction from overflowing on a pathological exponent.
        if (scale > MAX_SCALE || scale < -MAX_INTEGER_DIGITS
                || (long) value.precision() - scale > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException(
                    what + " is out of range: at most " + MAX_INTEGER_DIGITS + " integer digits and "
                            + MAX_SCALE + " decimal places are accepted, but got precision "
                            + value.precision() + " and scale " + scale);
        }
    }
}
