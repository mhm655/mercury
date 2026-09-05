package com.mercury.instrument;

import java.time.LocalDate;
import java.util.List;

/**
 * Implemented by anything whose future payments are fixed by contract.
 *
 * <h2>What this seam buys</h2>
 * Present-valuing a set of dated cashflows is the same arithmetic whatever produced them:
 * discount each amount by the factor for its payment date and sum. Expressing that
 * capability as an interface means one discounted-cashflow engine serves a bond, an FX
 * forward and a swap's fixed leg. Without it that logic would be written three times, in
 * three places, and would drift.
 *
 * <h2>Why the amounts must be contractually determined</h2>
 * This interface promises {@link Cashflow}s carrying real {@code Money} amounts, which
 * means it can only be implemented where those amounts are actually known from the
 * contract:
 *
 * <ul>
 *   <li><b>Bond</b> - fixed coupons and principal. Known.</li>
 *   <li><b>FX forward</b> - two fixed amounts at an agreed rate. Known.</li>
 *   <li><b>Swap fixed leg</b> - fixed rate on a fixed notional. Known.</li>
 *   <li><b>Swap floating leg</b> - <em>not</em> known. Each coupon depends on a forward
 *       rate projected from a curve that does not exist inside an instrument
 *       definition.</li>
 * </ul>
 *
 * So {@link InterestRateSwap} deliberately does <b>not</b> implement this interface, even
 * though it is obviously a cashflow-bearing product. Its fixed leg does; its floating leg
 * exposes its schedule, index and spread for a pricer to project once it has a curve.
 *
 * <p>Making the swap implement this and return best-guess amounts would be worse than not
 * implementing it: the interface would then mean "cashflows, except sometimes they are
 * made up", and every caller would have to know which case it held - which is exactly the
 * kind of hidden conditional that polymorphism is supposed to remove.
 *
 * <p>This is a correction to an earlier claim in {@code DESIGN_PROPOSAL.md} section 3.3,
 * which said this seam covered swap legs generally. It covers fixed legs.
 */
public interface CashflowGenerating {

    /**
     * Contractual cashflows falling strictly after {@code from}, in date order.
     *
     * <p>Amounts are signed from the holder's perspective: positive received, negative
     * paid. Cashflows on or before {@code from} are excluded, because a coupon already paid
     * is not part of the instrument's remaining value.
     *
     * @param from the valuation date; cashflows must fall strictly after it
     * @return an immutable list, empty once the instrument has no payments left
     */
    List<Cashflow> cashflows(LocalDate from);
}
