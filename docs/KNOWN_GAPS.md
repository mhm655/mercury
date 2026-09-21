# Known gaps

Deliberate omissions and deferred fixes, recorded so they are choices rather than
oversights. Anything a reviewer might reasonably expect and not find should be here with a
reason and a milestone.

Found during the pre-M4 audit unless noted otherwise.

---

## Fixed during M17

### H-1 · No asynchronous submission path · fixed

`ExecutionVenue.execute` returns the trades it produced, so a caller always waited. That is
what made `BookConcurrency.THREAD_PER_BOOK` slower than the inline default rather than faster
(`docs/BENCHMARKS.md` §6): a park-and-wake handoff per order, around matching that was quicker
than the handoff. The LMAX design it follows assumes fire-and-forget submission, a batching
writer, executions arriving as events, and a spinning rather than parking queue. M13 built the
events half; this milestone builds the submission half - but narrower than "change
`ExecutionVenue`'s contract for every caller," which is what this entry originally said the
fix would cost.

**Additive, not a redesign.** The measured problem is specific to `OrderBookVenue` and
`SingleWriterBookLane` - `OtcNegotiationVenue` has no threading model at all and was never
part of it. `BookLane` gained a second door, `submit(Runnable)`, alongside the existing
`run(Supplier<T>)`: `SingleWriterBookLane.submit` queues a plain `Runnable` (no `FutureTask`,
no `.get()`) and returns as soon as it is queued; `LockedBookLane.submit` runs inline under the
same lock `run` uses, since there is no writer thread to hand off to on `INLINE`.
`OrderBookVenue.submit(OrderBookInstruction, SimulationClock)` is new and additive - `execute`
and every one of its callers (`ExecutionRouter`, every demo, every `execute`-based test) are
unchanged. A submitted order's trades reach a caller only through whatever `EventBus` the
venue was built with, never as a return value.

**Measured, not asserted.** `docs/BENCHMARKS.md` §6's own M17 section: removing the handoff
roughly doubles throughput on one book and clears the twelve-book figure too, consistent with
the diagnosis above. It also opens a new, honestly recorded gap - see "Execution |
`OrderBookVenue.submit` has no back-pressure" below.

---

## Fixed during M8

### G-1 · Order ids are reusable after an order fills · fixed

`OrderBook.submit` rejected an id that was **currently resting**, but once an order filled,
its id was free again:

```java
submit(X, sell 100)  // fills completely
submit(X, sell 50)   // accepted - id is no longer resting
```

`Fill` records therefore referenced ids that were not unique over time, so a reconstructed
audit trail could be ambiguous about which order a given execution belonged to.

**Why the fix is not inside `OrderBook`.** The honest fix was never "remember every id
forever" inside the book - that is an unbounded set in a long-running book, trading one bug
for a memory leak. Real venues assign their own unique order and execution identifiers
rather than trusting client-supplied ones, so that is where the fix lives: `OrderBookVenue`
(`com.mercury.execution`) is now the sole sanctioned way to submit an order. An
`OrderBookInstruction` never carries an id at all; the venue mints one from its own
`OrderIdGenerator`, a monotonic counter that cannot repeat a value it has already handed
out. `OrderBook.submit` itself is unchanged and, driven directly, would still accept a
reused client-supplied id - the fix is architectural (nothing outside the venue is a
sanctioned caller), not a patch to the book's own check.

**`TradeId`s needed the same care, for a different reason.** `TradeIdGenerator` is a single
instance shared between `OrderBookVenue` and `OtcNegotiationVenue`, not one per venue - two
independent counters would each start at `TRD-1` and collide the moment a CLOB trade and an
OTC trade land in the same `PortfolioLedger`. Caught in review before it shipped.

### G-2 · No self-trade prevention · fixed

Two orders from the same participant used to cross happily:

```java
submit(A, sell 100 @ 100)
submit(B, buy  100 @ 100)   // fills against A
```

Real venues are required to prevent this — self-matching is wash trading, and regulators
treat it as market manipulation whether or not it was intended.

