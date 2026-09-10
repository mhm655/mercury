package com.mercury.risk;

import com.mercury.core.money.Money;
import com.mercury.trade.Counterparty;
import java.util.List;

/**
 * The one limit this milestone ships: a counterparty's projected exposure must not exceed its
 * stated {@code CreditLimit}.
 *
 * <h2>Gross notional, not mark-to-market</h2>
 * "Exposure" here is the running sum of the absolute consideration of every trade booked
 * against a counterparty since inception - not a mark-to-market or potential-future-exposure
 * figure. A closing trade adds to it rather than netting it down. This is a stated,
 * deliberately narrow choice - see {@code docs/KNOWN_GAPS.md} - the same way
 * {@code CostBasisMethod.AVERAGE_COST} being the default is a stated choice among several
 * defensible ones, not the only correct answer.
 *
 * <p>Strictly greater than the maximum is a breach; landing exactly on it is not - a limit is a
 * ceiling a book may touch, not a strict upper bound it must stay clear of.
 *
 * <p>Stateless and thread-safe.
 */
public final class CounterpartyExposureLimit implements RiskLimit {

    private static final String NAME = "counterparty exposure";

    @Override
    public LimitCheckResult check(Counterparty counterparty, Money projectedExposure) {
        Money maximum = counterparty.creditLimit().maximum();
        if (projectedExposure.isGreaterThan(maximum)) {
            return new LimitCheckResult(
                    List.of(new LimitBreach(NAME, counterparty.id(), projectedExposure, maximum)));
        }
        return LimitCheckResult.approved();
    }

    @Override
    public String toString() {
        return NAME;
    }
}
