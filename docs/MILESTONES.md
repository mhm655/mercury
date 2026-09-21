# Milestone log

What each milestone delivered and what its reviews found, newest first. This used to open the
README; it moved here so the README can lead with what Mercury is and how to run it. Defects
and deliberate omissions are written up separately in [KNOWN_GAPS.md](KNOWN_GAPS.md).

The runnables named below are now commands on one jar - java -jar mercury-app/target/mercury.jar
lifecycle, isk, montecarlo, or walkthrough for all of it in sequence.

**M17 complete** — **asynchronous order submission, narrower than the fix `docs/KNOWN_GAPS.md`
had priced.** M13's benchmark found `BookConcurrency.THREAD_PER_BOOK` **1.7-3.8× slower** than
the `INLINE` default, traced to `SingleWriterBookLane.run` wrapping every command in a
`FutureTask` and parking the caller until the writer thread woke it - roughly 0.9µs of handoff
per order, more than matching itself costs. `KNOWN_GAPS.md` recorded the fix as a deliberate
non-change: closing it "would change `ExecutionVenue`'s contract for every caller in the
codebase." That priced the wrong repair. The measured cost lives entirely inside
`OrderBookVenue`/`SingleWriterBookLane` - `OtcNegotiationVenue` has no threading model at all,
and `ExecutionRouter` is a two-line validation pass-through with no logic of its own to change.

[ADR 0008](adr/0008-fire-and-forget-order-submission.md) records the narrower shape actually
built: `BookLane` gained a second door, `submit(Runnable)`, beside the existing
`run(Supplier<T>)`. `SingleWriterBookLane.submit` queues a plain `Runnable` - no future, no
`.get()` - and returns the instant it is queued; `LockedBookLane.submit` still runs inline
under the same lock `run` uses, since `INLINE` has no writer thread to hand off to, so the win
is specific to `THREAD_PER_BOOK`. `OrderBookVenue.submit(OrderBookInstruction,
SimulationClock)` is new and entirely additive: `execute` and every caller of it -
`ExecutionRouter`, every demo, every `execute`-based test - are untouched. A submitted order's
trades reach a caller only through whatever `EventBus` the venue holds, the same publication
`execute` already does before returning, never as a value handed back from `submit` itself.

`docs/BENCHMARKS.md` §6 gained its own M17 section rather than a bare claim: removing the
handoff roughly doubles throughput on one book and clears the twelve-book figure too,
consistent with the original diagnosis. The same benchmark run is also the reason this
milestone opened a gap instead of only closing one - a sustained twelve-thread synthetic load
against `THREAD_PER_BOOK` ran the JVM out of heap, `OutOfMemoryError`, every writer thread
dying mid-match, reproducibly. Raw enqueue is cheaper than real work with object allocation
behind it, so an unthrottled producer eventually outruns the writer - the identical shape of
finding M13 already recorded for `AsynchronousEventBus`'s own unbounded queue. Rather than
picking a bound and a policy against no real caller's known throughput (sizing against a
guess, the exact thing that entry already declined to do), the new gap is recorded in
`docs/KNOWN_GAPS.md` beside the one it mirrors, not smoothed over by a benchmark window short
enough to hide it.

**M16 complete** — **a terminal UI that replays the engine, rather than simulating one.**
`SimulationClock` only advances when told to — there is nothing to render live in the literal
sense — so `tui` steps through the same six order/negotiation instructions `walkthrough` runs
in one pass, one at a time, blocking on a line of stdin between them and redrawing the whole
screen from scratch after each. [ADR 0007](adr/0007-hand-rolled-ansi-terminal-ui.md) scopes it
as hand-rolled ANSI, not a library: a few panels redrawn on each step is cursor math and colour
codes, not a windowing toolkit's worth of layout and resize handling.

