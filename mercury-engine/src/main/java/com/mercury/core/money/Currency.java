package com.mercury.core.money;

/**
 * An ISO 4217 currency, restricted to the set Mercury simulates.
 *
 * <h2>Why not {@link java.util.Currency}?</h2>
 * The JDK type covers all ~180 ISO codes and reads its data from the platform locale
 * database. For this engine that is a poor trade:
 *
 * <ul>
 *   <li>An enum gives exhaustive {@code switch} - the compiler tells us when a new
 *       currency needs handling somewhere, instead of a {@code default} branch silently
 *       swallowing it.</li>
 *   <li>Identity comparison and {@code EnumMap}/{@code EnumSet} are free, which matters
 *       for per-currency cash accounts and FX exposure buckets.</li>
 *   <li>{@code java.util.Currency} is not {@code Comparable} and its behaviour can vary
 *       with the JDK's locale data. Reproducibility is a hard requirement here.</li>
 * </ul>
 *
 * The cost is that we support a fixed set of currencies. A simulation does not need
 * more, and adding one is a single line.
 *
 * <h2>Minor units</h2>
 * {@code minorUnits} is the number of decimal places the currency is quoted to, and it
 * is <em>not</em> always 2. JPY has none: a yen amount of "100.50" is not a real
 * quantity of money. {@link Money} normalises to this scale on construction, so the
 * distinction is enforced by the type rather than remembered by the programmer.
 */
public enum Currency {

    USD("US Dollar", 2),
    EUR("Euro", 2),
    GBP("Pound Sterling", 2),
    CHF("Swiss Franc", 2),
    CAD("Canadian Dollar", 2),
    AUD("Australian Dollar", 2),

    /** Zero minor units. The reason {@link Money} cannot assume two decimal places. */
    JPY("Japanese Yen", 0);

    private final String displayName;
    private final int minorUnits;

    Currency(String displayName, int minorUnits) {
        this.displayName = displayName;
        this.minorUnits = minorUnits;
    }

    /** Human-readable name, e.g. {@code "US Dollar"}. */
    public String displayName() {
        return displayName;
    }

    /**
     * Number of decimal places this currency is quoted to; the scale every
     * {@link Money} in this currency carries.
     */
    public int minorUnits() {
        return minorUnits;
    }

    /** The ISO 4217 alphabetic code, e.g. {@code "USD"}. */
    public String code() {
        return name();
    }
}
