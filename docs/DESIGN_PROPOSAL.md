# MERCURY — Design Proposal (Pre-Implementation)

**Status:** Approved and under construction. M1–M2 delivered; see §11 for progress.
**Target:** Java 21+, framework-independent core, incremental delivery.

This is a living document. Where implementation has proved part of it wrong, the
correction is marked inline rather than silently edited away — the reasoning that changed
is usually more interesting than the conclusion.

This document covers: requirement analysis, ambiguities, domain model, architecture,
key abstractions, justified patterns, anti-patterns to avoid, and a roadmap.

---

## 1. What this project actually has to prove

The stated goal is not "a trading system." It is: *an experienced Java engineer opens
this repo and concludes, within ten minutes, that the author can design software.*

That reframes every decision. Four consequences:

1. **Legibility beats feature count.** A reviewer reads maybe 15 files. Those 15 files
   must be the interesting ones, and they must be findable.
2. **Every abstraction must pay rent.** An unjustified `AbstractFactoryProvider` is
   worse than no abstraction, because it signals cargo-culting. Restraint is a
   demonstrable skill and we will demonstrate it explicitly (see §8).
3. **Claims must be verifiable.** "High performance" is noise. "Cancel is O(1) because
   of an intrusive doubly-linked list per price level, here is the JMH run and the
   hardware" is signal.
4. **The concept is not novel — so rigor is the entire differentiator.** "Build a trading
   engine" is a common portfolio idea; there are hundreds on GitHub. Nothing about the
   *premise* will distinguish this repo. What distinguishes it is execution quality and,
   above all, evidence.

### 1.1 Evidence-first, not technology-first

Most portfolio READMEs open with a list of technologies. This one opens with three
concrete, clickable artifacts:

1. **The benchmark table** — real numbers, stated hardware, stated methodology (§5.2, §5.6).
2. **The extensibility-proof commit** — a link to a single diff adding a sixth instrument
   that modifies zero existing files (§7.1).
3. **The reproducibility guarantee** — a golden-master test proving the whole simulation
   is deterministic end-to-end (§7.2).

Each converts a claim a reviewer would otherwise have to take on faith into something
they can verify in under a minute. This principle governs the whole project: **prefer
building the proof over writing the claim.**

### 1.2 Domain credibility

Murex builds capital-markets software. A reviewer there will notice if we conflate
exchange-traded and OTC instruments, price in `double`, ignore multi-currency, or price
a swap without constructing a discount curve. Getting the *domain* right is as much of a
differentiator as getting the Java right — and the unglamorous details (day counts,
business-day rolling, holiday calendars) are precisely what separates "has seen a
trading system" from "read a Black-Scholes tutorial."

---

## 2. Ambiguities in the requirements (decisions needed)

These are genuine forks. I state a recommendation for each; flag any you disagree with.

### A2.1 — Order book vs. OTC instruments  (most important)

The brief asks for a central limit order book *and* for FX forwards, IR swaps and
options. In reality those are **OTC** instruments: they are bilaterally negotiated with
a counterparty, not matched on a public book. Only equities (and some bonds) trade on a
CLOB.

Running swaps through a price-time-priority matching engine would be a domain error a
Murex engineer would spot instantly.

**Decision:** model two distinct *execution venues* behind one `ExecutionVenue` interface:

- `OrderBookVenue` — price-time priority CLOB, for exchange-traded instruments (stocks, listed bonds).
- `OtcNegotiationVenue` — request-for-quote: price the instrument, apply a spread, book a bilateral trade against a `Counterparty`.

Both produce the same `Trade` and feed the same lifecycle. This *adds* architecture value
(a real polymorphic boundary, a real domain distinction) rather than costing us. An
instrument is routed by a `TradabilityProfile` it carries, not by `instanceof`.

### A2.2 — Numeric representation: `BigDecimal` or `double`?

Neither, universally. This is the classic mixed-domain problem.

**Decision — a documented split:**

- **`BigDecimal`** for anything that is a *ledger fact*: cash balances, trade
  consideration, realized P&L, settlement amounts. These must round-trip exactly and
  reconcile to the cent.
- **`double`** for anything that is a *model output*: Black-Scholes prices, Greeks,
  discount factors, Monte Carlo paths. These are approximations to 4–6 significant
  figures; `BigDecimal` here would be 10–50× slower and *falsely* precise.
- A single explicit conversion boundary (`ModelValue -> Money`) with a stated rounding
  policy (`RoundingMode.HALF_EVEN`, currency-specific minor units).

Documenting *why* the split exists is a stronger signal than picking either one purely.

### A2.3 — Multi-currency and reporting currency

FX forwards and a "USD/JPY +20%" scenario imply multi-currency portfolios. So:

- `Money` is always `(BigDecimal amount, Currency currency)`; arithmetic across
  mismatched currencies throws rather than silently coercing.
- A `Portfolio` has a **reporting currency**. Valuation requires an FX conversion
  service reading rates from the market snapshot.
- `CashAccount` is per-currency (`Map<Currency, Money>`), not a single balance.

**Consequence:** FX exposure falls out naturally instead of being bolted on.

### A2.4 — Valuation time and reproducibility

Pricing must be a **pure function** of `(instrument, marketSnapshot, valuationDate)`.
No pricer may read a clock or mutable global state. This is not purism — it is what
makes stress testing, bump-and-revalue Greeks, Monte Carlo, deterministic tests, and the
golden-master test (§7.2) possible *with one mechanism* (see §5.3).

Requires an injected `SimulationClock` rather than `LocalDate.now()` anywhere.

### A2.5 — Realized P&L cost-basis method

FIFO and weighted-average give different realized P&L for the same trades. The brief
does not say which.

**Decision:** a `CostBasisMethod` strategy, `AVERAGE_COST` as the default, `FIFO`
implemented to prove the seam is real. Documented, tested against a worked example.

### A2.6 — Pre-trade risk needs a hypothetical portfolio

"Reject a trade that breaches a limit" means evaluating limits against the portfolio
*as it would be if the trade executed* — not the current one. So we need a cheap
pro-forma projection: `Portfolio.projectWith(ProposedTrade) -> PortfolioSnapshot`.
Easy if positions are immutable; painful if not. This drives §5.5.

### A2.7 — Settlement and the passage of time

T+2 settlement implies a clock that advances. **Decision:** a `SimulationClock` the
harness can step manually; settlement is triggered by clock advancement, not by a real
timer. Keeps tests deterministic.

### A2.8 — Counterparties

Required by "maximum counterparty exposure." Needs a first-class `Counterparty` entity
with a credit limit. Cheap to add now, invasive to retrofit.

### A2.9 — Scope discipline  (the largest risk in the project)

The brief spans an order book, five pricers, a risk engine, Monte Carlo, Spring Boot,
Postgres, Kafka, WebSockets, OpenTelemetry, and a dashboard. Realistically that is
200–400 hours of work.

**The failure mode to fear is not building the wrong thing — it is stopping at 60%.**
Most projects of this shape die there, and a 60%-complete engine is *worse* than a
tightly-scoped complete one: a reviewer who finds a half-implemented risk engine stops
trusting the parts that are finished.

**Decisions:**

- Phase 1 (the pure Java engine) **is the product**. Phases 2–5 are stretch.
- The roadmap is ordered so the **most impressive artifact exists earliest** (§9). If the
  project stops after M3, what exists is still a credible portfolio piece.
- Kafka stays unbuilt unless something genuinely demands it — and the README says so.
  Declining unnecessary complexity, in writing, reads as senior.
- The five instruments stay, but swaps ship last and simply: vanilla fixed-float,
  single-curve discounting, no dual-curve/OIS. The simplification is documented and
  justified rather than silently assumed.

---

## 3. Domain model

### 3.1 Value objects (immutable, `record` where it fits)

Typed identifiers instead of `String` everywhere — avoids primitive obsession and makes
signatures self-documenting: `InstrumentId`, `OrderId`, `TradeId`, `PortfolioId`,
`CounterpartyId`.

Quantities and prices: `Money`, `Quantity`, `Price`, `Currency`, `CurrencyPair`,
`BasisPoints`, `Tenor`.

### 3.2 Market conventions — the unglamorous credibility layer

These are cheap to build and disproportionately convincing, because they are what a
reviewer who has worked on a real trading system looks for:

- **`DayCountConvention`** — an enum with behaviour (`ACT_360`, `ACT_365F`, `THIRTY_360`,
  `ACT_ACT`), each implementing `yearFraction(start, end)`. Polymorphism without a class
  hierarchy — a small, honest use of Java enums.