Four panels, built in that order because each one's data source raised its own question.
**Order book depth** has no public reader on the real venue — `OrderBookVenue` keeps its
per-instrument books in a private map, by design (§5.6), and adding a method to
`mercury-engine` for one UI's benefit was the wrong side of that boundary to move. `ShadowBook`
resolves it without one: a second, display-only `OrderBook` per instrument, built from types
already public (`OrderBook`, `Order`, `OrderId`), mirroring every instruction the real venue
receives — an order book is a deterministic function of the orders it gets, so an identical
sequence produces identical depth. **The blotter** raised the same shape of question for a
rejected negotiation, which never becomes a `Trade` and so never reaches the event bus at all;
the answer turned out not to need a new event type, since the caller running the negotiation
already holds the `NegotiationResult` that says so. **P&L and risk** reuse the same
`PortfolioValuationService`, `SensitivityCalculator` and `MonteCarloVaRCalculator` calls
`EndToEndDemo` makes, against `TuiDemo`'s own evolving ledger rather than the fixed golden-
master book. Valuation, P&L, delta and DV01 are cheap and recompute every frame; the
20,000-path Monte Carlo VaR is not, so it recomputes only every few steps and the stale figure
is shown labelled `(as of step N)` rather than silently going out of date — a cadence chosen
over a background thread for the reason the whole UI already blocks on stdin between steps:
there is no frame anyone is waiting on for a second thread to finish faster. **Testability**
turned out to be free rather than a fourth thing to build: each panel is data assembly plus at
most one small piece of cached state, tested without a terminal the same way the rest of this
codebase's demo-facing code is — only `TuiDemo.printFrame` itself writes raw ANSI, and it stays
as thin as `Main`'s own console-facing methods, assembling nothing itself.

This is Phase 4's [preferred interface](DESIGN_PROPOSAL.md#104-phase-4--interface-and-observability)
delivered — the alternative the design proposal argued for building instead of a web dashboard,
on the reasoning that a mediocre frontend contaminates a reviewer's read of a backend they have
not inspected yet. Not built, on purpose: a live external market feed (the engine has no way to
produce one), new order types, and any change to `mercury-engine`'s public surface beyond what
the blotter decision above needed.

**M15 complete** — **architecture documentation**, the second half of the pair §7 of the design
proposal calls the highest-value items in the project. The sixth-instrument commit
([docs/EXTENSIBILITY.md](EXTENSIBILITY.md)) shipped early, folded into the M6/M7 window rather
than waiting for its named milestone, because nothing about proving the registry open for
extension depended on anything M8 through M14 built. What M15 actually closes is the second
half: a reader had the design proposal's argument for the architecture and the milestone log's
account of what got built, and nothing showing the two agree with the code as it stands today.

[docs/ARCHITECTURE.md](ARCHITECTURE.md) is that document, built the way the rest of this
project's evidence is built - checked, not asserted. Its package-dependency diagram is not a
redrawing of `docs/DESIGN_PROPOSAL.md` §4.1's intended layering; it is `grep -rh "^import
com.mercury\." mercury-engine/src/main/java/com/mercury/<package>/*.java` against every
package, one at a time, so an edge on the page is an edge that actually exists - the same
discipline `LayeringRulesTest` already applies at the package level, applied for the first time
to the documentation describing it.

The two disagree, and the disagreement is worth recording rather than quietly resolving.
§4.1 draws ports and adapters: a central "domain" declares interfaces
(`PricingModel`, `EventBus`, `RiskLimit`) that pricing, risk and matching implement from
outside, so dependencies point *inward* toward the domain. The code that actually shipped never
built that inversion - there is no domain-owned `PricingModel` port with `pricing` as one
adapter among several; `com.mercury.pricing` owns the interface itself, and `portfolio` depends
on it directly, the same ordinary direction `execution` depends on `risk` and `risk` depends on
`portfolio`. Dependencies still run one way and still avoid cycles - `LayeringRulesTest` would
fail the build otherwise - they just never inverted at the package boundary the way §4.1
proposed. The type-keyed registry in `pricing` (§5.1) delivered the actual goal a ports-and-
adapters shape was reaching for - swapping an implementation without touching a caller - by a
more direct route, which is likely why nobody noticed the inversion itself never got built:
the property it was meant to buy showed up regardless.

