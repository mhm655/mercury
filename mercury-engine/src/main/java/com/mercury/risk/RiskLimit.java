package com.mercury.risk;

import com.mercury.core.money.Money;
import com.mercury.trade.Counterparty;
import java.util.List;
import java.util.Objects;

/**
 * A rule that judges a counterparty's exposure, as it would be if a proposed trade executed.
 *
 * <p>Composite, for the same reason {@code com.mercury.marketdata.MarketShock} is: a limit set
 * genuinely is a tree ("gross exposure under 50m <em>and</em> single-trade size under 5m"), and
 * a caller checking a trade should not have to know whether it holds one rule or twenty. Only
 * one leaf ({@link CounterpartyExposureLimit}) exists yet - the composite machinery is real and
 * tested anyway, because it is what lets a second rule plug in later without any call site
 * changing, exactly the argument that justified {@code MarketShock}'s shape.
 *
 * <h2>Pro-forma, not point-in-time</h2>
 * {@code projectedExposure} is supplied already computed <em>as it would be after</em> the
 * trade under consideration - {@code docs/DESIGN_PROPOSAL.md} section A2.6's "evaluate limits
 * against the portfolio as it would be if the trade executed." This interface only judges the
 * projected figure; computing it is the caller's job (see
 * {@code com.mercury.execution.OtcNegotiationVenue}), because doing so needs collaborators -
 * a market snapshot, a running exposure total - a limit rule has no business knowing about.
 *
 * <p>A breach is a value, not a thrown exception - see {@code com.mercury.core.MercuryException}'s
 * own javadoc on this point. Implementations must be pure and stateless.
 */
@FunctionalInterface
public interface RiskLimit {

    /**
     * Judges {@code projectedExposure} against this limit for {@code counterparty}.
     *
     * @return a result carrying zero or more {@link LimitBreach}es
     */
    LimitCheckResult check(Counterparty counterparty, Money projectedExposure);

    /** Both limits, breaches from either reported together. */
    default RiskLimit and(RiskLimit other) {
        Objects.requireNonNull(other, "other");
        return composite(List.of(this, other));
    }

    // ------------------------------------------------------------- factories

    /** Never breaches. The identity of {@link #and}. */
    static RiskLimit none() {
        return (counterparty, projectedExposure) -> LimitCheckResult.approved();
    }

    /** Every limit in turn, all their breaches collected together. */
    static RiskLimit composite(List<RiskLimit> limits) {
        List<RiskLimit> parts = List.copyOf(limits);
        if (parts.isEmpty()) {
            return none();
        }
        return (counterparty, projectedExposure) -> {
            List<LimitBreach> breaches = parts.stream()
                    .flatMap(part -> part.check(counterparty, projectedExposure).breaches().stream())
                    .toList();
            return new LimitCheckResult(breaches);
        };
    }
}