- **`BusinessDayConvention`** — `FOLLOWING`, `MODIFIED_FOLLOWING`, `PRECEDING`,
  `MODIFIED_PRECEDING`. Determines how a coupon date landing on a weekend or holiday rolls.
- **`HolidayCalendar`** — per-currency/centre, composable (a EUR/USD trade observes the
  union of both calendars). Composition, not inheritance.
- **`ScheduleGenerator`** — builds a coupon/payment schedule from an effective date,
  maturity, frequency, roll convention and calendar. This is what bonds and both swap legs
  consume, so it is written exactly once.

Omitting this layer is the single most common tell of a toy financial project.

### 3.3 Instruments — capability interfaces, not a deep hierarchy

```
FinancialInstrument            (id, symbol, currency, tradability)
├─ Stock
├─ Bond                        implements CashflowGenerating
├─ FxForward                   implements CashflowGenerating
├─ EuropeanOption              implements HasUnderlying, OptionTerms
└─ InterestRateSwap            implements CashflowGenerating
```

The hierarchy is deliberately **flat and one level deep**. Shared behaviour is expressed
by *composed capability interfaces* rather than by intermediate abstract classes
(`AbstractDerivative`, `DebtInstrument`, …), because those intermediate classes attract
unrelated logic and force is-a relationships that do not hold.

`CashflowGenerating.cashflows(LocalDate from)` is the seam that lets one discounting
engine price bonds, FX forwards and **fixed** swap legs — the single biggest duplication
kill in the project. Without it we would write present-value logic three times.

> **Corrected at M2.** An earlier draft said this seam covered swap legs generally. It
> does not. A *floating* leg's coupons depend on forward rates projected from a curve, so
> they are not contractually determined and the leg cannot honestly implement an interface
> that promises known amounts. `InterestRateSwap` therefore does not implement it either;
> its fixed leg does, and the floating leg exposes its terms for a pricer to project at M6.
> See [ADR 0004](adr/0004-capability-interfaces-and-the-cashflow-boundary.md).

**Instruments carry no pricing logic.** See §5.1 for why.

### 3.4 Aggregates and entities

- `Portfolio` — aggregate root owning `Map<InstrumentId, Position>` and a
  multi-currency `CashAccount`. Mutation only through `apply(Trade)`.
- `Position` — **immutable**; applying a trade produces a new `Position`. Makes
  pro-forma projection (§A2.6) and concurrent reads trivial.
- `Trade` — entity with an append-only lifecycle history.
- `Order` — entity; lives in the book.
- `Counterparty` — entity with credit limits.

### 3.5 The `Money` invariant

`Money.plus(Money)` throws `CurrencyMismatchException` rather than converting. Implicit
FX conversion inside an arithmetic operator is a real production bug class. Conversion is
always explicit and always names the rate source.

---

## 4. Architecture

### 4.1 Dependency direction

Strict inward-pointing dependencies (ports and adapters, without the vocabulary
ceremony):

```
        app / cli / (later) spring-web
                    │
                    ▼
      application services  (orchestration, use cases)
                    │
                    ▼
        domain  (instruments, portfolio, trades, events)
                    ▲
                    │  implements domain-owned interfaces
      pricing · risk · matching · marketdata · simulation
```

The domain declares interfaces (`PricingModel`, `MarketDataSource`, `EventBus`,
`RiskLimit`); the outer rings implement them. That is dependency inversion doing real
work, not a diagram.

**We will enforce this with ArchUnit tests**, so the layering is *proven* rather than
asserted in a README. Rules: domain must not depend on pricing/risk/infrastructure;
nothing in the core may reference `LocalDate.now()`, Spring, or JPA; no package cycles.

### 4.2 Build layout

A **3-module Maven build**:

| Module | Contains |
|---|---|
| `mercury-engine` | value objects, ids, money, market conventions, domain events, instruments, pricing, market data, curves, portfolio, matching, risk, lifecycle, simulation |
| `mercury-app` | CLI / terminal UI, demo harness, wiring (manual DI, no framework) |
| `mercury-benchmarks` | JMH suites, isolated so benchmark deps never leak into the engine |

**Revised from an earlier four-module split** that separated `mercury-core` from
`mercury-engine`. That boundary was the fuzziest of the four and would have generated
recurring "which module does this go in?" friction for no enforcement benefit — the
ArchUnit rules are what actually police layering, and they work within a module just as
well as across modules. `mercury-app` and `mercury-benchmarks` stay separate because they
earn it: the first proves no framework leaked into the engine, the second keeps JMH
dependencies out of it.

