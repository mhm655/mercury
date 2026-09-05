# MERCURY — Design Proposal (Pre-Implementation)

**Status:** Awaiting approval. No production code written yet.
**Target:** Java 21+, framework-independent core, incremental delivery.

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
is the LMAX-style insight: *serialize commands rather than lock the data structure.* A
lock-per-book design would be slower and far harder to reason about. Deterministic replay
from the command log falls out for free — which is what makes §7.2 possible.

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
| **Builder** | `InterestRateSwap`, `Scenario`, `Bond` | Genuinely many-parameter, many-optional construction. *Not* used for `Stock`, which has three fields |
| **Template Method** | discounted-cashflow pricing base | Bond / FX forward / swap share the DCF skeleton and differ only in cashflow projection |
| **Adapter** | market data feeds | Isolates external formats from the domain (matters in Phase 2) |
| **State machine** | trade lifecycle | See below — with a caveat |

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
  interleavings against the book.
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
| M1 | Core types & market conventions | `Money`, typed ids, `Quantity`, `Currency`, `Tenor`, day count, business-day conventions, `HolidayCalendar`, `ScheduleGenerator`, `SimulationClock` |
| M2 | Instruments | 5 instruments, capability interfaces, builders, `InstrumentFactory` |
| **M3** | **Order book** | **CLOB, price-time priority, partial fills, O(1) cancel + first JMH benchmarks** |
| M4 | Market data & events | Immutable snapshot, `MarketShock` composite, `EventBus` (sync) |
| M5 | Curve construction | `YieldCurve`, interpolation strategies, bootstrapper, par round-trip test |
| M6 | Pricing engine | Registry, DCF template, Black-Scholes, all 5 pricers, reference-value tests |
| M7 | Portfolio | `Position`, `CashAccount`, valuation service, realized/unrealized P&L, exposure |
| M8 | Trade lifecycle & execution | State machine, audit trail, both execution venues, pre-trade pipeline |
| M9 | Risk limits | `RiskLimit` composite, pro-forma projection, breach events, rejection |
| M10 | Risk engine | Bump-and-revalue Greeks, analytic Greeks, **Gamma validation (§5.3.1)**, DV01, exposure, historical VaR |
| M11 | Scenarios / stress | Named scenarios (Market Crash, Rate Shock, Currency Crisis), impact report |
| M12 | Monte Carlo, single-threaded | GBM paths, VaR + Expected Shortfall, convergence tests |
| M13 | Concurrency | Parallel MC, async bus, single-writer books, **scaling benchmarks 1→12 workers** |
| M14 | Harness, terminal UI, golden master | End-to-end demo runnable in one command + **§7.2 determinism test** |
| M15 | Extensibility proof & documentation | **§7.1 sixth-instrument commit**, README, ARCHITECTURE.md, diagrams, benchmark results, ADRs |

**M15 is not optional polish** — for this project's actual goal it is one of the
highest-value milestones. Short ADRs should be written *as we go* rather than
reconstructing rationale at the end; ADRs in a repo are a strong seniority signal.

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
  tests the architecture rather than any one instrument. ADR 0004. **216 tests green.**

### Settled

OTC vs CLOB split (§A2.1); `BigDecimal`/`double` split (§A2.2); transition table over the
classic State pattern (§6); 3-module build (§4.2); order book at M3 (§10.1); no web
dashboard (§10.4); Spring Boot retained but thin (§10.2).

### Open

1. Any milestone reordering beyond the M3 change in §10.1.
2. Whether `Quantity` should gain a primitive fast path at M3, or whether the order book
   holds `long` internally and converts at its boundary — see §3.3's note. Deferred until
   the benchmark shows whether it matters.