**M14 complete** — **the golden-master book trades itself.** `DemoScenario.ledger()` called
`PortfolioLedger.buy`/`sell`/`trade` directly through M13: a parallel set of positions that
happened to match what a venue would have produced, never actually produced by one. It now
runs the same eight economic events as real instructions through the same
`ExecutionRouter` every other demo in this codebase uses - a `SimulationClock.Advancing`
moves the book's history forward one trade date at a time, a market maker rests at the exact
price this scenario always used for the five exchange-traded legs, and a dealer is negotiated
against, at zero spread, for the two options and the swap and forward. A `LedgerKeeper`
subscribed to the trade bus assembles the ledger from whatever actually executed, exactly as
`EndToEndDemo`'s book has since M8. The report downstream of this - the one in the README, the
one the golden master pins - is now the output of a simulation rather than a fixture that
looked like one.

That forced an honest correction the parallel-positions version had been hiding.
`OtcNegotiationVenue` does not accept a caller-supplied price; it prices the instrument itself
and applies a spread. The swap and forward are deliberately struck off market - see M4's
`payerSwap()` - so their true price at execution is not zero, and a real desk does not hand
over an off-market instrument for nothing: it charges the value it is walking in with. Booking
them at zero, as the hand-declared version did "because that is what they cost," was quietly
wrong the whole time; the book was never actually given those trades for free, it was simply
never asked to price them. Running them through the venue that does price them turned an
invented free lunch into real cash paid, which is why this book's headline profit moved -
25,097.39 of unrealised profit to 13,795.53, without a single position's market value, Greek,
DV01 or stress-scenario number changing at all. Every number that depends only on the
portfolio's quantities and the market (which is everything except cash and cost basis) is
byte-identical to M13; only what the book actually paid for what it holds changed, because
that is the only thing this milestone touched.

*(A post-ship review found two things worth fixing. First, a real performance regression:
`DemoScenario.ledger()` went from a cheap immutable builder chain (tens of microseconds) to
running actual order-book matching and OTC pricing (measured at roughly 30x slower per call,
42µs to 1.3ms), and two call sites - `Main.printReport()` and `GoldenMasterTest.runScenario()` -
still called it two or three times per invocation out of habit from when that cost nothing.
Both now build the ledger once and derive everything else from it; report output is
byte-identical, confirmed against the golden file. Second, a duplication this milestone made
worse rather than better: `EndToEndDemo` and `TradeLifecycleDemo` already each built an
`OrderBookVenue`/`OtcNegotiationVenue`/`ExecutionRouter` triple by hand, and M14 added a third
copy in `DemoScenario.ledger()` instead of noticing the shape had already been copied once.
Past this codebase's own stated threshold - extract on a confirmed second real user, not
speculatively - the three call sites now share `DemoScenario.venues(...)`, which returns the
two venues and the router together because every caller needs at least two of the three: the
router for routed execution, and the OTC venue directly for `negotiate`/`exposureTo`/`release`,
calls `ExecutionRouter` does not expose. All three demos' output is unchanged, confirmed by
diffing `report`, `walkthrough`, `lifecycle`, `risk` and `montecarlo` before and after.)*

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

*(A post-ship review found that "synchronous is the default, deliberately" had never been
measured against the alternative it was a default relative to. `docs/BENCHMARKS.md` §7 now
does: against a subscriber costing a few milliseconds, `AsynchronousEventBus` is roughly
17,000× faster on the publisher's own latency, because its cost is a queue insertion regardless
of the subscriber, while the synchronous bus's cost *is* the subscriber's. Against a subscriber
as cheap as `LedgerKeeper`'s, synchronous wins instead - decoupling costs more than the work
being deferred. The same run also turned `KNOWN_GAPS.md`'s "nothing produces faster than the
dispatcher consumes" into a reproducible failure: a benchmark publishing at that rate overflowed
the unbounded queue and timed out draining it on close. The claim that no *production* caller
does this yet still holds; the claim that it couldn't be shown did not.)*

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