Also rejected: ten micro-modules (ceremony, slow builds, no benefit at this size) and a
single module (benchmark deps on the engine's classpath).

Maven over Gradle: enterprise Java default, POMs are readable by any reviewer without
learning a DSL, better JMH tooling story.

### 4.3 Manual dependency injection in Phase 1

No Spring in the core. Wiring is a hand-written composition root in `mercury-app`. This
*proves* the engine is framework-independent instead of claiming it, and makes Phase 2 a
pure addition.

---

## 5. The design problems that actually matter

Everything above is table stakes. These six are where the project earns its reputation.

### 5.1 Polymorphic pricing without `instanceof` — the Expression Problem

The brief asks for two things that are in tension:

- add a new *instrument* without modifying existing code, **and**
- add a new *pricing model* for an existing instrument without modifying it.

That is the **Expression Problem**. Naming it explicitly in ARCHITECTURE.md is itself a
signal. The candidate answers:

| Approach | Adding an instrument | Adding a model | Verdict |
|---|---|---|---|
| `instrument.price()` | fine | can't have two models | rejected — also violates SRP |
| `instanceof` chain | modify the chain | no | rejected outright |
| Visitor pattern | **modify the visitor interface + every visitor** | fine | rejected — breaks the stated goal |
| **Type-keyed model registry** | add class + pricer + 1 registration | register another | **chosen** |

```java
interface PricingModel<T extends FinancialInstrument> {
    Class<T> instrumentType();
    ModelName name();
    ValuationResult price(T instrument, MarketDataSnapshot md, LocalDate asOf);
}
```

`PricingService` holds `Map<Class<?>, Map<ModelName, PricingModel<?>>>`, resolves by
runtime class, and applies a `ModelSelectionPolicy` (default model per type, overridable
per request or per portfolio). Adding `ConvertibleBond` touches: one new class, one new
pricer, one registration line. **Zero existing files modified** — and §7.1 proves it with
an actual commit rather than asserting it here.

There is exactly **one** unchecked cast, confined to the registry lookup and guarded by
the `instrumentType()` invariant enforced at registration. Being upfront that Java
generics cannot fully express this — rather than hiding it — is more credible than
pretending otherwise.

This directly satisfies "Option → Black-Scholes, later Binomial": both register under
`EuropeanOption.class` with different `ModelName`s, and a test asserts the two models
agree within tolerance. That cross-model agreement test is excellent evidence of
correctness.

### 5.2 The order book — real data structures, stated complexity

One book per exchange-traded instrument.

```
OrderBook
├─ TreeMap<Price, PriceLevel> bids   (descending comparator)
├─ TreeMap<Price, PriceLevel> asks   (ascending)
├─ HashMap<OrderId, OrderNode>       ← O(1) cancellation
└─ cached bestBid / bestAsk references
```

`PriceLevel` holds an **intrusive doubly-linked list** of orders (not `ArrayDeque`),
because cancellation must unlink an arbitrary interior node in O(1). `ArrayDeque` gives
O(n) interior removal; that distinction is the whole point.

| Operation | Complexity | Note |
|---|---|---|
| Insert, new price level | O(log P) | P = distinct price levels |
| Insert, existing level | O(1) | tail append preserves time priority |
| Cancel | **O(1)** | id→node map + intrusive unlink; O(log P) only if the level empties |
| Best bid / best ask | **O(1)** | cached; TreeMap fallback is O(log P) |
| Match one fill | O(1) | |
| Match sweeping k levels | O(k log P + f) | f = fills produced |

Documenting the **rejected** alternatives is as valuable as the choice: `ArrayList` +
scan (O(n) everything), a single `PriorityQueue` (no O(1) cancel, no price-level
grouping, no stable FIFO within a price), and a fixed price-array ladder (O(1)
everything, but assumes a bounded tick range — the right choice for a real HFT venue,
wrong here; we say so).

**Benchmarked with JMH** at 10⁵–10⁶ orders: insert throughput, cancel throughput, mixed
workload, and a matching sweep. Real numbers, stated hardware, stated JVM flags.

**This ships third (M3), before pricing and portfolio.** Rationale in §9.1.

### 5.3 One shock mechanism, three features

This is the design insight I most want in the README.

`MarketDataSnapshot` is **immutable**, keyed by a sealed `MarketDataKey` (`SpotPrice`,
`FxRate`, `Volatility`, `YieldCurvePoint`, `CreditSpread`). It exposes:

```java
MarketDataSnapshot withShock(MarketShock shock);   // returns a new snapshot
```

`MarketShock` is a small interface, and shocks **compose** (Composite pattern — genuinely
justified, because a scenario *is* a tree of shocks: "Market Crash" = equities −30% ∧ FX
−10% ∧ rates +150bp ∧ vol +50%).

That single mechanism powers three headline features with no additional machinery:

1. **Stress testing** — a named `Scenario` is a composite shock; revalue the portfolio
   under the shocked snapshot.
2. **Greeks by bump-and-revalue** — Delta is a `SpotShock(+ε)`, DV01 is a
   `ParallelCurveShock(+1bp)`, Vega is a `VolShock(+1%)`. Which means **every Greek works
   for every instrument that has a pricer, with zero per-instrument code.** Add
   `ConvertibleBond` and it has Delta, Gamma, Vega and DV01 for free.
3. **Monte Carlo** — each simulated path is just another shocked snapshot.

> **Confirmed at the M5 audit, with numbers.** Delta, DV01 and FX delta are now three short
> methods on `SensitivityCalculator`, all of them the same two lines — shock, revalue,
> difference — with only the shock differing. Adding rate and currency risk to a portfolio
> that had only equity risk cost no new machinery at all.
>
> The clearest evidence that the claim above is not just a claim: the demo book's USD DV01 of
> −69.92 per basis point already **includes the two option legs' rho** (−10 of the total),
> alongside the bond's −112 and the FX forward's USD leg at +52. There is no rho formula in
> the codebase. Nothing was written to make options interest-rate-sensitive; they are, and the
> bump found it.
>
> The audit also found the other half of this, which the design document did not anticipate:
> the engine measured all of that risk from M5 onward and **printed none of it**. Being able
> to compute a risk number is not the same as reporting one, and the second failure is much
> harder to see than the first. Recorded as C-2 in `KNOWN_GAPS.md`.

Where a closed form exists, a pricer may optionally implement `AnalyticGreeks`
(Black-Scholes has exact Delta/Gamma/Vega). The risk engine prefers analytic when
available and falls back to numerical otherwise — and a test asserts the two agree within
tolerance, which validates *both* implementations at once.

Immutability is not aesthetic here: it is what makes the snapshot safely shareable across
Monte Carlo worker threads with zero synchronization (§5.6).

#### 5.3.1 Known weakness: numerical Gamma is delicate

The elegance of "every Greek for free" has a sharp edge, and hiding it would be
dishonest. Gamma is a **second-order** finite difference:

```
Γ ≈ [ V(S+h) − 2·V(S) + V(S−h) ] / h²
```

The numerator is a difference of nearly-equal quantities divided by a small number, so:

- **h too small** → catastrophic cancellation; the result is dominated by floating-point
  noise in `V`, amplified by 1/h².
- **h too large** → truncation error; we measure curvature over too wide an interval.

Mitigations, all of which are stated in ARCHITECTURE.md rather than glossed over:

- Bump size scaled relative to spot (`h = ε·S`, not absolute), with ε chosen empirically
  and the choice documented.
- **Validation against analytic Black-Scholes Gamma**, written early (M10) — because if
  this test is flaky, the §5.3 story is weaker than advertised and we need to know before
  building on it.
- Central differences (as above) rather than forward differences, for O(h²) rather than
  O(h) truncation error.
- Where an analytic form exists it is preferred at runtime; numerical is the fallback for
  instruments that have no closed form.

Being able to explain *why* this is hard is worth more in an interview than the feature
working silently.

### 5.4 Curve construction — the algorithmic content the brief under-specified

You cannot honestly price a bond, an FX forward or a swap without a discount curve, and
"yield curves" appears in the brief only as a market-data field. Building one properly is
genuine algorithmic work and is exactly the kind of thing Murex does.

- **`YieldCurve`** — immutable; exposes `discountFactor(date)` and `zeroRate(date)`.
- **`CurveInterpolator`** — a Strategy. Linear on zero rates vs. linear on log discount
  factors give different forwards; the choice is a real modelling decision and gets
  documented, not defaulted silently.
- **`CurveBootstrapper`** — builds the curve from quoted market instruments (deposits,
  FRAs, par swap rates) by solving, pillar by pillar, for the discount factor that
  reprices each instrument to par. That is an **iterative root-find** (Newton–Raphson with
  a bisection fallback for robustness) — real numerical code, with convergence criteria
  and failure handling worth writing about.
- **Round-trip test**: bootstrap a curve from par swap rates, then reprice those same
  swaps with it and assert they value to zero within tolerance. A curve that cannot
  reprice its own inputs is broken, and this test catches it immediately.

Single-curve discounting only (pre-2008 convention). Real desks use dual-curve/OIS
discounting; we deliberately do not, and the README says so with a one-line explanation.
**Naming a simplification is credible; silently assuming it is not.**

### 5.5 Portfolio without a god class

The brief explicitly warns against a giant `Portfolio`. The split:

- `Portfolio` — **state and invariants only**: positions, cash, applying trades.
- `PortfolioValuationService` — market value, unrealized P&L (needs pricing + FX; not the portfolio's job).
- `RealizedPnlLedger` — realized P&L via `CostBasisMethod`.
- `ExposureCalculator` — gross/net, by currency, by asset class, by counterparty.
- `PortfolioValuation` — an **immutable result record**, not a mutating computation.

Rule of thumb applied throughout: *if computing it requires a collaborator the entity
should not know about (a pricer, an FX rate, a clock), it is a service, not a method on
the entity.* Stating that rule in ARCHITECTURE.md shows the split was principled rather
than arbitrary.

This is also the guard against the opposite failure — an **anemic domain model** where
`Portfolio` is a bag of getters and all logic lives in services. `Portfolio` keeps the
logic that enforces its own invariants (you cannot sell into a position you do not have
without configured short permission; a cash account cannot go negative without a credit
line).

### 5.6 Concurrency — three deliberate models, not "add threads"

Different components have genuinely different concurrency characteristics, and using one
strategy everywhere would be the mistake. Documented per component:

**a) Matching engine — single-writer, no locks.**
Each `OrderBook` is owned by exactly one thread and fed by a `BlockingQueue` of commands.
Books for different instruments run in parallel; a single book is never contended. This
is the LMAX-style insight: *serialize commands rather than lock the data structure.* ~~A
lock-per-book design would be slower and far harder to reason about.~~ Deterministic replay
from the command log falls out for free — which is what makes §7.2 possible.

> **Corrected at M13, by measurement.** Both struck claims turned out to be wrong here.
>
> A lock-per-book design is **faster**, not slower: 3.8× the throughput at twelve books
> (`docs/BENCHMARKS.md` §6). The reason is that `ExecutionVenue.execute` returns the trades it
> produced, so a caller submits a command and then blocks for the result — paying an enqueue,
> a park and a wake-up (~0.9 µs) around matching that is quicker than the handoff, while
> twelve writer threads on top of twelve callers oversubscribe the CPU. LMAX's advantage
> assumes fire-and-forget submission, a batching writer and a spinning queue; this paragraph
> assumed the advantage transfers to a blocking handoff, and it does not.
>
> Nor was it harder to reason about. `BookConcurrency` gives both designs the same shape — a
> lane with exclusive access to one book — and the same tests pass against each.
>
> Replay does not fall out for free either, because the queue is in memory and each command is
> discarded once run: what falls out is the *structure* replay needs (one owner, one ordered
> stream of commands), not the recording. See `docs/KNOWN_GAPS.md`.
>
> Both are built: `INLINE` is the default because it is faster, and `THREAD_PER_BOOK` because
> it is what makes one-book-one-owner real.

**b) Monte Carlo / risk — embarrassingly parallel, immutable inputs.**
Scenarios are independent, snapshots are immutable, the portfolio is read-only during a
run → **no shared mutable state on the hot path**, so no synchronization is needed.
Partitioned across an `ExecutorService`; results reduced into a distribution.

