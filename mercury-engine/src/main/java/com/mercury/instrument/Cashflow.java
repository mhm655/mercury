package com.mercury.instrument;

import com.mercury.core.money.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A single payment of a known amount on a known date.
 *
 * <h2>Signed, not directional</h2>
 * A positive amount is received, a negative amount is paid. Encoding direction in the sign
 * rather than in a separate enum means present-valuing a set of cashflows is a plain sum -
 * no branching on direction, and no chance of adding where you meant to subtract. It also
 * makes an FX forward natural: one leg positive, one negative, in different currencies.
 *
 * <h2>Known amounts only</h2>
 * The amount here is <em>contractually determined</em>. A floating-rate coupon is not: it
 * depends on a forward rate that has to be projected from a curve, and no such thing exists
 * inside an instrument definition. Projected cashflows are the pricer's output, not the
 * instrument's - see {@link CashflowGenerating} for why that boundary is drawn where it is.
 *
 * <p>Immutable and thread-safe.
 */
public record Cashflow(LocalDate paymentDate, Money amount) {

    public Cashflow {
        Objects.requireNonNull(paymentDate, "paymentDate");
        Objects.requireNonNull(amount, "amount");
    }

    public static Cashflow of(LocalDate paymentDate, Money amount) {
        return new Cashflow(paymentDate, amount);
    }

    /** True if this cashflow is money coming in. */
    public boolean isReceipt() {
        return amount.isPositive();
    }

    @Override
    public String toString() {
        return paymentDate + ": " + amount;
    }
}
