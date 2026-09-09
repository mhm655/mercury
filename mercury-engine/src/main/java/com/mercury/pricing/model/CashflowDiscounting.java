package com.mercury.pricing.model;

import com.mercury.core.money.Currency;
import com.mercury.instrument.Cashflow;
import com.mercury.marketdata.MarketDataSnapshot;

/**
 * Present-values a set of dated amounts. The arithmetic two pricers share.
 *
 * <h2>Extracted at M6, and only at M6</h2>
 * The design proposal originally called for a Template Method here: an abstract
 * discounted-cashflow base class with a per-instrument projection step to override. That was
 * struck out at M5 because there was no varying step - a bond and an FX forward both simply
 * hand over their cashflows, and a base class with two subclasses adding only a type token
 * would have been ceremony.
 *
 * <p>M6 is the second case, and it does justify sharing something. A swap's fixed leg is
 * discounted exactly the way a bond is, while its floating leg has to be projected off a
 * curve first. So the shared part is real - and it is a <b>function</b>, not a base class.
 * There is still nothing for a subclass to override; there is a sum that two models both need
 * to compute the same way, and a static method expresses that with no hierarchy, no protected
 * members and no constructor chain.
 *
 * <p>Worth saying plainly, because the interesting outcome here is a negative one: waiting for
 * the second case did not vindicate the pattern the design predicted. It showed the pattern
 * was never the right shape, and that what the two pricers actually had in common was four
 * lines of arithmetic.
 *
 * <h2>The rule these lines encode</h2>
 * Each cashflow is discounted on <em>its own</em> currency's curve and then converted at spot.
 * That order is not a preference: the amount is certain in its own currency, so it is that
 * currency's time value which applies. Converting first and discounting on the target curve
 * would price a euro payment using dollar interest rates - which is exactly what makes an FX
 * forward, whose two legs settle in different currencies, come out right.
 *
 * <p>Stateless, pure and thread-safe.
 */
final class CashflowDiscounting {

    private CashflowDiscounting() {
    }

    /**
     * The present value of {@code cashflows}, expressed in {@code target}.
     *
     * <p>Cashflows are assumed to fall after the market's valuation date; callers filter
     * already-settled ones out, and the curve refuses a date in the past rather than
     * compounding it forward.
     */
    static double presentValue(Iterable<Cashflow> cashflows, MarketDataSnapshot market,
                               Currency target) {
        double presentValue = 0.0;
        for (Cashflow cashflow : cashflows) {
            Currency currency = cashflow.amount().currency();
            double discountFactor = market.yieldCurve(currency)
                    .discountFactor(cashflow.paymentDate());
            double amount = cashflow.amount().amount().doubleValue();
            presentValue += amount * discountFactor * market.fxRate(currency, target);
        }
        return presentValue;
    }
}