The subtle part, and the one worth writing about: **random number generation.** A shared
`java.util.Random` is both a contention point (CAS retry storm on its atomic seed) *and*
destroys reproducibility, because thread interleaving changes which path gets which draws.
Fix: `SplittableRandom` / `RandomGenerator.SplittableGenerator` with a deterministic split
per task, seeded from the run seed. Same seed → same VaR, on 1 worker or 8. **Reproducible
parallel Monte Carlo is a genuinely non-trivial thing to get right and a strong interview
talking point.**

**c) Event bus — pluggable dispatch.**
Same `EventBus` interface, two implementations: synchronous (default, deterministic, used
in all tests) and asynchronous (`BlockingQueue` + `ExecutorService`, for the live
simulation). Documented tradeoff: async buys throughput and failure isolation, costs
ordering guarantees and test determinism. Making async the default from day one is a
classic mistake and we will say so.

**Benchmarks:** Monte Carlo at 1 / 2 / 4 / 6 / 8 / 12 workers on the target machine
(i5-10400F: **6 physical cores, 12 threads**, 16 GB RAM). We should *expect* near-linear
scaling to ~6, then a sharp knee — hyperthreading gives perhaps 15–30% more on ALU-bound
floating-point work, not 2×. Beyond that: allocation rate and GC pressure, memory
bandwidth, and Amdahl's serial tail (the final sort/percentile for VaR). Measuring that
knee and **explaining it honestly** is worth more than a fabricated 8× speedup — and any
reviewer who has done this will know a claimed 8× on 6 cores is fiction.

> **Measured at M13** (`docs/BENCHMARKS.md` §5). The shape of the prediction was right — no
> 8×, a real knee, and the honest explanation is the useful part — but two specifics were
> wrong, in opposite directions.
>
> Option pricing never became linear (1.74× on 2 workers) and hyperthreads then added **47%**
> from 6 to 12 workers, well above the 15–30% predicted, ending at 5.93×. VaR bent much
> earlier than the core count, flattening at **2.6× from 4 workers**.
>
> Amdahl's serial tail is not what caused it: splitting, concatenation and one sort of 50,000
> values are far too small to hold a 157 ms job to 2.6×. A GC-profiled rerun points at
> allocation — ~285 MB per run, with the rate pinned at ~4.6 GB/s from 4 workers upward and GC
> pauses only a few percent of wall time. Allocation rate was on this list; GC pressure, which
> it was listed beside, was not the binding constraint.

---

## 6. Design patterns — used, and deliberately not used

Each entry states the *problem*, not just the pattern name.

| Pattern | Where | Problem it solves |
|---|---|---|
| **Strategy** | `PricingModel`, `CostBasisMethod`, `CurveInterpolator`, `ScenarioGenerator`, `VaRMethod` | Multiple interchangeable algorithms selected at runtime; the core must not know which |
| **Composite** | `MarketShock`, `RiskLimit`, `HolidayCalendar` | A scenario genuinely *is* a tree of shocks; a limit set *is* a tree of limits; a cross-currency trade observes a union of calendars. Uniform treatment of leaf and composite is the real requirement |
| **Registry / typed factory** | `PricingService`, `InstrumentFactory` | Open-closed dispatch on instrument type without `instanceof` (§5.1) |
| **Observer** | `EventBus` | Pricing, portfolio, risk and alerting must react to market events without knowing about each other |
| **Command** | `SubmitOrder`, `CancelOrder`, `BookTrade` | Gives an audit log, replay capability, and a natural async queue boundary for the single-writer matching engine — three real benefits, not one |
| **Builder** | `InterestRateSwap`, `Bond` | Genuinely many-parameter, many-optional construction. *Not* used for `Stock`, which has three fields, nor for `Scenario` (M11) — see correction below |
| ~~**Template Method**~~ | ~~discounted-cashflow pricing base~~ | **Not used — see correction below.** Bond and FX forward turned out to differ in *nothing* the discounting cares about, so there was no varying step to override |
| ~~**Builder**~~ | ~~`Scenario`~~ | **Not used — see correction below.** Only one field (`description`) turned out to be genuinely optional; a varargs factory says the same thing in a third of the code |
| **Adapter** | market data feeds | Isolates external formats from the domain (matters in Phase 2) |
| **State machine** | trade lifecycle | See below — with a caveat |

> **Corrected at M5.** Template Method was listed above as justified for the
> discounted-cashflow base, with per-instrument subclasses overriding a cashflow-projection
> step. Implementing it showed there is no such step: `Bond` and `FxForward` both expose
> `cashflows(LocalDate)` and differ in nothing the discounting uses, so a base class with two
> subclasses containing only a type token and a name would have been pure ceremony.
>
> It is instead a single generic model, `DiscountedCashflowModel<T extends FinancialInstrument
> & CashflowGenerating>`, registered once per instrument type. The intersection bound expresses
> the requirement exactly — the compiler will not allow it to be registered for an instrument
> that cannot produce cashflows.
>
> The floating swap leg is the case that genuinely *would* need a varying step, because its
> coupons must be projected from a curve before they can be discounted. If M6 confirms that, a
> shared skeleton may be worth extracting then. **A pattern is cheap to add once a second case
> proves it is needed, and expensive to remove once it is load-bearing** — which is the whole
> argument for not reaching for one in advance.

> **Settled at M6.** The predicted second case arrived exactly as described: `SwapModel`
> projects each floating coupon off the curve before discounting it, which the bond and the FX
> forward do not do. It justified extracting something shared — and what it justified was
> `CashflowDiscounting`, a four-line **static function** that both models call.
>
> Not a base class. There is still nothing for a subclass to override: the varying part lives
> entirely inside `SwapModel`, before the shared sum is reached, and a Template Method would
> have had to invent a hook for a step that only one of the two models performs at all.
>
> The useful conclusion is a negative one, and worth recording as such. Waiting for the second
> case did not vindicate the pattern the design predicted — it showed the pattern was never the
> right shape. What the two pricers genuinely had in common was four lines of arithmetic, and
> that is what they now share.

> **Corrected at M11.** Builder was listed above as justified for `Scenario`, on the same
> "genuinely many-parameter, many-optional" reasoning that holds for `Bond` and
> `InterestRateSwap`. Building it showed the reasoning does not transfer: a `Scenario` has
> exactly one optional field (`description`) and a repeated shock list, not several
> independently-defaultable knobs. `Scenario.of(name, description, MarketShock...)` says
> everything the Builder said, at every call site, in a third of the code.
>
> The same lesson Template Method taught above applies again: a pattern justified by a
> *predicted* shape has to be checked against the shape that actually gets built, not
> assumed from the prediction. If a second genuinely optional field arrives later, Builder is
> cheap to add back then — which is the whole argument for not reaching for it in advance.

**Deliberately rejected, and the README will say why:**

- **Visitor** — breaks open-closed on the instrument axis (§5.1).
- **Singleton** — a global `EventBus` would make tests order-dependent and impossible to
  run in parallel. Injected instead.
- **Full State pattern for the trade lifecycle** — six state classes for a mostly linear
  progression is ceremony. The lifecycle's real requirement is *rejecting invalid
  transitions*, which an explicit transition table (`EnumMap<TradeState, Set<TradeState>>`)
  expresses more clearly and tests more directly. Where states *do* differ behaviourally
  (amendment allowed before `BOOKED`, cancellation before `SETTLED`) we attach guards to
  the transition rather than writing a class per state. **Confirmed as an intentional
  deviation from the brief.**
- **Abstract factories / provider hierarchies** — nothing here needs them.
- **Microservices / Kafka** — no requirement demands them at this scale.

Trade lifecycle: `NEW → VALIDATED → BOOKED → EXECUTED → CONFIRMED → SETTLED`, plus
terminal `REJECTED` and `CANCELLED`. Every transition appends an immutable
`TradeLifecycleEvent` (who, when, why) — an **append-only audit trail**, which is exactly
what real capital-markets systems require and a nice touch of domain realism.

---

## 7. Evidence artifacts

Two deliverables exist purely to convert architectural claims into verifiable facts.
They are cheap to build and are among the highest-value items in the project.

### 7.1 The extensibility proof

Late in the project (M15), add a **sixth instrument** — an American option (priced by a
binomial tree) or a convertible bond — as a **single commit that modifies zero existing
files**. Then link that commit's diff directly from the README.

Expected diff: one instrument class, one pricing model, one line in the composition root,
plus tests. Nothing else.

