package com.mercury.risk;

import com.mercury.core.id.CounterpartyId;
import com.mercury.core.money.Money;
import java.util.Objects;

/**
 * One risk limit firing: {@code counterparty}'s exposure, as it would be if the trade under
 * consideration executed, exceeded {@code maximum}.
 *
 * <h2>A fact, not an exception</h2>
 * {@code com.mercury.core.MercuryException}'s own javadoc draws this line: a breach is "an
 * expected business outcome rather than a defect." This mirrors
 * {@code com.mercury.matching.SelfTradePrevention} - a rule firing is real information a
 * caller can inspect, not a condition to catch.
 *
 * <p>Immutable and thread-safe.
 */
public record LimitBreach(
        String limitName, CounterpartyId counterparty, Money projectedExposure, Money maximum) {

    public LimitBreach {
        Objects.requireNonNull(limitName, "limitName");
        Objects.requireNonNull(counterparty, "counterparty");
        Objects.requireNonNull(projectedExposure, "projectedExposure");
        Objects.requireNonNull(maximum, "maximum");
        if (limitName.isBlank()) {
            throw new IllegalArgumentException("A limit breach must name the limit that fired");
        }
        if (!projectedExposure.isGreaterThan(maximum)) {
            throw new IllegalArgumentException(
                    "Not a breach: projected exposure " + projectedExposure + " does not exceed "
                            + "the maximum " + maximum);
        }
    }

    @Override
    public String toString() {
        return limitName + ": " + counterparty + " projected exposure " + projectedExposure
                + " exceeds maximum " + maximum;
    }
}
