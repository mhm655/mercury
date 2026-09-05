package com.mercury.instrument;

/**
 * Call or put.
 *
 * <p>Another enum carrying its own behaviour, for the reasons in ADR 0002: a closed,
 * stateless set differing in one expression. Putting {@code intrinsicValue} here rather
 * than in the pricer means the payoff is defined once and reused by every model that needs
 * it - closed-form, binomial and Monte Carlo alike - so the three cannot disagree about
 * what the contract pays.
 */
public enum OptionType {

    /** The right to buy the underlying at the strike. */
    CALL("Call") {
        @Override
        public double intrinsicValue(double spot, double strike) {
            return Math.max(spot - strike, 0.0);
        }

        @Override
        public OptionType opposite() {
            return PUT;
        }
    },

    /** The right to sell the underlying at the strike. */
    PUT("Put") {
        @Override
        public double intrinsicValue(double spot, double strike) {
            return Math.max(strike - spot, 0.0);
        }

        @Override
        public OptionType opposite() {
            return CALL;
        }
    };

    private final String displayName;

    OptionType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * The payoff if exercised immediately at {@code spot}. Never negative: an option is a
     * right, not an obligation, so it is simply left unexercised when out of the money.
     */
    public abstract double intrinsicValue(double spot, double strike);

    /** The other type. Used by put-call parity checks in the pricing tests. */
    public abstract OptionType opposite();

    public String displayName() {
        return displayName;
    }
}