Every portfolio README claims open-closed design; almost none prove it. This lets a
reviewer verify the project's central architectural claim in one click. It also functions
as a genuine regression test on the architecture: **if the diff turns out to be large, the
design failed and we learn it while there is still time to fix it.** That makes this
valuable even in the failure case.

### 7.2 The golden-master determinism test

Run the full simulation with a fixed seed and a fixed clock; snapshot the resulting trade
blotter, final positions, P&L and risk numbers; commit that as a golden file. The test
re-runs the simulation and asserts byte-identical output.

Two reasons this is worth more than another feature:

- **It catches regressions across every component at once** — pricing, matching, lifecycle,
  portfolio and risk are all covered by one assertion.
- **Reproducibility is a regulatory requirement in real trading systems.** You must be able
  to explain why a number was what it was on a given day. Demonstrating that you know this,
  and that the architecture (injected clock, injected seed, immutable snapshots, replayable
  command log) was built to support it, is strong domain signal.

It also enforces the discipline from §A2.4 and §5.6 — the test simply cannot pass if
hidden clock reads or shared RNG state creep in.

---

## 8. Testing strategy

- **Reference-value tests** for every pricer, against published worked examples (Hull's
  textbook values for Black-Scholes; a hand-computed bond price with a stated curve).
  Financial code that only tests itself against itself is worthless.
- **Property-based tests** (jqwik) where invariants are stronger than examples: put-call
  parity; option price monotonic in volatility; bond price monotonically decreasing in
  yield; portfolio value = Σ position values; matching conserves quantity (Σ buys = Σ
  sells); no crossed book after matching; cash + positions conserved across a trade.
- **Cross-validation tests**: analytic Greeks vs. bump-and-revalue (§5.3.1); Black-Scholes
  vs. binomial as steps → ∞; Monte Carlo option price converging to the closed form; a
  bootstrapped curve repricing its own input instruments to par (§5.4). These catch errors
  no single-implementation test can.
- **State machine tests**: exhaustive — every (state, transition) pair asserted legal or
  rejected. Cheap, complete, demonstrably rigorous.
- **Concurrency tests**: same seed → identical VaR across 1 and 8 workers
  (reproducibility); concurrent order submission conserves quantity; randomized
  interleavings against the book. *(All three exist as of M13, plus one that watches which
  thread announces each execution to prove a book really has a single writer.)*
- **Golden-master test** (§7.2) for end-to-end determinism.
- **ArchUnit** for layering, cycles, and "no framework in the core."
- **Integration tests** for full slices: submit order → match → book trade → lifecycle →
  position update → P&L → risk limit check.
- **JMH** for benchmarks, in a separate module, with documented hardware and methodology.

Determinism is a first-class goal: injected clock, injected seed, synchronous event bus in
tests. Flaky tests in a portfolio repo are actively damaging.

---

## 9. Architectural mistakes we are explicitly avoiding

A list to keep visible during implementation and to address in ARCHITECTURE.md:

1. God `Portfolio` doing state + valuation + risk + P&L.
2. The opposite: an anemic domain model with all logic in services.
3. `double` for cash; `BigDecimal` in Monte Carlo inner loops.
4. `instanceof` chains, or a Visitor that must change for every new instrument.
5. Instruments that price themselves (blocks multiple models, violates SRP).
6. Mutable market data shared across threads.
7. A static/singleton event bus (untestable, order-dependent).
8. Async everything from day one → non-deterministic tests.
9. Deep inheritance (`Bond extends DebtInstrument extends AbstractInstrument`).
10. JPA/Spring annotations leaking into the core domain in Phase 3.
11. Running OTC instruments through the CLOB (§A2.1).
12. Silent FX conversion inside arithmetic.
13. Speculative abstraction — interfaces with exactly one implementation and no second one
    in sight. (`PricingModel` has five. `EventBus` has two. Those earn their keep.)
14. Fabricated benchmark numbers.
15. Pricing instruments without a properly constructed discount curve (§5.4).
16. Shipping a generic web dashboard (§10.4) — see the reasoning there.

---

## 10. Roadmap

Phase 1 is the product. Each milestone is a coherent, tested, reviewable slice — design
explained first, then code, then tests, then a short architecture review before moving on.

### 10.1 Ordering principle: front-load the most impressive artifact

The order book moves to **M3**, ahead of pricing and portfolio. Three reasons:

1. It is the component most likely to be asked about in an interview.
2. It is almost entirely self-contained — it depends only on M1 value types.
3. It demos and benchmarks in isolation, so it produces a headline artifact early.

**Consequence:** after M3 the repo already contains a benchmarked, well-tested order book
with documented complexity analysis. If the project stalls at that point — and §A2.9 says
that is the realistic risk — what exists is still a credible portfolio piece rather than
scaffolding.

### Phase 0 — Environment (blocking)

- Install JDK 21 (Temurin) and Maven. **Neither was installed on this machine.**
- Multi-module Maven skeleton, `.gitignore`, GitHub Actions CI (build + test on push).

### Phase 1 — The engine

| # | Milestone | Delivers |
|---|---|---|
| M1 | Core types & market conventions | `Money`, typed ids, `Quantity`, `Currency`, `Tenor`, day count, business-day conventions, `HolidayCalendar`, `ScheduleGenerator`, `SimulationClock` — ✅ **done** |
| M2 | Instruments | 5 instruments on capability interfaces — ✅ **done** |
| M3 | Order book | CLOB, price-time priority, O(1) cancel, JMH benchmarks — ✅ **done** |
| **M4** | **Vertical slice: value a portfolio** | Minimal `MarketDataSnapshot`, `MarketShock`, `PricingModel` registry, **two** pricers (stock + Black-Scholes), minimal `Position`/`Portfolio`, market value, **Delta by bump-and-revalue**, and a CLI that prints it. **First runnable end-to-end capability.** |
| M5 | Broaden pricing | Generic DCF model (not a template — see §6), bond and FX-forward pricers, flat discounting; reference-value tests against published figures — ✅ **done**, audited, with DV01, FX delta and clean/dirty reporting added in response |
| M5b | Curve construction | `YieldCurve`, interpolation strategies, bootstrapper, par round-trip test — ✅ **done**. Pillars are dates rather than tenors, for a reason that cost a defect to learn (ADR 0006); the whole pricing stack moved onto curves without a single reference value changing |
| M6 | Swap pricing | Floating-leg projection against a curve; completes all five instruments — ✅ **done**, with the par-rate round trip as its check |
| M7 | Full portfolio | `CashAccount`, realized/unrealized P&L, `CostBasisMethod`, exposure, **multi-currency valuation** — ✅ **done**. Positions are now projected from a trade history rather than declared, and the whole report reconciles against the book's opening cash |
| M8 | Trade lifecycle & execution | State machine, audit trail, both venues, `Counterparty`; closes gaps G-1 and G-2 |
| M9 | Risk limits | `RiskLimit` composite, pro-forma projection, breach events, rejection |
| M10 | Risk engine | Remaining Greeks, analytic vs numerical cross-validation, **Gamma validation (§5.3.1)**, DV01, historical VaR |
| M11 | Scenarios / stress | Named scenarios (Market Crash, Rate Shock, Currency Crisis), impact report |
| M12 | Monte Carlo, single-threaded | GBM paths, VaR + Expected Shortfall, convergence tests |
| M13 | Concurrency | Parallel MC, async bus, single-writer books, **scaling benchmarks 1→12 workers** |
| M14 | Harness, terminal UI, full golden master | End-to-end demo in one command + **§7.2 determinism test** — ✅ **done**. The golden-master book itself now trades through `ExecutionRouter` rather than declaring its positions by hand |
| M15 | Extensibility proof & documentation | **§7.1 sixth-instrument commit** — ✅ **done** ([docs/EXTENSIBILITY.md](EXTENSIBILITY.md)); README, [ARCHITECTURE.md](ARCHITECTURE.md), diagrams — ✅ **done** |

**M15 is not optional polish** — for this project's actual goal it is one of the
highest-value milestones. Short ADRs should be written *as we go* rather than
reconstructing rationale at the end; ADRs in a repo are a strong seniority signal.

### 10.1b M4 is restructured as a vertical slice

**The problem with the original plan.** M4 (market data) → M5 (curves) → M6 (pricing) →
M7 (portfolio) had to *all* land before the project could demonstrate anything. Four
consecutive milestones of invisible work is the worst possible shape for a project whose
principal risk is stopping partway (§A2.9), and it was a planning error rather than a
discovery.