**The fix.** `Order` gained a required `owner` (`CounterpartyId`, reused from the OTC side
rather than inventing a separate trader identity). `OrderBook.match` now refuses to fill a
resting order against an incoming order with the same owner: that pairing is skipped and
matching continues against the rest of the book, so most of an order is unaffected by one
self-owned order sitting in its path - a real, deliberately-chosen policy (pairwise
self-trade prevention), not a blanket cancellation of the whole incoming order.

**Silent skip was rejected as the design.** A first pass left the blocked resting order
exactly where it was and moved on, with no signal anywhere that anything had happened. A
real venue's self-trade prevention produces an auditable event, because a regulator expects
to see that STP fired rather than infer it from an order that filled less than expected for
no visible reason. `OrderBook.match` now records the fact - instrument, both order ids, the
blocked quantity - and `MatchResult` carries it alongside `fills`, at the layer where it is
actually discovered rather than bolted onto the venue interface above it.

**A crossed book, found in a later review.** Skip-and-continue left one case open: the
aggressor's unfilled remainder rested at its limit *on top of* the same-owner order it had
been blocked from, so a sell at 100 followed by the same owner's buy at 100 left bid 100 /
ask 100 - the one state a matching engine must never be in. `bookIsNeverCrossed` could not
see it, because its generator gives each side a fixed owner and so never blocks a pairing.
The remainder is now cancelled instead of rested whenever resting it would cross (the
"cancel newest" mode venues commonly default to); fills against other owners stand, and the
shared-owner property now asserts the book is never crossed after every submission.

---

## Fixed during a post-M8 review

### F-1 · `OtcNegotiationVenue`'s spread silently did nothing on a zero-priced instrument · fixed

The spread was applied as `mid × (1 ± spread)` - the right convention for anything quoted
as a clean per-unit price (a stock, a bond, an option premium), and the wrong one for a
net-present-value instrument like a swap or a forward. A swap negotiated at or near
inception prices at or near **zero**, and a percentage of a number near zero is a number
near zero: `TradeLifecycleDemo`'s own OTC negotiation happened to work only because the
demo's swap priced at 11,109.21, comfortably away from zero. The same code, run against a
swap struck at par, would have booked a trade that claimed a spread and applied none -
silently, with no error, no warning, nothing to distinguish it from a genuinely zero-cost
trade.

**How it was found.** A post-ship debugging pass, not a test - the shipped test suite
happened to only ever price this venue against instruments comfortably away from zero.

**Why a percentage-of-mid convention is the wrong shape here, not just imprecise.** A real
desk quotes a swap spread on the *rate*, not as a fraction of its NPV - repricing the
instrument with a bumped rate input, which Mercury's `PricingModel` interface does not
generically support today (see the deliberate scope limit below). Deriving a spread's
monetary size from a number that can legitimately be zero is broken by construction, for
any instrument whose priced value is a net PV rather than a market-quoted price - not a
rounding edge case to patch around.

**The fix.** `OtcNegotiationVenue.execute` now checks whether a nonzero requested spread
actually changed the consideration, and throws `SpreadHadNoEffectException` if it did not,
rather than booking a trade that silently applied none - the same "loud beats plausible"
rule `MarketDataSnapshot` and `SwapModel` already follow for missing data (see C-1 and
E-2). A zero spread on a zero-priced instrument is unaffected and still books normally: a
genuinely zero-cost trade at inception is legitimate, and the exception is about a
requested spread having no effect, not about zero consideration itself.

**What is still deferred.** A real rate-based spread convention for NPV instruments is not
implemented - see "A percentage-of-mid spread on NPV instruments" below. This fix turns the
silent wrong answer into a loud, correct refusal; it does not add the machinery a correct
answer would need.

---

## Deliberate scope limits

These are not defects and are not scheduled. They are named so their absence reads as a
decision.

