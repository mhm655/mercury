# Milestone log

What each milestone delivered and what its reviews found, newest first. This used to open the
README; it moved here so the README can lead with what Mercury is and how to run it. Defects
and deliberate omissions are written up separately in [KNOWN_GAPS.md](KNOWN_GAPS.md).

The runnables named below are now commands on one jar - java -jar mercury-app/target/mercury.jar
lifecycle, isk, montecarlo, or walkthrough for all of it in sequence.

**M13 complete** — **concurrency, chosen per component** rather than "add threads", and
measured rather than assumed. Three pieces, and the measurements are the interesting part.

**Parallel Monte Carlo.** Paths are cut into fixed 4,096-path blocks, each drawing from a
`SplittableRandom` split from the run seed - eagerly, in one order, on the calling thread,
because splitting advances the parent. Block boundaries and streams depend on the seed and
the path count and on nothing else, so the same seed produces a **bit-identical** price and
VaR on `SimulationWorkers.sequential()`, on one worker and on twelve; the tests assert exact
equality rather than a tolerance. Splitting per *worker* would have been reproducible only
at a fixed worker count - a VaR computed on a laptop could not be reproduced on a server -
and summing block results in completion order would have made the last bits depend on thread
scheduling. For VaR the expensive part is revaluation, not the draw, so each block revalues
its own paths on its own worker and `HistoricalVaRCalculator` gained a `measure(List<Money>,
double)` overload: the percentile math stays in the one class that owns it and knows nothing
about threads. Option pricing reaches **5.9× on 12 workers**; VaR plateaus at **2.6× from 4
workers**, and a GC-profiled rerun says why - every run allocates ~285 MB and the allocation
rate flattens at ~4.6 GB/s exactly where throughput stops, with GC pauses only a few percent.
The limit is the revaluation path's allocation profile, not the parallel structure, and
`docs/BENCHMARKS.md` §5 says so, including that the mechanism is inferred from a correlation
rather than measured with hardware counters.

**An event bus, with something real listening.** `EventBus` has two implementations:
synchronous (the default everywhere - delivery on the publishing thread, in subscription
order, and a subscriber that throws fails the publisher, so a ledger that refuses a trade
cannot leave the execution looking successful) and asynchronous (one dispatcher thread, not a
pool, because a pool would let a blotter see a later trade before an earlier one; subscriber
failures isolated through a handler; `close()` drains so a caller can publish, close and
read). §5.6 c) calls async-by-default a classic mistake and nothing here defaults to it. Both
venues now announce every trade they mint as a `TradeExecuted`, published while the book or
the exposure record is still exclusively held, so no subscriber can see a trade the venue has
not yet counted; a rejected negotiation announces nothing. The consumer is real rather than
hypothetical: `LedgerKeeper` holds the mutable cell that immutable `PortfolioLedger`
deliberately does not, books the trades its owner made, and ignores the other side of every
order-book fill. That owner filter had been a hand-written loop inside the walkthrough demo -
a rule living in a demo instead of in the engine. The walkthrough now books nothing by hand,
subscribes a keeper and a printer, and prints exactly what it printed before.

**Single-writer books, opt-in, and the prediction that failed.** Each instrument's book, the
owners of its resting orders and the lane guarding them are one object with one writer.
`BookConcurrency` chooses who that writer is: `INLINE` matches on the calling thread under a
per-book lock, `THREAD_PER_BOOK` gives each book a thread fed from a command queue - §5.6 a)'s
single-writer engine, one book with one owner. Both produce identical trades, asserted by
running one script through each. The venue-wide `synchronized` is gone, so instruments no
longer wait for each other even inline. Then the benchmark disagreed with the design doc:
spreading twelve callers across twelve books is **3.27×** one book, confirming that claim, but
`THREAD_PER_BOOK` is **1.7× slower on one book and 3.8× slower on twelve**. The reason is in
this implementation rather than in the idea - `execute` returns its trades, so every caller
pays an enqueue, a park and a wake-up (~0.9 µs) around a matching operation faster than that,
and twelve writer threads on top of twelve callers oversubscribe a 12-thread CPU. LMAX's win
needs fire-and-forget submission and a spinning queue; Mercury has the event half of that and
not the submission half. So `INLINE` stays the default on the measurement rather than on
taste, and the gap is recorded in `docs/KNOWN_GAPS.md` rather than quietly left out.

**M12 complete** — single-threaded Monte Carlo: a second, independent pricer for
`EuropeanOption` (`MonteCarloOptionModel`, registered through `PricingService` exactly the
way the registry exists to enable - `PricingModel`'s own javadoc names "priceable by
Black-Scholes and by a binomial tree, so the two can be cross-checked" as the reason pricing
is a registry at all), and Monte Carlo Value at Risk with **Expected Shortfall**. GBM has a
closed-form terminal distribution, so `GeometricBrownianMotion.terminalValue` is an exact
draw - no Euler-Maruyama time-stepping error, only genuine Monte Carlo sampling error, which
the convergence tests show actually shrinking (92, then 23, then ~3 dollars of error at
100 / 10,000 / 1,000,000 paths on the same option) rather than asserting a single lucky
agreement. *(Those three figures were 195, 32 and ~1 as M12 shipped. M13 cut a run into
fixed-size blocks with a stream split per block, so a given seed now draws a different set of
paths - the convergence is the claim, and it is unchanged; the particular draws behind it were
never the claim.)* Monte Carlo VaR turned out to need no new percentile machinery at all: it
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