**The change.** M4 becomes a thin slice cutting through every layer, deep enough to be real
and narrow enough to finish quickly:

```
Stock + EuropeanOption            (already exist)
        │
        ▼
MarketDataSnapshot                spot + volatility + a flat rate. No curves yet.
        │
        ▼
PricingService  →  PricingModel   the type-keyed registry: the Expression Problem answer
        │                          proved on two implementations rather than argued
        ▼
Portfolio → PortfolioValuation     positions and market value. No cash, no realised P&L.
        │
        ▼
Delta by MarketShock + revalue     one Greek, proving the shock mechanism end-to-end
        │
        ▼
CLI: "value this portfolio"        something a reviewer can run
```

**What it deliberately leaves out:** curves, bonds, swaps, cash accounts, P&L, the event
bus, the remaining Greeks. Each arrives in a later milestone against a pipeline that already
works.

**Why this is better than the original order:**

- It produces a **demonstrable capability** — "price a portfolio and compute its delta" —
  four milestones earlier.
- It **de-risks everything after it.** The moment pricing works end to end, the shock
  mechanism, the risk engine and Monte Carlo become incremental rather than speculative. The
  §5.3 claim that one mechanism powers three features is currently unproven; M4 proves it in
  miniature, while it is still cheap to be wrong.
- It **validates the registry design early.** Two pricers is the minimum that can show the
  Expression Problem answer works. If the design is wrong, the second pricer reveals it.
- It **puts curve bootstrapping off the critical path.** Bootstrapping is the hardest
  numerical work in the project and it was blocking *all* pricing. It becomes M5b, valuable
  and independently schedulable, rather than a gate.

**It also fixes the testing gap the audit exposed.** The pre-M4 audit found a defect
(`Bond.maturityDate()` disagreeing with its own cashflows) that no unit test could have
caught, because both components were individually correct. Everything so far is unit-tested
and nothing is integration-tested, and the golden master was scheduled for M14 — far too late
to protect M4–M13, which is exactly when cross-component bugs appear.

M4 therefore ships a **miniature golden-master test**: fixed seed, fixed clock, a small
portfolio valued end to end, with the output committed. It grows with each milestone instead
of arriving at the end, giving every subsequent milestone a regression net from the day it
starts.

### 10.2 Phase 2 — Spring Boot: build it thin

REST API over the engine; the engine untouched and still Spring-free; DI wiring only.

**Deliberately deprioritized.** For this specific target, a controller layer adds less
than people assume — Murex is a deep systems and domain shop, not a REST-CRUD shop, and
the matching engine and Monte Carlo work is far more relevant to them. Phase 2 exists for
employability breadth and should not consume time budgeted for the core.

### 10.3 Phase 3 — Persistence

PostgreSQL + Flyway. Separate persistence models mapped to domain objects, so JPA never
touches the core (§9.10). Transactions and consistency discussed explicitly.

### 10.4 Phase 4 — Interface and observability

**No React dashboard.** A mediocre web dashboard actively *damages* a
backend-architecture portfolio piece, because reviewers judge what they can see: if the
frontend looks amateur, that impression contaminates their read of a backend they have
not inspected yet. The downside risk exceeds the upside.

Preferred alternatives, in order:

1. **A terminal UI** rendering the live order book, blotter, P&L and risk. Reads as
   "systems engineer," is genuinely fun to demo, and cannot look like a generic admin
   template.
2. **A static HTML report** generated by the engine after a simulation run — scenario
   results, risk numbers, benchmark charts. Zero frontend risk, and it doubles as
   documentation.

Observability (Micrometer/OpenTelemetry, Prometheus/Grafana) also lands here, and a
Grafana screenshot delivers most of the "real system" visual impact a dashboard would,
without the frontend risk.

#### M16 — Terminal UI — ✅ done

Scoped in [ADR 0007](adr/0007-hand-rolled-ansi-terminal-ui.md): hand-rolled ANSI, not a
library, replaying the same deterministic demo scenario one step at a time rather than
simulating a live feed the engine has no way to produce. Lives entirely in `mercury-app` —
`mercury-engine` gains nothing for this feature alone to consume, the same boundary
`Main`'s own javadoc already states.

What it renders, and where each panel's data comes from:

| Panel | Source | Per-tick cost |
|---|---|---|
| Order book depth | a `ShadowBook` per instrument, mirroring the real venue's instructions | cheap |
| Blotter | `TradeExecuted` events off the `EventBus`, plus rejections recorded locally | cheap |
| P&L | `ledger.toPortfolio()` + one `PortfolioValuationService.value(...)` | cheap |
| Greeks / DV01 | `SensitivityCalculator` | cheap |
| VaR | `MonteCarloVaRCalculator` (20k paths in the demo) | expensive — throttled, not per tick |

Breakdown:

- **M16a — stepping model** — ✅ done. `ScenarioStepper` runs a fixed list of
  `ScenarioStep`s in order, one per `advance()`; `TuiDemo` drives it from a
  `SimulationClock.fixedAt` (the same clock `EndToEndDemo` uses — this replays
  `EndToEndDemo`'s single-day sequence, not the golden master's multi-day one) and blocks on
  a line of stdin between steps.
- **M16b — book and blotter panels** — ✅ done. `OrderBookVenue` keeps its per-instrument
  books in a private map, by design (§5.6) — reachable from nowhere outside its own lane,
  which ruled out reading depth from the real venue without adding a method to
  `mercury-engine`. Resolved without one: `ShadowBook` wraps a second, display-only
  `OrderBook` per instrument, built from types that were already public
  (`OrderBook`, `Order`, `OrderId`), and mirrors every instruction the real venue receives —
  an `OrderBook` is a deterministic function of the orders it gets, so an identical sequence
  produces identical depth. `BookDepthView` formats it as plain text, tested without a
  terminal. The blotter's open question resolved the same way: a rejected negotiation never
  reaches the bus as an event, but `TuiDemo`'s own step already holds the
  `NegotiationResult` that says so, so `Blotter.recordRejection(...)` is called directly
  from there — no new event type needed.
- **M16c — P&L and risk panel** — ✅ done. `PnlRiskPanel` wires the same
  `PortfolioValuationService`, `SensitivityCalculator` and `MonteCarloVaRCalculator` calls
  `EndToEndDemo` makes, against `TuiDemo`'s own evolving ledger rather than the fixed golden-
  master book. Valuation, P&L, delta and DV01 are cheap and recompute on every redraw; VaR is
  not (20,000 paths), so it is recomputed only every *N* steps — cadence chosen over "off the
  render thread" for the same reason the whole UI blocks on stdin between steps: a replay has
  no frame the user is waiting on while a background thread finishes, so there is nothing a
  second thread buys here that a cadence does not. Between recomputes the last result is
  shown, labelled `(as of step N)` rather than silently going stale.
- **M16d — a way to test it** — done, now that M16c gave it a panel worth the split.
  `ScenarioStepper`, `ShadowBook`, `BookDepthView`, `Blotter` and `PnlRiskPanel` are each
  tested without a terminal; only `TuiDemo.printFrame` writes raw ANSI, and it stays as thin
  as `Main`'s own console-facing methods, assembling nothing itself.

New command: `java -jar mercury.jar tui`, dispatched the same way `walkthrough`,
`lifecycle`, `risk` and `montecarlo` already are.

**Not in scope:** a live external market feed, new order types, or any change to
`mercury-engine`'s public surface beyond what M16b's blotter decision requires. Shipped: the
README's milestone table and "Built so far" section now carry the M16 row this rule gated on,
and [docs/MILESTONES.md](MILESTONES.md) carries the full write-up.

#### M17 — Asynchronous order submission — ✅ done

Scoped in [ADR 0008](adr/0008-fire-and-forget-order-submission.md): `docs/KNOWN_GAPS.md`'s
"No asynchronous submission path" entry warned that fixing §5.6 a)'s single-writer engine's
own measured regression (`docs/BENCHMARKS.md` §6: `THREAD_PER_BOOK` 1.7-3.8× *slower* than
`INLINE`, because `SingleWriterBookLane.run` parks the caller on a `FutureTask`) would change
`ExecutionVenue`'s contract for every caller in the codebase. It did not need to: the problem
is specific to `OrderBookVenue`/`SingleWriterBookLane`, not the shared interface.