| Area | Not modelled | Why |
|---|---|---|
| Bonds | Amortisation, call/put schedules, floating-rate notes, inflation linkage | The vanilla bullet bond already answers the design question (how a cashflow-bearing instrument exposes itself to a generic pricer). The rest is domain surface without architectural gain. |
| Swaps | Historical index fixings | A floating period already under way fixed its rate at the start, and Mercury stores no past fixings — so a seasoned swap is **refused**, not approximated (see E-2). An index fixing is market data, and this engine treats missing market data as an error rather than a zero. A fixing store is bookkeeping rather than a design question; M8 built the trade lifecycle but not this, so it stays deferred and unscheduled rather than claiming a milestone it did not land in. |
| Swaps | Cross-currency, basis (float-float), amortising notionals, principal exchange | All are different *compositions* of the existing legs rather than new structures — which is the point of composing legs instead of subclassing. |
| Curves | Dual-curve / OIS discounting | Single-curve is the pre-2008 convention. Real desks discount OIS and project on a separate index curve; the basis between them is itself a quoted market. We are single-curve and say so. |
| Curves | Futures quotes, and their convexity adjustment | The bootstrapper takes deposits and par swaps, which cover the whole curve. Futures are the third common input and need a convexity adjustment — a genuinely subtle correction — for no new design question. |
| Curves | Spline interpolation | Deliberate, not missing. A spline removes the kink at each pillar and can produce negative forward rates between them, which is an arbitrage the input quotes never contained. Flat-forward keeps the kink and stays arbitrage-free. See ADR 0006. |
| Curves | Key-rate risk | The shock family can express a one-pillar bump, and nothing asks for one yet. DV01 is a parallel shift; key-rate duration is the same mechanism with a narrower selector, and arrives when the risk engine needs it. |
| Order types | Fill-or-kill, good-till-date, stop, iceberg | Each adds a branch in the matching loop and no new insight. Limit, market and IOC cover price-time priority, resting, partial fills and cancellation. |
| Order book | Tick-indexed price array | O(1) for everything and what a real exchange uses, but it assumes a bounded tick grid the simulation does not fix. See ADR 0005. |
| FX | Triangulation through a vehicle currency | `fxRate(from, to)` consults the pair and its inverse, nothing else, so GBP to USD fails even when GBP/EUR and EUR/USD are both present. A cross rate needs a stated vehicle currency and a rule for which crosses are legal; inferring one silently would value a book against a rate nobody quoted. Fails loudly today. |
| Portfolio | A trade that carries a position through zero | Selling fifteen when long ten is two trades: closing ten at the old cost basis and opening a short five at today's price. `PositionLots` refuses it rather than inventing where the boundary falls. Book the two legs separately. |
| Portfolio | Realised P&L converted at today's rate, not the trade's | A foreign gain is reported at the current FX rate, so it includes the currency move since the position was opened — which is what the holder actually made. Splitting price return from currency return needs the rate on each trade date, which is a fixing store; still deferred and unscheduled after M8. |
| Currencies | Only 7 ISO codes | `Currency` is an enum for exhaustive `switch` and cheap `EnumMap` keys. Adding one is a single line. See ADR 0002. |
| Equities | Dividends | Would change option pricing (the dividend yield term in Black-Scholes). Currently a zero-dividend assumption, to be stated explicitly when pricing lands at M6. |
| Risk limits | Exposure measured beyond gross notional by counterparty | M9's `CounterpartyExposureLimit` checks the running sum of absolute trade considerations against a counterparty, released via `OtcNegotiationVenue.release` once a trade settles (see below) — not a mark-to-market or potential-future-exposure figure, so a trade still outstanding is counted at its full traded consideration rather than its current replacement cost. A stated, narrower choice than `ExposureCalculator`'s full gross/net/by-currency/by-asset-class design in `docs/DESIGN_PROPOSAL.md` §5.5, built only as far as a credit check needs. Only OTC trades are checked; a CLOB fill has no named counterparty to check against (see `TradabilityProfile`). |
| Trade lifecycle | Settlement scheduling | `Trade` carries a `settlementDate` and supports the `SETTLED` state, but nothing moves a trade there automatically on clock advancement (`docs/DESIGN_PROPOSAL.md` A2.7). Driving a trade to `SETTLED` is a caller's explicit action until a real scheduler exists; adding one now, with no consumer, would be exactly the speculative machinery the project's restraint principle (A2.9) argues against. |
| Risk limits | Exposure is per-venue-instance, not global | `OtcNegotiationVenue.exposureByCounterparty` lives on the venue instance. Correct as long as exactly one `OtcNegotiationVenue` ever trades against a given counterparty, which is true of every wiring in this codebase today (`ExecutionRouter` holds exactly one). If a second instance were ever introduced - sharded by instrument, a failover replica - each would track exposure independently and the limit would silently under-count. Not fixed now because doing so (a shared `ExposureLedger` injected into every venue, or a single venue enforced by construction) would be built against a scenario nothing in the codebase creates yet - exactly the kind of speculative machinery A2.9 argues against. Worth revisiting the moment a second venue instance actually exists. |
| OTC negotiation | A separate, expiring quote step | `OtcNegotiationVenue` prices and executes in one call. A real RFQ workflow quotes a price that can expire before it is accepted. Collapsing the two is a stated simplification, in the same spirit as the project's existing single-curve and vanilla-swap simplifications — not a gap that was missed. |
| OTC negotiation | A percentage-of-mid spread on NPV instruments | `OtcNegotiationVenue` applies its spread as a fraction of the priced mid, which is the right convention for a clean per-unit price and the wrong one for a swap or forward's net present value — see F-1. A correct version needs `PricingModel` to reprice at a shocked rate input, which the interface does not support generically today; adding it for one caller would be speculative. For now, F-1 makes the venue refuse a spread that would have had no effect rather than pretend one was applied. |
| Risk engine | No `AnalyticGreeks` capability interface or runtime "prefer analytic" dispatch | `docs/DESIGN_PROPOSAL.md` §5.3 describes a pricer optionally implementing `AnalyticGreeks`, with the risk engine preferring it over bump-and-revalue when available. M10 does not build that interface - `BlackScholesModel` instead exposes static `delta`/`gamma`/`vega` formulas, exactly the shape its own `price(...)` javadoc had already forward-declared ("so the analytic Greeks at M10 can share exactly these conventions"), used only for cross-validation tests. `SensitivityCalculator` always uses bump-and-revalue in production. Building the runtime-dispatch interface now would need per-position analytic-vs-numeric branching inside what is currently a uniform portfolio-level shock-and-revalue, for an optimisation with no measured performance problem behind it - exactly the speculative machinery A2.9 argues against. Revisit if a real performance case for it ever shows up. |
| Risk engine | No historical-data loader | `HistoricalVaRCalculator` takes historical scenarios as caller-supplied `MarketShock`s, not loaded from any feed. This engine has no live or historical market-data source anywhere - every `MarketDataSnapshot` in Mercury is already caller-constructed - so a loader would be new infrastructure this class has no need to own. |
| Scenarios | No scenario library shipped by the engine | `Scenario` (M11) is a general factory/Composite primitive in `com.mercury.marketdata`; Market Crash, Rate Shock and Currency Crisis are defined in `DemoScenario`, not the engine. Same reasoning `RiskFactors` already states: which scenarios a book is measured against is a reporting decision, and inferring or hardcoding a "standard" set into the engine would make that decision for every caller rather than leave it to whoever is doing the reporting. |
| Monte Carlo | One risk factor at a time, no correlation | `MonteCarloVaRCalculator` (M12) simulates a single underlying's spot in isolation, the same per-factor shape `SensitivityCalculator.delta`/`gamma`/`vega`/`dv01` already have. A real multi-factor portfolio VaR needs correlated draws across every risk factor at once - a covariance matrix, a Cholesky decomposition, a joint distribution - which nothing here builds, since no caller needs it yet and getting correlation estimation right is a substantial piece of work on its own. |
| Monte Carlo | VaR stops scaling at 4 workers | Parallel Monte Carlo landed at M13 and option pricing reaches 5.9× on 12 workers, but VaR flattens at 2.6× - each path allocates ~5.7 KB (a shocked snapshot, valuation lines, `BigDecimal`-backed `Money` per position) and the allocation rate hits a ceiling of ~4.6 GB/s exactly where throughput stops (`docs/BENCHMARKS.md` §5). The fixes would be a `double`-domain revaluation path for risk - ADR 0001 already separates ledger from model arithmetic - or reusing shocked snapshots per block. Both trade clarity for speed, and nothing needs VaR faster than 60 ms yet. |
| Execution | `OrderBookVenue.submit` has no back-pressure | The M17 fix for the gap above (see "Fixed during M17") is itself unbounded: `SingleWriterBookLane`'s command queue has no limit, the same choice `AsynchronousEventBus` already made below and for the same reason - bounding it means blocking the submitter, which is what `submit` exists not to impose, or dropping work, which needs a stated policy this class does not have. `docs/BENCHMARKS.md` §6's own M17 run reproduced the failure mode directly: a twelve-thread JMH loop queuing faster than the writer could drain ran the JVM out of heap. No production caller in this codebase submits anywhere near that rate today. |
| Execution | The command queue is not a command *log* | §5.6 a) says deterministic replay "falls out for free" from feeding each book from a queue. It does not fall out yet: `SingleWriterBookLane`'s queue is in memory and each command is discarded once it has run, so nothing can be replayed from it. Recording commands durably, and replaying them into a fresh book, is real work (a serialisation format for instructions, and a decision about what a replay does to the event bus) that no consumer has asked for. The structure the replay would need is in place; the recording is not. |
| Event bus | No production subscriber runs asynchronously | `AsynchronousEventBus` is built, tested and documented, but every wiring in this codebase uses `SynchronousEventBus`, because a deterministic demo and a golden-master test want their subscribers finished before the next line runs. Listed in the README's dead-weight table for the same reason `RiskLimit.and()` is: real machinery with tests, waiting for the live simulation that will use it. `docs/BENCHMARKS.md` §7 measures the choice directly rather than leaving it asserted: against a subscriber costing a few milliseconds, decoupling the publisher is worth roughly 17,000× on `publish`'s own latency, which is the number this table used to claim without showing. |
| Event bus | Unbounded queue, no back-pressure | `AsynchronousEventBus` queues without limit, because bounding it means blocking the publisher - the thing an async bus exists not to do - or dropping events, which needs a stated policy. *(This entry originally said "nothing in Mercury produces faster than the dispatcher consumes, so a bound would be sized against a guess." `docs/BENCHMARKS.md` §7's own measurement run is a counterexample: a publisher at ~0.24 µs against a ~4 ms subscriber enqueued faster than the dispatcher could drain, and `close()` timed out mid-drain inside a two-second JMH iteration - reproducible, not hypothetical, once something actually publishes that fast. It took a synthetic benchmark to produce that producer, not a real caller, so the underlying claim - no *production* wiring does this yet - still holds; only "would be sized against a guess" was wrong, since the failure mode is now on record with a number attached.)* A real deployment needs the bound and the policy together. |
| Event bus | A subscriber failing on the async bus is counted, not reported | The default `AsynchronousEventBus` counts failures and otherwise swallows them; the engine has no logger and no framework to get one from (`LayeringRulesTest`), so the default cannot be "log it". A caller that cares passes a handler. Counting is the floor, not a good production answer. |
| Access control | No notion of who is calling | The engine has no caller identity, so it cannot decide *who* may negotiate, release exposure or book a trade - that belongs to the API layer planned for phase 2, which knows the caller. What the engine can check without one, it does: `OtcNegotiationVenue.release` accepts only a continuation of a trade it executed itself, the venues are safe under concurrent callers, and error messages no longer list counterparties, instruments or market data. The phase-2 layer still has to authenticate callers, authorise each operation, and map `MercuryException`s to responses rather than echoing messages. |
| Risk engine | No confidence interval on Expected Shortfall | `HistoricalVaRCalculator.valueAtRiskConfidenceInterval` brackets VaR - one order statistic, whose rank uncertainty has a well-known large-sample (Binomial/Normal) approximation. Expected Shortfall averages a whole tail of variable size, not one order statistic, so the same rank-uncertainty trick does not carry over directly - a correct ES confidence interval is a genuinely different, harder statistical problem (its own asymptotic theory, or a bootstrap), not a small extension of the VaR one. Deferred rather than approximated incorrectly. |

---

## Fixed during the M6 audit

### E-1 · `parRate` reported the wrong sign below zero · fixed

`SwapModel.parRate` normalised the floating leg with `Math.abs`, to make the answer
independent of which way round the swap is traded. That looks equivalent to normalising by
direction and is not.

A floating leg's present value carries **two** signs: one from whether the holder receives it,
and one from the curve. Above zero they agree, so `abs` happened to give the right answer.
Below zero they do not — a received floating leg on a negative curve is worth less than
nothing — and `abs` kept the wrong one:

| | |
|---|---:|
| Market 5Y par swap quote | **−0.30%** |
| `parRate()` reported | **+0.30%** |
| PV of a swap struck at the reported rate | **−302,828.70** on 10,000,000 notional |
| PV at the true par rate | 0.04 |

Three per cent of notional, out of a number that looks exactly like a rate.

**Why nothing caught it.** Every swap test used a positive-rate curve.
`CurveBootstrapperTest` did exercise a negative-rate market — but only against the *curve*,
never against the swap model built on top of it. Coverage of a component is not coverage of
its callers.

**Why it matters more than an ordinary sign bug.** The engine explicitly advertises this
market condition. `MarketDataKey.ZeroRate` says negative rates are permitted because
"rejecting them would encode a market condition as a validation rule". A capability the
documentation claims and one method silently breaks is worse than one that was never offered.

**The fix.** Normalise by `PayReceive`, not by magnitude. The direction sign is removed
deliberately; the curve's sign is left alone.

### E-2 · A seasoned swap was priced with a rate that did not match its accrual · fixed

The first version handled a floating period already under way by clamping: project the rate
from the valuation date to the period end, then accrue that rate over the period. Those are
two different lengths of time.

On a swap six weeks into a three-month period, it charged a **48-day rate for 92 days of
accrual**:

```
period            [2024-05-15 -> 2024-08-15]   tau = 0.2556 (ACT/360)
rate projected    2024-06-28 -> 2024-08-15     covering only 0.1333
coupon accrued    over the full 0.2556
```

The error is small on a flat front end and grows with its slope — invisible either way, and
worth tens of thousands on a ten-million notional.

**Why it now refuses rather than approximating better.** Every alternative that returns a
number invents one. Accruing over the remaining stub alone silently discards interest already
earned; assuming the index fixed at today's equivalent-tenor rate makes up a figure that is a
matter of public record. Both produce a valuation indistinguishable from a correct one.

An index fixing is market data. The snapshot does not hold it. The engine's existing rule for
that case is `MissingMarketDataException` — loud beats plausible — and `MissingFixingException`
now follows it. Swaps valued on or before their start date, which is every swap at the moment
it is traded, are unaffected.

---

## Fixed during M6

### D-1 · The stress scenario had been missing its rates leg since M4 · fixed

`DESIGN_PROPOSAL.md` §5.3 specifies the market-crash scenario as equities −30% **and**
volatility +50% **and** FX −10% **and rates +150bp**. The implementation had three of the four:

```java
MarketShock crash = MarketShock.scaleAllSpots(0.70)
        .and(MarketShock.scaleAllVolatilities(1.50))
        .and(MarketShock.scaleAllFxRates(0.90));   // and nothing for rates
```

**Why it went unnoticed.** Until M5 the book was equities and options, which have almost no
rate sensitivity, so the missing leg moved the answer by a few dollars. A bond, an FX forward
and a swap made it expensive: the headline number went from −104,888.50 to −56,815.17 once the
rates leg was added — the scenario had been overstating the loss by 46%.

**Why this is the worst kind of gap.** A stress test that silently omits a factor is more
dangerous than one that is absent, because it produces a number people act on. Nothing failed,
nothing warned, and the output looked exactly as authoritative as it does now.

**The fix.** The leg, and a golden-master test asserting the scenario names every factor it
shocks — so the label and the shock cannot drift apart again.

The number reconciles: rates alone contribute +47,661.76, the other three −104,888.50, and the
interaction between them +411.57 (the options' rho changes once spot has fallen 30%). First
order, DV01 × 150bp would predict +49,457.59; the −1,795.83 difference is convexity over a
move that large, which is about the right size for a book of duration 4.4.

---

## Fixed during the M5 audit

### C-1 · `withShock` could build a market the builder would refuse · fixed

`MarketDataSnapshot.Builder` rejected a non-positive spot price. `withShock` — the only other
way to create a snapshot — validated nothing, so a shock could impose one anyway:

```
builder rejects spot 0.0:  Spot price for AAPL must be positive, but was 0.0
withShock produced:        spot(AAPL) = -195.5
```

The negative spot then reached Black-Scholes and failed four layers down with
`N(x) is undefined for NaN`, naming neither the instrument, nor the observation, nor the
shock. Worse, `ValuationResult` blamed the model — its message read *"This indicates a broken
calculation, not an extreme market"* — when the market data was the thing at fault.

**How it was found.** Not by a test. By asking what else could reach a snapshot, after
noticing that the validation lived on the builder rather than on the data.

**The fix.** The rule moved onto `MarketDataKey`: each sealed case now says what values it
can legally take, and both the builder and `withShock` ask the key. The invariant ends up on
the type that owns it, the builder stops knowing rules for data it does not own, and there is
no third construction path left to forget — `MarketDataSnapshotTest.oneRulePerKeyNotPerPath`
asserts both routes reject through the same rule.

### C-2 · The engine measured risk it did not report · fixed

M5 added a bond and an FX forward to the demo portfolio. The risk section still showed equity
delta and nothing else, while the book carried:

| Risk factor | Exposure | Reported before |
|---|---:|---|
| USD rates, per bp | −69.92 | no |
| EUR rates, per bp | −51.80 | no |
| EUR/USD, per unit | 484,295.75 | no |

The exposure was real and the stress line moved with it, which is what made it easy to miss:
nothing was wrong, something was merely absent. An engine that computes risk it does not
print is indistinguishable, to a reader, from one that cannot compute it.

**The fix.** `dv01` and `fxDelta` on `SensitivityCalculator`, both built on the existing
shock-and-revalue mechanism — three short methods, no new machinery, which is the argument
for `MarketShock` making its own case. `MarketShock.scaleFxRate` was added alongside them:
the shock family had a single-key form for spot, volatility and rates but only
`scaleAllFxRates` for FX, so one currency could not be moved on its own.

Worth noting what fell out for free: the USD DV01 of −69.92 includes the options' rho. The
bond contributes −112, the forward's USD leg +52, and the two option legs −10 between them.
There is no rho formula anywhere in the codebase.

### C-3 · A bond's dirty price was printed as though it were clean · fixed

The report showed `998.9954` under the same **UNIT VALUE** heading as a share price and an
option premium. For a bond that number is the present value of everything still owed — the
*dirty* price — and bond markets quote clean. `Bond.accruedInterest` had existed since M2,
with four tests and no production caller.

**The fix.** An `AccruingInterest` capability interface, and an accrued-interest block in the
report:

```
ACCRUED INTEREST  (unit values above are dirty: clean + accrued)
  INSTRUMENT                CLEAN        ACCRUED          DIRTY
  CORP-5Y                997.6254         1.3700       998.9954
```

The renderer filters on the capability rather than testing `instanceof Bond`, which would
have been the first branch of the chain this design exists to avoid. One implementor today;
the swap fixed leg is the second at M6.

---

## Fixed during the pre-M4 audit

Recorded because how a defect was found is often more useful than the defect.

### A-1 · `Bond.maturityDate()` contradicted its own cashflows · fixed

A bond maturing on Saturday 15 May 2027 reported that date as its maturity while paying on
Monday the 17th. On the Saturday it claimed to have matured **while still owing
1,050,277.78 USD**. Any caller filtering out matured positions would have silently dropped a
position that still owed money — no exception, no warning, a wrong number.

`InterestRateSwap` returned the *adjusted* date, so two implementations of one interface
meant different things.

**Root cause, and the general lesson.** `Bond` and `Schedule` were each individually correct
and individually well tested. They disagreed *with each other*, and no test looked at the
seam. Unit tests verify components; integration bugs live in the gaps between components
that are each fine alone.

**Fix.** `Maturing.maturityDate()` is now defined as the final *payment* date — adjusted —
with the invariant that `cashflows(maturityDate())` must be empty. `Bond` exposes
`contractualMaturityDate()` separately for the unadjusted term-sheet date.
`MaturityConsistencyTest` enforces the invariant across every instrument, using weekend
maturities because the defect is invisible on a business day.

### A-2 · `OrderNode.sequence` was dead code claiming to do something · fixed

A field written on every insert and never read. Its javadoc said it "establishes time
priority within a price level"; priority is actually structural — append to tail, match from
head. The false claim was repeated in `OrderBook`'s class documentation and in ADR 0005.

Removed. The remaining counter is named `fillSequence` and documented as identifying fills
only. A comment asserting something the code does not do costs more than the field did: a
reviewer who catches one stops trusting the rest.

### A-3 · `FxForward` accepted a notional that rounded to nothing · fixed

A 0.001 USD notional against JPY (no minor units) produced a live instrument whose legs both
settled zero. Validation checked the raw `BigDecimal` rather than the `Money` that results
from it. Now validates the settling amounts.

### A-4 · The no-clock ArchUnit rule was only partly enforced · fixed

It caught `LocalDate.now()`, `LocalDateTime.now()`, `Instant.now()` and `System.currentTimeMillis()`
— leaving `ZonedDateTime.now()`, `LocalTime.now()`, `new Date()` and `Calendar.getInstance()`
open, while the documentation claimed no production class reads a clock. Widened to sixteen
entry points.

A partially enforced rule is worse than an absent one, because it is believed.

---

## Fixed during the second pre-M4 audit

The second pass found **no correctness bugs** — the order book's invariants held under 400
runs at 264 resting orders and 21 price levels, roughly twenty times the depth the tests had
been reaching. The findings were about quality, and one about the tests themselves.

### B-1 · The property tests barely exercised the order book · fixed

Measured coverage of the original generator:

| | Reached |
|---|---|
| Max resting orders in any run | 14 |
| Max price levels touched | 9 of 22 |
| Submissions into a completely empty book | 26% |

Both sides drew prices from a single 95–105 range, so nearly every order crossed on arrival
and the book never accumulated. Eight properties over a thousand sequences were, in effect,
testing a book with fourteen orders in it — while the states the structure exists for (deep
queues, interior cancellation, level exhaustion mid-sweep) went untouched.

**Fixed** by separating the bid and ask ranges with roughly one order in eight crossing.
Now reaches 50+ resting orders in 36% of runs and never falls below 10.

**And guarded.** `theGeneratorActuallyBuildsDepth` asserts via jqwik statistics that at least
a fifth of sequences build a book of 50+ orders. That guard earned its place immediately: the
first version of the rewrite still only reached depth 9% of the time, and without the check
the improvement would have looked complete. A property suite's strength is the state space it
reaches, not the number of cases it runs.

### B-2 · `PayReceive.sign()` — dead, and contradicted by its own javadoc · fixed

Documented as letting cashflows be "flipped by multiplication rather than by branching", while
both `FixedRateLeg` and `FloatingRateLeg` branched on the constant with a ternary. Never
called. Removed.

### B-3 · Fifteen further public methods with no caller · fixed

Deleted, except three that were legitimate untested configuration —
`businessDayConvention(..)` on both builders and `spread(..)` on the swap builder, now covered
by `BuilderConfigurationTest`. Those were public, documented options through which the
non-default rolling and spread paths were never executed.

**The pattern, and the mechanical fix.** Three findings across two audits were the same
mistake: write the javadoc describing the intended design, implement something slightly
different, never reconcile. For a project whose documentation *is* the product, a comment
asserting behaviour the code lacks costs more than the dead code — a reader who catches one
stops trusting the rest. Vigilance had failed three times, so `NoOrphanedApiTest` now fails
the build when a public method has no caller anywhere.

### B-4 · Instruments disagreed about what equality meant · fixed

`Stock`, `FxForward` and `EuropeanOption` were records with component-wise value equality;
`Bond` and `InterestRateSwap` compared on id. Two entirely different bonds sharing an id
compared equal and collapsed in a `HashSet`; conversely, under value semantics an amended
instrument would stop matching the position that referenced it.

Settled on **entity equality** — same `InstrumentId` means the same instrument — documented on
`FinancialInstrument` and enforced by `InstrumentIdentityTest`. Market data and positions are
both keyed by id, so identity is the domain's own answer.

---

## Not a gap, but worth stating

`MODIFIED_FOLLOWING` will escape its month if an entire calendar month is closed — it rolls
backwards into the previous one, violating the convention's only purpose. This requires a
calendar with ~30 consecutive closed days, which no real market has. Left unguarded rather
than adding a check for an input that cannot occur; noted so that a reader who spots it knows
it was considered.
