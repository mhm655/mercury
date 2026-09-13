# Milestone log

What each milestone delivered and what its reviews found, newest first. This used to open the
README; it moved here so the README can lead with what Mercury is and how to run it. Defects
and deliberate omissions are written up separately in [KNOWN_GAPS.md](KNOWN_GAPS.md).

The runnables named below are now commands on one jar - java -jar mercury-app/target/mercury.jar
lifecycle, isk, montecarlo, or walkthrough for all of it in sequence.

**M12 complete** — single-threaded Monte Carlo: a second, independent pricer for
`EuropeanOption` (`MonteCarloOptionModel`, registered through `PricingService` exactly the
way the registry exists to enable - `PricingModel`'s own javadoc names "priceable by
Black-Scholes and by a binomial tree, so the two can be cross-checked" as the reason pricing
is a registry at all), and Monte Carlo Value at Risk with **Expected Shortfall**. GBM has a
closed-form terminal distribution, so `GeometricBrownianMotion.terminalValue` is an exact
draw - no Euler-Maruyama time-stepping error, only genuine Monte Carlo sampling error, which
the convergence tests show actually shrinking (195, then 32, then ~1 dollar of error at
100 / 10,000 / 1,000,000 paths on the same option) rather than asserting a single lucky
agreement. Monte Carlo VaR turned out to need no new percentile machinery at all: it
generates a scenario per simulated path and hands the list to `HistoricalVaRCalculator`
(M10), the same class now also carrying Expected Shortfall - historical and Monte Carlo VaR
differ only in where the scenario list comes from, never in how the statistic is computed
from it. Randomness is injected and reseeded fresh on every call, never held as mutable
state, so `MonteCarloOptionModel.price(...)` stays exactly as pure a function of its market
snapshot as `BlackScholesModel.price(...)` is - the same discipline `SimulationClock`
already enforces for time, applied to randomness. `SplittableRandom` chosen specifically
because M13's parallel Monte Carlo needs a generator that splits deterministically per
task; M12 never calls `.split()`, but the type costs nothing to have chosen early.

A post-ship review found a VaR figure with no attached precision indicator - equally
trustworthy-looking whether it came from 10 historical days or 200,000 simulated paths.
`HistoricalVaRCalculator.valueAtRiskConfidenceInterval` closes that, and - asked for through
`measure` alongside the point estimate - costs nothing extra to compute. (A later review found
the Monte Carlo calculator asking for the three statistics separately, revaluing every path
three times while this paragraph said otherwise; `measure` is the fix.) VaR is one order statistic, and large-sample theory treats the count of
scenarios at or below the true quantile as Binomial - approximately Normal - so the
plausible band of *ranks* around the point estimate maps straight onto two more positions in
the P&L list already sorted for the point estimate itself. No bootstrap, no repeated
revaluation. `MonteCarloDemo`'s 200,000-path band comes out tight; `RiskEngineDemo`'s
10-day historical one comes out genuinely wide for the same statistic - which is the honest
answer, not a defect in either.

**M11 complete** — the single hardcoded stress scenario the RISK section used to end with is
now three **named scenarios**: `Scenario` (Composite over `MarketShock`, the pattern
`docs/DESIGN_PROPOSAL.md` §6 named for it before M8 existed) wraps a name, a description and
a composed shock, built by a plain factory - `Scenario.of(name, description, MarketShock...)`
- rather than the Builder the design doc also predicted: that reasoning held for `Bond` and
`InterestRateSwap`'s genuinely many optional fields and did not transfer to a type with only
one (see the struck-through correction in §6). Market Crash is the old scenario verbatim, so its
-86,093.00 is unchanged; Rate Shock (+200bp across every currency) isolates the DV01 exposure
the report already prints instead of blending it into four factors at once; Currency Crisis
(EUR/USD -20% and EUR rates +300bp) is the emerging-market pattern of a currency collapsing
while local rates spike to defend it, and is the first scenario in this report to move the
book's two EUR positions in opposite directions at once rather than the same one. Which
scenarios a report is measured against is demo-supplied data (`DemoScenario.scenarios()`),
not an engine constant - the same "listed, not inferred" reasoning `RiskFactors` already
states for risk factors, applied to scenarios for the first time here.

**M10 complete** — the risk engine reports **Gamma and Vega** alongside the Delta, FX delta
and DV01 it already had, and adds a **historical VaR** calculator. Gamma and Vega are
computed the same way every other Greek here is - shock, revalue, difference - and both are
cross-validated against `BlackScholesModel`'s closed form rather than trusted on their own:
Gamma especially, since a second-order finite difference is the delicate one
(`docs/DESIGN_PROPOSAL.md` §5.3.1), and the validation the design doc asks be "written early"
now runs on every build. No new capability interface was added for this - the working
precedent already in the codebase (a hand-rolled analytic check in
`SensitivityCalculatorTest`) was static formulas plus a test, so that is what M10 built on,
rather than a runtime "prefer analytic" dispatch with nothing yet to prefer it for.
Historical VaR reuses the same `valueChangeUnder` primitive a third way (stress testing and
Greeks were the first two): revalue under each of a set of historical daily moves, and
report a percentile of the resulting P&L distribution as the loss - a genuinely different
technique from the Monte Carlo VaR arriving at M12, not an early duplicate of it.

Unlike M8 and M9, this one **does** touch `Main`'s report: Gamma and Vega are risk numbers
the book has actually carried since M4 and never printed, the same gap C-2 found for rate
and FX risk at M5, so they belong in the RISK section that already exists rather than a
side demo. The golden master was re-recorded and diffed by hand - five new lines, nothing
else moved.

**M9 complete** — OTC negotiations are now checked against a **risk limit** before they
execute: `RiskLimit` (a Composite, the same shape `MarketShock` already established) judges
a counterparty's projected exposure — the running gross notional traded against it, plus
the trade under consideration — against its stated `CreditLimit`. A breach is not an
exception; it is a value (`LimitCheckResult`, carrying every `LimitBreach`), and a rejected
negotiation produces no trade and mutates nothing, exactly as the M8 audit insisted
self-trade prevention must be an auditable fact rather than a silent skip.

A post-ship review of M9 found the first cut worth tightening twice more, both now done:
`OtcNegotiationVenue.exposureTo` lets a caller ask how much room is left against a
counterparty without attempting a trade first, and `OtcNegotiationVenue.release` takes a
settled trade's consideration back out of the running total. Without the second one, the
limit would have been a lifetime trading-volume cap rather than anything resembling live
credit exposure — every counterparty would eventually exhaust it permanently no matter how
healthy the relationship, since nothing ever gave exposure back. It still is not a
mark-to-market figure (see `docs/KNOWN_GAPS.md`), but it no longer only grows.

**M8 complete** — trades now have a **lifecycle**: an explicit state machine
(`NEW → VALIDATED → BOOKED → EXECUTED → CONFIRMED → SETTLED`) with an append-only audit
trail, two execution venues (a CLOB order book and an OTC negotiation, routed by instrument
rather than by `instanceof`), and named counterparties. It also closed two gaps the M5
audits had deliberately deferred here: order ids can no longer be reused after a fill, and
self-trade prevention now blocks — and records — a same-owner crossing. CI green on every
push.