`BookLane` gained `submit(Runnable)` beside the existing `run(Supplier<T>)`;
`SingleWriterBookLane.submit` queues a plain `Runnable` (no future, no waiting) and
`OrderBookVenue.submit(OrderBookInstruction, SimulationClock)` is new and additive - `execute`
and every existing caller (`ExecutionRouter`, every demo, every `execute`-based test) are
unchanged. A submitted order's trades reach a caller only through the event bus, never as a
return value. Measured rather than asserted: §6's M17 section shows `submit` on
`THREAD_PER_BOOK` roughly doubling throughput on one book and clearing the twelve-book figure
too - and the same benchmark run reproduced an `OutOfMemoryError` under sustained synthetic
load, because the queue this needs is unbounded, the same trade-off `AsynchronousEventBus`
already made. Recorded as its own entry in `docs/KNOWN_GAPS.md` rather than smoothed over.

**Not in scope:** a redesign of `ExecutionVenue`'s shared contract, an equivalent for
`OtcNegotiationVenue` (which has no threading model to give one to), and a bound-and-policy
fix for the queue this opens - see the ADR's "Alternatives rejected" for why bounding it now
would be sizing against a guess, the same reasoning that already applies to
`AsynchronousEventBus`.

#### M18 — Global counterparty exposure ledger — ✅ done

`docs/KNOWN_GAPS.md` named the gap directly: `OtcNegotiationVenue.exposureByCounterparty` lived
on the venue instance, correct only as long as exactly one venue ever traded against a given
counterparty. `ExposureLedger` (`com.mercury.execution`) is the same state - lock, running
totals, open-exposure records - extracted wholesale so it can be constructed once and shared.
`OtcNegotiationVenue` keeps the sequence a credit check needs (read, check, mint a trade on
approval, commit, publish) under `ExposureLedger.lock()`; the ledger owns only the state and
its own `release`. Every existing constructor is untouched - each still builds a private ledger
internally - and one new overload takes a shared one, so this is additive for every caller
before M18, the same shape M17 already used for `submit`.

Proven, not just asserted: `ExposureLedgerTest` constructs two `OtcNegotiationVenue` instances
against one shared ledger and the same counterparty, and shows the second sees no room left
once the first has spent it - the exact scenario the old per-instance design could not even be
tested against, because nothing in the codebase had ever constructed two. A scaled-down version
of the existing concurrent stress test, split across both instances, confirms the combined
limit is never over-admitted.

#### M19 — Settlement scheduling — ✅ done

§A2.7 decided "settlement is triggered by clock advancement, not by a real timer" back at M1's
design pass; nothing built it. `Trade` carried a `settlementDate` and the `SETTLED` state, but
driving a trade there was always a caller's explicit action - `TradeLifecycleDemo` carried two
copies of the same manual `.transitionTo(CONFIRMED).transitionTo(SETTLED)`.

The gap under the gap: neither venue ever actually populated `Trade.settlementDate()` - every
trade minted by `OrderBookVenue` or `OtcNegotiationVenue` carried `Optional.empty()`. A
scheduler has nothing to schedule against an empty date, so `SettlementConvention` (T+2
calendar days - a stated simplification, not a `HolidayCalendar` roll, since this milestone is
about the scheduler firing, not the convention deciding the date) gives every trade a real one
first. `TradeSettlementBook` (`com.mercury.trade`) is the scheduler itself: a
`Consumer<TradeExecuted>` holding open trades in insertion order, and `settleDueBy(asOf, clock)`
a pull-based step a caller invokes after advancing a clock - the same shape
`Schedule.unpaidPeriodsAsOf` already uses for date logic elsewhere, not a push/listener wired
into `SimulationClock`, which has no precedent in this codebase. It returns the trades it just
settled to its caller rather than publishing a new event type nothing yet consumes.

**Wired into a real consumer, not left as unused machinery.** `TradeLifecycleDemo`'s two manual
settlement call sites are gone, replaced by a `TradeSettlementBook` subscribed to a real event
bus (the demo previously passed `EventBus.ignoring()`, since it books from returned trade lists
rather than the bus). Running it settles three trades at once in one section - every trade
minted up to that point, since nothing advances the demo's clock and they all share one
settlement date - which is `TradeSettlementBook` actually sweeping everything due, not a
narrower demo than the hand-written version it replaced.

**Not in scope:** business-day-aware settlement (a `HolidayCalendar` roll), a push notification
when `SimulationClock` advances, and a new event type for settlement itself - `settleDueBy`'s
return value is the only channel this milestone needed.

#### M20 — Multi-factor correlated Monte Carlo VaR — ✅ done

`MonteCarloVaRCalculator`'s own javadoc named the gap: "a real multi-factor portfolio VaR would
need correlated draws across every risk factor at once - a covariance matrix, a Cholesky
decomposition, a joint distribution this class does not have."
`CorrelatedMonteCarloVaRCalculator` (`com.mercury.simulation`) is that answer, built alongside
the single-factor class rather than in place of it - a book with only one real driver of risk
has no correlation to draw, and the simpler, cheaper class stays right for that case.

`CorrelationMatrix` validates a caller-supplied correlation structure (square, symmetric, unit
diagonal, entries in `[-1, 1]`, and - the check the other four cannot substitute for - positive
definite) and computes its Cholesky factor once at construction. Each simulated path draws one
independent standard normal per risk factor, correlates them through that factor
(`L * Z`), and feeds each factor's own correlated normal to a new
`GeometricBrownianMotion.terminalValueFromStandardNormal` - the existing `terminalValue`
formula, extracted so a pre-correlated draw can be fed to it directly rather than the method
drawing its own. Every factor's resulting shock composes into one `MarketShock.composite` per
path, reusing `MarketShock`'s existing composability rather than a new joint shock type.
Reproducibility carries over for free: same `SensitivityCalculator`/
`HistoricalVaRCalculator`/seed/`SimulationWorkers`/`PathBlocks` machinery as the single-factor
calculator, so a correlated run is bit-identical on any worker count too.

[ADR 0009](adr/0009-correlation-matrix-positive-definite-not-semi-definite.md) records one
narrowing: `CorrelationMatrix` requires positive *definite*, not merely semi-definite, so an
exactly singular correlation (an exact `+1`/`-1` pairwise correlation is the simplest case) is
rejected rather than decomposed with a pivoted algorithm - a real, well-understood fix, but for
a caller nothing in this codebase has yet.

Wired into a real demo, not left only tested: `MonteCarloDemo` gained a third section - AAPL
and MSFT simulated jointly, next to the naive uncorrelated sum of each leg's own standalone
VaR - so the diversification effect is visible in actual output, not just asserted in a test.

**Not in scope:** correlation estimation from historical data (this engine has no historical
time series anywhere to estimate one from - the same reasoning `docs/KNOWN_GAPS.md`'s
"No historical-data loader" entry already gives), and a pivoted/rank-revealing decomposition
for the singular case ADR 0009 declines.

#### M21 — Fault isolation on worker threads — ✅ done

Not on the roadmap - found while reproducing M17's own documented back-pressure gap for a
debugging pass, not while looking for a new gap. Running `submitCrossingPairAsync` under a
capped heap confirmed the `OutOfMemoryError` `docs/BENCHMARKS.md` §6 already recorded, and
something it didn't: `SingleWriterBookLane.runUntilClosed` ran a submitted command with no
exception boundary at all, so the `Error` killed the writer thread uncaught while leaving
`closed` unset - `submit()` only checks that flag, so it kept silently accepting work onto a
writer thread that no longer existed, forever, with nothing to tell the caller. Every other
worker-thread-owning class in the codebase was then checked deliberately for the same shape,
and `AsynchronousEventBus` had it too: `Dispatch.to` catches only `RuntimeException` around a
subscriber call, never `Error`, so the same failure killed the dispatcher thread while
`publish()` kept accepting events into a queue nothing would ever drain.

Both are fixed the same way. A `RuntimeException` from `SingleWriterBookLane.submit`'s work is
now counted (`failureCount()`) and the writer keeps going - fire-and-forget work has no caller
left to fail, the same "nowhere to throw" answer `AsynchronousEventBus` already gives its own
subscribers. An `Error` is not survivable, so the writer or dispatcher thread now marks its
lane or bus closed *before* it dies, so a later `submit`/`publish` rejects loudly instead of
silently queuing onto a thread that is gone - and the `Error` itself is still rethrown, so it
is reported the way an uncaught exception on any other thread would be, not swallowed.
`SimulationWorkers` was checked and is not affected: it runs blocks through
`ExecutorService`/`FutureTask`, which the JDK already makes capture `Throwable` rather than let
it kill the pool thread.

