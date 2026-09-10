package com.mercury.risk;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of checking a proposed trade against a {@link RiskLimit}: every
 * {@link LimitBreach} it produced, possibly none.
 *
 * <p>Mirrors {@code com.mercury.matching.MatchResult} - an outcome carried alongside the facts
 * that explain it, rather than a bare boolean a caller would have to go elsewhere to justify.
 *
 * <p>Immutable and thread-safe.
 */
public record LimitCheckResult(List<LimitBreach> breaches) {

    private static final LimitCheckResult APPROVED = new LimitCheckResult(List.of());

    public LimitCheckResult {
        Objects.requireNonNull(breaches, "breaches");
        breaches = List.copyOf(breaches);
    }

    /** No limit fired. */
    public static LimitCheckResult approved() {
        return APPROVED;
    }

    public boolean isApproved() {
        return breaches.isEmpty();
    }

    public boolean isBreached() {
        return !breaches.isEmpty();
    }
}
