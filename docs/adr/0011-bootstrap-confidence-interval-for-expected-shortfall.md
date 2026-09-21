# ADR 0011 — Bootstrap confidence interval for Expected Shortfall

**Status:** Accepted
**Milestone:** M24

## Context

`HistoricalVaRCalculator.valueAtRiskConfidenceInterval` already answers "how precise is this
VaR estimate" cheaply: VaR is one order statistic — the `k`-th smallest of `n` scenarios — so
its sampling uncertainty follows directly from treating the *count* of scenarios at or below
the true quantile as Binomial(`n`, `p`), approximated Normal. That maps a band of plausible
*ranks* straight onto two more positions in the already-sorted P&L list. No resampling, no
extra revaluation — the method's own javadoc records that a bootstrap was "considered and set
aside" for VaR specifically because this cheaper trick existed.

Expected Shortfall has no equivalent order statistic to hang that trick on: it averages a
whole tail of variable size (every scenario at least as bad as the VaR threshold), not one
ranked position. The rank-uncertainty argument simply does not apply to an average. Until this
milestone, `expectedShortfall` had a point estimate only — `docs/KNOWN_GAPS.md` recorded this
explicitly as "a genuinely different, harder statistical problem... not a small extension of
the VaR one," deferred rather than approximated incorrectly.

## Decision

Add `expectedShortfallConfidenceInterval(Portfolio, List<MarketShock>, MarketDataSnapshot,
LocalDate, double confidenceLevel, long seed)`. Implementation: resample the already-sorted
P&L list with replacement 1,000 times (`BOOTSTRAP_RESAMPLES`, a named constant), compute
Expected Shortfall on each resample via the same private `expectedShortfall(RankedScenarios)`
the point estimate already uses, and report the `CONFIDENCE_INTERVAL_LEVEL` (95%, the same
fixed meta-confidence `valueAtRiskConfidenceInterval` already uses) percentile band of the
resulting distribution. Every overload takes an explicit `seed`, seeding a `SplittableRandom` —
the same convention `com.mercury.simulation.MonteCarloVaRCalculator` already uses — so the same
seed against the same scenarios gives the same interval, bit for bit, matching the project-wide
reproducibility invariant.

Only the `Portfolio`-based overload was built. `valueAtRisk`/`expectedShortfall`/`measure` each
have a second, precomputed-`List<Money>` overload because M13's parallel Monte Carlo actually
calls it (revaluing paths across worker threads, then asking this class only for the
percentile math). No caller needs the same shape for `expectedShortfallConfidenceInterval` yet
— `NoOrphanedApiTest` caught this directly during implementation, when a symmetric overload was
added "for consistency" and had no caller anywhere. Removed rather than kept: an unused method
with a javadoc justifying its existence is the exact failure mode that test exists to prevent.
Add it the moment a real caller needs it, the same as every other capability in this engine.

## Rationale

**Why bootstrap rather than an asymptotic Expected Shortfall confidence interval.** A
closed-form asymptotic formula for ES's own sampling distribution exists in the literature, but
it needs assumptions this class deliberately avoids everywhere else — typically a density
estimate at the VaR quantile, which reintroduces exactly the kind of distributional assumption
the class's own "distribution-free, matching the rest of this class" principle rules out for
VaR's interval. A bootstrap needs no such assumption, at the cost of genuinely repeating the
ranking `B` times — precisely the trade-off the VaR method's javadoc names as "considered and
set aside... only because the cheaper trick existed there." For Expected Shortfall it doesn't,
so the trade-off resolves the other way.

**Why sequential, not parallel.** Historical scenario counts in every caller this engine has
are hundreds to low thousands — nothing like Monte Carlo's tens of thousands of paths. 1,000
resamples of a list that size runs in milliseconds on one thread. Reaching into
`com.mercury.simulation.SimulationWorkers` for this would be new cross-package coupling
(`risk` depending on `simulation` for a capability `simulation` was never built to expose
outside its own package) for a benefit nothing has measured a need for. If a real caller ever
needs this over a much larger sample, that is the moment to revisit — not before.

**Why the same fixed `CONFIDENCE_INTERVAL_LEVEL` as VaR's interval, not a separate parameter.**
That constant's own javadoc already explains why it is deliberately fixed rather than
configurable: to keep it from ever being confused with a caller's own VaR `confidenceLevel`.
The same confusion risk exists identically for an ES interval, so the same fixed level applies
rather than inventing a second, independently configurable meta-confidence knob nothing asks
for.

**Why `mildBoundAtSampleEdge`/`severeBoundAtSampleEdge` are always `false` here.** Those flags
describe VaR's rank-clamping edge case specifically — a rank the sample does not have, clamped
back onto the point estimate. A bootstrap percentile index into a fixed 1,000-element resample
distribution is always well-defined; there is no analogous "ran off the edge of the sample"
condition to flag. Repurposing the flags to mean something else for this method would blur what
they mean for VaR's, so they are simply always `false` here, documented as such rather than
silently reused for an unrelated purpose.

## Alternatives rejected

**An asymptotic Expected Shortfall confidence interval formula.** Rejected — breaks this
class's stated distribution-free convention, for a saving (avoiding the resample loop) that
does not matter at the scenario counts this class actually sees.

**Parallelising the bootstrap via `SimulationWorkers`.** Rejected — new cross-package coupling
for an unconfirmed need; see Rationale above.

**A symmetric precomputed-`List<Money>` overload, mirroring `measure`'s shape.** Built, then
removed once `NoOrphanedApiTest` confirmed nothing called it — see Decision above.

## Consequences

**Good.** Expected Shortfall now carries the same "how much should this figure be trusted"
answer VaR already had, closing a gap the project's own `KNOWN_GAPS.md` named as real rather
than cosmetic. Reproducible: the same seed against the same scenarios reproduces the same
interval exactly, provable directly (and proven in
`HistoricalVaRCalculatorTest.theExpectedShortfallConfidenceIntervalIsDeterministicForTheSameSeed`).

**Cost.** A caller now has to supply and, if they want to reproduce a figure later, record a
seed — the same overhead every Monte Carlo caller in this engine already accepts. The interval
is a genuinely stochastic function of that seed rather than a deterministic function of rank
alone (unlike VaR's), which is an honest property of a bootstrap, not a defect, but is worth
naming since it is a real difference between the two methods sitting side by side in this
class.

**Boundary noted.** Still no distributional assumption on returns; still no caller-facing
option to widen or narrow the bootstrap resample count if 1,000 ever proves insufficient for a
future case — that would be exactly the kind of speculative configurability this project avoids
building ahead of a confirmed need.

## Related

`HistoricalVaRCalculator.valueAtRiskConfidenceInterval`'s own javadoc, which this ADR quotes
directly. `com.mercury.simulation.MonteCarloVaRCalculator`'s seed convention, reused here
rather than reinvented. `docs/KNOWN_GAPS.md`'s former "Risk engine | No confidence interval on
Expected Shortfall" entry, closed by this milestone.