Proven with the same technique that found the gap: `SingleWriterBookLaneTest` and
`EventBusTest` each have a submitted command/subscriber that throws `OutOfMemoryError`
directly, asserting the lane or bus closes and a subsequent call is rejected - deterministic
unit tests, not benchmarks that happen to reproduce an OOM.

#### M22 — FX triangulation through a single stated vehicle currency — ✅ done

`MarketDataSnapshot.fxRate`'s own javadoc named the gap explicitly: "no triangulation... GBP
to USD is not derived from GBP/EUR and EUR/USD; only the pair itself and its inverse are
consulted." `Builder.vehicleCurrency(Currency)` is the fix - unset by default, so every
existing snapshot is untouched; when set, a pair still missing after the existing direct/
inverse resolution gets one retry through the named vehicle
(`fxRate(from, vehicle) * fxRate(vehicle, to)`).

Deliberately one stated hop, not a graph search over every currency a snapshot happens to
hold - a search would answer "which crosses are legal" implicitly and differently per
snapshot, which is exactly the "inferring one silently" failure the original javadoc already
rejected. [ADR 0010](adr/0010-fx-triangulation-through-a-single-stated-vehicle-currency.md)
records this and the other alternative considered (a per-call vehicle parameter on `fxRate`
itself, rejected because two callers reading the same snapshot could then disagree about which
crosses are legal - a market-data consistency property that belongs on the snapshot, not the
call site).

A cross that fails even through the stated vehicle still throws `MissingMarketDataException`,
now naming the vehicle currency that was tried - loud, not silently degraded.

**Not in scope:** multi-hop triangulation through more than one bridge currency, and any form
of automatic vehicle-currency inference - both stay out per the ADR's stated boundary.

#### M24 — Confidence interval on Expected Shortfall — ✅ done

`HistoricalVaRCalculator.valueAtRiskConfidenceInterval` already priced VaR's own estimation
uncertainty cheaply, straight from rank - VaR is one order statistic, so no resampling is
needed. Expected Shortfall averages a whole tail of variable size, not one order statistic, so
that trick has no equivalent - `docs/KNOWN_GAPS.md` named this explicitly as "a genuinely
different, harder statistical problem... not a small extension of the VaR one," deferred
rather than approximated incorrectly.

`expectedShortfallConfidenceInterval(Portfolio, List<MarketShock>, MarketDataSnapshot,
LocalDate, double confidenceLevel, long seed)` closes it with a seeded bootstrap: resample the
scenario list with replacement 1,000 times, compute Expected Shortfall on each resample through
the exact method the point estimate already uses, and report the percentile band of the
result. [ADR 0011](adr/0011-bootstrap-confidence-interval-for-expected-shortfall.md) records
why a bootstrap rather than an asymptotic formula (the same distribution-free convention the
rest of this class already follows), why it stays sequential rather than reaching into
`SimulationWorkers` (historical scenario counts are nothing like Monte Carlo's path counts, so
the cross-package coupling would serve no confirmed need), and why a symmetric
precomputed-`List<Money>` overload built "for consistency" with `measure`'s shape was removed
again once `NoOrphanedApiTest` caught that nothing called it.

Reproducible like every other stochastic figure this engine produces: the same seed against
the same scenarios gives the same interval, bit for bit, proven directly in
`HistoricalVaRCalculatorTest`.

**Not in scope:** a configurable bootstrap resample count, and a precomputed-`List<Money>`
overload - both stay out until a real caller needs them.

#### M23 — A trade that carries a position through zero — ✅ done

`PositionLots.apply` always refused a trade that would carry a position through zero - selling
fifteen when long ten is really two trades, closing ten at the old cost basis and opening a
short five at today's price - and nothing above it ever did the actual splitting.

A new private `PortfolioLedger.applySplittingAtZero` does it: detects a through-zero delta
before it reaches `PositionLots`, splits it into a closing quantity (flattening to exactly
zero) and an opening quantity (the remainder), prorates the consideration between the two by
quantity, and calls the *unchanged* `PositionLots.apply` twice.

Deliberately not two `Trade` objects, even though the gap's own wording ("book the two legs
separately") reads like it should be.
[ADR 0012](adr/0012-through-zero-trades-split-at-the-ledger-not-the-trade.md) records why: a
`Trade` arriving at the ledger has already walked its full lifecycle at a venue, under one id,
as one audited execution - splitting that retroactively would edit a sealed history. Minting a
second id would also need the same shared `TradeIdGenerator` instance the previously-fixed G-1
bug already made venues share, for a caller that does not exist.

Proven by direct comparison, not hand-derived numbers: the one-call crossing trade is asserted
to produce identical ledger state to booking the same two legs separately at the same price -
a check that caught a real sign error during implementation before it reached a commit.

**Not in scope:** a second, independently auditable `Trade` for the opening leg - real,
additional design work for the day a caller actually needs one.

### 10.5 Phase 5 — Optional, only if justified

Kafka / distributed Monte Carlo. **Default recommendation: do not build it** — and
document the reasoning. Declining unnecessary complexity, in writing, reads better than
adding it.

---

## 11. Progress and open items

### Delivered

- **Phase 0** — JDK 21 (Corretto 21.0.12) and Maven 3.9.16 installed; 3-module Maven
  build; GitHub Actions CI green on every push.
- **M1** — core value types, typed ids, market conventions. ADRs 0001–0003.
- **M2** — five instruments on a capability-interface model, plus a polymorphism suite that
  tests the architecture rather than any one instrument. ADR 0004.
- **M3** — order book with price-time priority, O(1) cancellation and O(1) top of book,
  eight property tests over randomised order sequences, and JMH benchmarks against a
  linear-scan baseline. ADR 0005, [BENCHMARKS.md](BENCHMARKS.md).
- **Pre-M4 audit** — three real defects found and fixed, two gaps deferred with reasons, and
  the M4–M7 sequence restructured as a vertical slice (§10.1b) in response to what the audit
  showed about integration testing. [KNOWN_GAPS.md](KNOWN_GAPS.md).
- **Second pre-M4 audit** — no correctness bugs; four quality findings, the largest being that
  the order-book property tests reached a book of only fourteen orders. Generator rewritten and
  guarded; an orphaned-API build check added so dead code cannot recur.
- **M4** — vertical slice: immutable market data with composable shocks, the type-keyed pricing
  registry, portfolio valuation, delta by revaluation, a runnable CLI, and a golden-master test.
  Found and fixed an unapplied contract multiplier and two numeric-boundary bugs.
- **M5** — discounted-cashflow pricing for bonds and FX forwards; FX rates in market data.
  Template Method dropped after implementation showed it had no varying step (§6).
  **397 tests green.**
- **M5 audit** — market-data invariants moved onto `MarketDataKey`, DV01 and FX delta added,
  a bond's clean price separated from its dirty one. The instructive finding was not a defect:
  the engine had been measuring interest-rate and currency risk and printing none of it.
  **409 tests green.**
- **M5b** — yield curves, two interpolation schemes, and a bootstrapper validated by the par
  round trip. Pillars are keyed by settlement date rather than tenor, which cost a defect to
  learn (ADR 0006). The whole pricing stack moved onto curves without a single reference value
  changing, because a flat rate is now the one-pillar case of the same type.
  **461 tests green.**
- **Extensibility proof** — a sixth instrument type (interest-rate cap and floor) in one commit
  touching no existing file. See `docs/EXTENSIBILITY.md`. **495 tests green.**
- **M7** — multi-currency valuation, retiring a deferral carried from M4; then lots, cost basis,
  cash and the realised/unrealised split. **529 tests green.**
- **M6** — swap pricing: floating coupons projected at the simple forward rate off the curve.
  All five instrument types now price in one portfolio. Template Method settled for good (§6):
  the second case justified a shared function, not a base class. Found that the stress
  scenario had been silently missing its rates leg since M4. **474 tests green.**

### Settled

OTC vs CLOB split (§A2.1); `BigDecimal`/`double` split (§A2.2); transition table over the
classic State pattern (§6); 3-module build (§4.2); order book at M3 (§10.1); no web
dashboard (§10.4); Spring Boot retained but thin (§10.2).

### Open

1. Any milestone reordering beyond the M3 change in §10.1.
2. ~~Whether `Quantity` should gain a primitive fast path at M3.~~ **Resolved at M3:** the
   book holds `long`, and the argument turned out to be domain rather than performance —
   only exchange-traded instruments reach a CLOB and those trade in whole units. See
   [ADR 0005](adr/0005-order-book-data-structures.md).
