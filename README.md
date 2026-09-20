# MERCURY

A trading and risk engine in plain Java 21: it matches orders, books trades against credit
limits, keeps a multi-currency ledger, prices five kinds of instrument off bootstrapped curves,
and reports Greeks, stress scenarios and Monte Carlo VaR - all from a fixed scenario, byte for
byte reproducible.

> **Independent educational project.** Inspired by the problem space that real
> capital-markets software operates in. Not a clone of, and not derived from, any
> proprietary system.

## In thirty seconds

Three claims, each checkable in about a minute, and each one the kind a reader can disprove:

- **A sixth instrument was added without editing a single existing file.** An interest-rate
  cap: one commit, four new files, zero modified — `git show --stat` settles it. That is the
  strongest evidence here that the open-closed pricing dispatch is real rather than asserted
  ([how](docs/EXTENSIBILITY.md)).
- **Three of my own performance predictions were wrong, and all three are still written
  down** next to the measurements that killed them: a cache-friendly baseline I expected to
  win at small book sizes and which won at none, hyperthreads forecast at 15–30% that
  delivered 47%, and a single-writer matching engine that came out 1.7–3.8× *slower* than the
  lock it was built to beat ([the numbers](docs/BENCHMARKS.md)).
- **The engine is byte-for-byte reproducible, and the report further down is its output** —
  not a transcription of it. A test fails if this README drifts from what the jar prints.

```bash
mvn -q -DskipTests package && java -jar mercury-app/target/mercury.jar walkthrough
```

That single command runs the whole engine: orders cross on a book, a bond is negotiated
against a credit limit and a larger trade refused, the fills become a ledger, and the ledger
is valued and risked. Everything below is detail.

## Run it

Requires JDK 21+ and Maven 3.9+. If `java -jar` fails with `UnsupportedClassVersionError`, or
with a message about `jvm.cfg`, the `java` on your PATH is an older or broken install — the
JVM rejects the jar before any code in it runs, so nothing inside can produce a friendlier
message. `java -version` is the check.

```bash
mvn -q -DskipTests package
```

```bash
java -jar mercury-app/target/mercury.jar
```

That prints the report below, and ends by naming the other four commands — the jar tells you
what else it can do, so this table is a reference rather than a prerequisite. `help` lists
them too.

| Command | What it shows |
|---|---|
| *(none)* or `report` | The valuation report: positions, P&L, curves, Greeks, stress scenarios |
| `walkthrough` | The whole engine in one run: orders cross on a book, a bond is negotiated against a credit limit, the trades become a ledger, the ledger is valued and risked |
| `lifecycle` | Trade state machine, self-trade prevention, a credit-limit breach and its release |
| `risk` | Gamma and Vega beside their Black-Scholes closed forms; historical VaR |
| `montecarlo` | Monte Carlo prices converging on Black-Scholes; VaR and Expected Shortfall |

`mvn verify` runs everything else: unit and property tests, the ArchUnit layering rules, and a
test that fails if the report below stops matching what the engine prints.

## The report

This book opened as **1,000,000 of cash**, made eight trades - crossed on a book or negotiated
against a dealer, the same venues the `walkthrough` command runs, not declared by hand - and
holds what they add up to.

```
POSITIONS
  INSTRUMENT         QUANTITY   CCY     UNIT VALUE     MARKET VALUE  MODEL
  --------------------------------------------------------------------------------
  AAPL                   1000   USD       195.5000        195500.00  spot
  MSFT                    250   USD       412.2500        103062.50  spot
  AAPL-C-200               -5   USD      2382.5395        -11912.70  black-scholes
  AAPL-P-180                8   USD      1040.3824          8323.06  black-scholes
  CORP-5Y                 250   USD      1012.3155        253078.87  discounted-cashflow
  FWD-EURUSD                1   USD       423.7203           423.72  discounted-cashflow
  IRS-5Y                    1   USD     11109.2141         11109.21  swap-discounting
  BUND-3Y                 200   EUR       984.4650        211167.73  discounted-cashflow
  --------------------------------------------------------------------------------
  TOTAL                                                   770752.39

EXPOSURE BY CURRENCY  (positions settling in each, valued in USD)
  USD                                                     559584.66
  EUR                                                     211167.73

CASH
  USD                                                     457798.14
  EUR                                                    -198000.00
  NET ASSET VALUE  (positions + cash)                    1016195.53

PROFIT AND LOSS  (FIFO cost basis)
  Realised                                                  2400.00
  Unrealised                                               13795.53
  Total                                                    16195.53

ACCRUED INTEREST  (unit values above are dirty: clean + accrued)
  INSTRUMENT                CLEAN        ACCRUED          DIRTY
  CORP-5Y               1010.9355         1.3800      1012.3155
  BUND-3Y                984.4650         0.0000       984.4650

DISCOUNT CURVES  (zero rates, continuously compounded, bootstrapped from quotes)
  CURRENCY          1Y        2Y        5Y       10Y
  USD          4.9461%   4.5372%   4.1839%   4.2481%
  EUR          3.2409%   3.0223%   2.9856%   2.9735%

RISK
  DELTA  (value change per unit rise in spot)
    AAPL                     487.9941
    MSFT                     250.0000
  GAMMA  (value change per unit^2, the curvature delta alone misses)
    AAPL                       1.3024
    MSFT                       0.0000
  VEGA  (value change per 1 vol point rise)
    AAPL                     136.2162
  FX DELTA  (value change per unit rise in the rate)
    EUR/USD               680985.3499
  DV01  (value change per +1bp on the discount rate)
    USD                      381.4940
    EUR                     -113.5839

SCENARIOS
  Market Crash  (equities -30%, volatility +50%, FX -10%, rates +150bp)
    P&L impact                                      -86093.00
  Rate Shock  (rates +200bp across every currency)
    P&L impact                                       50776.17
  Currency Crisis  (EUR/USD -20%, EUR rates +300bp)
    P&L impact                                     -172506.57
```

Five instrument types, four models, three kinds of risk, two currencies, and no `instanceof`
anywhere in the dispatch. **Nothing in the demo states a five-year zero rate** — it states
deposit and swap quotes, and the bootstrapper finds the curve that reprices all of them at
once. **Nor does it state a position** — it states eight trades, and the positions are what
they add up to.

The strongest number in the report is the one that owes nothing to the code producing it. This
book opened as **1,000,000 of cash and nothing else**, so whatever it has done since, its total
profit must be net asset value minus that. It comes to **16,195.53** both ways — which requires
cost basis, FIFO lot matching, the realised/unrealised split and the FX conversion all to be
right at once.

Every number is explainable, which is the check that the pieces agree with each other. The
covered call and protective put cut AAPL delta from 1000 to 488. The bond prices above par
because five-year discounting at 4.18% is below its 4.5% coupon. The FX delta of 680,985 is
exactly the book's euro exposure: the forward's euro notional discounted on the euro curve
(484,092) plus the Bund's euro value (200 × 984.4650 = 196,893). The swap is worth 11,109 because it
pays 4.00% fixed when the five-year par rate is 4.25%, over an annuity of 4.44.

**The USD DV01 is positive.** Every other position in the book — the bond, both option legs,
the forward's dollar leg — loses value when rates rise. One payer swap on a million of notional
outweighs all of them, so the portfolio is short rates while five of its seven positions are
long. No single line of the report shows that; only the aggregate does, which is the argument
for computing risk at portfolio level rather than summing per-instrument numbers.

That DV01 also carries **about −10 of option rho**, picked up with no rho formula anywhere in
the codebase, because sensitivities are computed by shocking the market and revaluing.

Two figures worth reading twice. The FX forward was worth **−1,675.68** under the flat rates
this scenario used before curves and is worth **+423.72** on the real ones — a sign flip out of
nothing but curve shape, because the one-year rate differential is 1.71% rather than 1.30%. And
the stress number moved from −104,888.50 to −56,815.17 when the scenario's **rates leg was
restored**: it had been specified in the design document since M4 and quietly missing from the
code, which nobody noticed while the book held no material rate risk
([D-1](docs/KNOWN_GAPS.md)).

Four audits have found six real defects and one weak test suite: a bond that reported itself
matured while still owing its principal, a market-data shock that could build a market the
builder would have refused, property tests that looked thorough while only ever building a
book of fourteen orders. The most instructive one was not a defect at all — the engine had
been measuring interest-rate and currency risk since M5 and printing none of it. All are
written up in [KNOWN_GAPS.md](docs/KNOWN_GAPS.md), along with what was deliberately left
undone.

The sixth is the smallest and the most uncomfortable, because the report above carried it in
print for eleven milestones. `CORP-5Y` accrues 1000 × 4.5% × 11/360, which is exactly 1.375
and rounds half-even to **1.38**. It printed **1.37** — the year fraction was evaluated into a
`double` first, making the product 1.374999999999999975, and a cent fell off a settlement
figure because of which way a binary approximation landed. One cent is not the point.
[ADR 0001](docs/adr/0001-bigdecimal-for-ledger-double-for-models.md) exists to keep exactly
this out of amounts that change hands, and `Money.fromModelValue` is the boundary it is
supposed to cross at — a boundary whose own javadoc named `Bond` as a caller when `Bond` had
never called it. The rule was right, the doctrine was documented, and four cashflow methods
quietly went around it. **A convention only holds where something checks it**: the golden
master had frozen the wrong cent as correct, so no test could ever have noticed.

*(This section used to lead with a test count. It was removed on purpose: the audit showed
the number was uninformative — the eight property tests it was flattering covered almost none
of the state space they were supposed to. What a suite reaches matters; how many assertions
it runs does not.)*

See **[docs/DESIGN_PROPOSAL.md](docs/DESIGN_PROPOSAL.md)** for the full design: domain
model, architecture, the design problems that drive it, justified pattern choices,
anti-patterns being avoided, and the delivery roadmap. **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**
is the map of the finished thing instead - modules, package layers and the two or three
mechanisms that do most of the work, each pinned to the test that keeps it true. Decisions are
recorded as [ADRs](docs/adr) as they are made, not reconstructed afterwards.

| Milestone | Status |
|---|---|
| M1 — Core types, market conventions | ✅ complete |
| M2 — Instruments (stock, bond, FX forward, option, swap) | ✅ complete |
| M3 — Order book + first JMH benchmarks | ✅ complete |
| M4 — Vertical slice: value a portfolio end-to-end | ✅ complete |
| M5 — Discounted cashflows: bonds and FX forwards | ✅ complete |
| M5 audit — invariants on market data, DV01 / FX delta, clean vs dirty | ✅ complete |
| M5b — Curve construction (bootstrapping) | ✅ complete |
| M6 — Swap pricing: all five instrument types | ✅ complete |
| Extensibility proof — a sixth instrument, zero files modified | ✅ complete |
| M7 — Full portfolio: multi-currency, cash, cost basis, P&L | ✅ complete |
| M8 — Trade lifecycle, venues, counterparties | ✅ complete |
| M9 — Risk limits | ✅ complete |
| M10 — Risk engine: Gamma, Vega, analytic cross-validation, historical VaR | ✅ complete |
| M11 — Scenarios / stress: named scenarios, impact report | ✅ complete |
| M12 — Monte Carlo, single-threaded: GBM paths, VaR + Expected Shortfall, convergence tests | ✅ complete |
| M13 — Concurrency: parallel Monte Carlo, event bus, single-writer books, scaling benchmarks | ✅ complete |
| M14 — Harness: the golden-master book trades through the real venues, not by hand | ✅ complete |
| M15 — Extensibility proof & architecture documentation | ✅ complete |

What each milestone delivered, and what its reviews found, is in the
[milestone log](docs/MILESTONES.md). Everything from M4 on is in the
[roadmap](docs/DESIGN_PROPOSAL.md#10-roadmap).

## Evidence, not claims

"Build a trading engine" is a common project idea. Nothing about the premise is novel,
so this repo is organised around *verifiable* claims rather than a technology list.
Three artifacts, each checkable in about a minute:

| Artifact | Status | What it proves |
|---|---|---|
| **[Benchmarks](docs/BENCHMARKS.md)** | ✅ order book, Monte Carlo scaling, venue concurrency | Real JMH numbers on stated hardware — including three predictions of mine that the measurements disproved (a cache-friendly baseline that never won, hyperthreads that beat the forecast, and a single-writer engine that lost to the lock it was supposed to beat), each reported as a failure rather than deleted |
| **[Extensibility proof](docs/EXTENSIBILITY.md)** | ✅ one commit, 4 files, 0 modified | An interest-rate cap added in a single commit that edits **nothing** — verify with `git show --stat`. It pays a kind of cashflow the engine had never seen, and cap-floor parity checks it against the swap model, which knows nothing about caps |
| **[Golden-master test](mercury-app/src/test/java/com/mercury/app/GoldenMasterTest.java)** | ✅ running from M4, trading its own book since M14 | The whole engine is byte-for-byte reproducible from a fixed clock — and it caught a real bug before it was even written. Its eight trades cross on the same venues `walkthrough` runs, not a hand-declared parallel set of positions. It also fails if the report in this README drifts from what the engine prints |
| **[End-to-end walkthrough](mercury-app/src/main/java/com/mercury/app/EndToEndDemo.java)** | ✅ `walkthrough` | Orders cross on the book, a bond is negotiated against a credit limit and a larger trade refused, only the book's own trades are booked, and that ledger is valued and risked — one run, no hand-declared positions |
| **[Trade lifecycle demo](mercury-app/src/main/java/com/mercury/app/TradeLifecycleDemo.java)** | ✅ `lifecycle` | Two participants cross on the order book, a same-owner crossing gets blocked with the fact printed rather than inferred, an OTC trade is negotiated against a named counterparty, one trade is walked to `SETTLED` and booked into a `PortfolioLedger`, and a trade that would breach a counterparty's credit limit is rejected with the breach printed rather than silently dropped |
| **[Risk engine demo](mercury-app/src/main/java/com/mercury/app/RiskEngineDemo.java)** | ✅ `risk` | Gamma and Vega printed side by side against their Black-Scholes closed forms, then a 90% historical VaR over the full demo book across ten hardcoded historical daily scenarios |
| **[Monte Carlo demo](mercury-app/src/main/java/com/mercury/app/MonteCarloDemo.java)** | ✅ `montecarlo` | A Monte Carlo option price visibly converging on the Black-Scholes answer as path count rises (92 → 23 → ~3 dollars of error), then Monte Carlo VaR and Expected Shortfall on the demo book's AAPL exposure |

### Three predictions the measurements killed

A benchmark suite that only ever confirms its author is not evidence of much. These were
written down before the runs, in ADRs and in the design proposal, and are kept beside the
numbers that contradicted them rather than quietly edited out:

| I predicted | It measured |
|---|---|
| A naive `ArrayList` would beat the indexed book at small sizes, because a contiguous scan is cache-friendly — and said that if it never won, my baseline was a strawman | It won at no size. Even at 1,000 orders the indexed book is 39× faster. The reasoning was miscalibrated, not wrong in kind: cache friendliness buys maybe 10×, and interior cancellation does ~250× more work. The crossover is below what this harness can measure, which is [reported as unmeasured rather than absent](docs/BENCHMARKS.md) |
| Hyperthreads would add 15–30% beyond 6 physical cores | They added **47%** |
| A single-writer matching engine would beat a per-book lock | It lost: **1.7× slower on one book, 3.8× slower on twelve**, while callers still block for their trades. The losing design is [kept anyway](#dead-weight-looked-for-on-purpose) — deleting it would delete the evidence for the default |

All three corrections sit in the [design proposal](docs/DESIGN_PROPOSAL.md#56-concurrency--three-deliberate-models-not-add-threads)
beside the original claims, not instead of them, and in full in
[BENCHMARKS.md](docs/BENCHMARKS.md).

### Measured so far

Order book, 50,000 resting orders, against a linear-scan baseline
([full results and caveats](docs/BENCHMARKS.md)):

| Operation | This book | Naive `ArrayList` | |
|---|---:|---:|---:|
| Read top of book | **2.43 ns** | 268,237 ns | 110,000× |
| Cancel from mid-book | **64.2 ns** | 133,571 ns | 2,080× |

Top of book measures 2.42 / 2.45 / 2.43 ns at 1,000 / 10,000 / 50,000 orders — flat to
within noise, which is direct evidence the cached-best-level invariant holds.

Concurrency, on 6 physical cores / 12 threads:

| Measurement | 1 worker | 12 workers | |
|---|---:|---:|---:|
| Monte Carlo option price, 1M paths | 25.87 ms | **4.36 ms** | 5.93× |
| Monte Carlo VaR, 50k paths | 156.6 ms | 61.1 ms | 2.56× (flat from 4 workers) |
| Venue throughput, 12 callers | 900.7 ops/ms *(1 book)* | **2948.3 ops/ms** *(12 books)* | 3.27× |

Every Monte Carlo figure is the same number to the last bit at every worker count. The VaR
plateau is an allocation ceiling rather than the parallel structure, and the
[write-up](docs/BENCHMARKS.md) shows the GC profile that says so.

## Built so far

- **An order book with real data structures**, now actually wired to a portfolio. M8 added
  `ExecutionVenue`/`OrderBookVenue`/`OtcNegotiationVenue` (`com.mercury.execution`) and a
  `Trade` lifecycle (`com.mercury.trade`) that turns a `Fill` into a booked ledger entry via
  `PortfolioLedger.book(Trade)`. The `walkthrough` command runs that whole path - venue, trade,
  ledger, valuation, risk - in one go, and since M14 the report above's own eight trades run
  through the same venues rather than being declared as ledger facts by hand - a market maker
  resting at the exact price this scenario always used for the five exchange-traded trades, a
  dealer negotiated against for the two options, the forward and the swap, with
  `SimulationClock.advancing` moving the book's own history forward one trade date at a time.
  Price-time priority via a `TreeMap` of
  price levels over intrusive linked lists: O(1) cancellation and O(1) best bid/ask,
  [measured](docs/BENCHMARKS.md) against a naive baseline rather than asserted. Fills
  execute at the *resting* order's price — the rule most often got wrong.
- **Domain conventions done properly.** Day counts, business-day rolling, composable
  holiday calendars, schedule generation rolled backwards from maturity. Unglamorous, and
  the clearest tell of whether a financial project is real.
- **Exact money, approximate models, one boundary between them.** `BigDecimal` for ledger
  facts, `double` for model output, and a single named crossing point
  ([ADR 0001](docs/adr/0001-bigdecimal-for-ledger-double-for-models.md)).
- **Capability-based instruments.** Five instrument types that opt into what they can
  actually do; a stock implements no capability at all
  ([ADR 0004](docs/adr/0004-capability-interfaces-and-the-cashflow-boundary.md)).
- **Architecture enforced by tests.** ArchUnit rules fail the build on a layering
  violation, a stray clock read, or a public method nobody calls — rather than the README
  asserting none of that happens.
- **Open-closed pricing dispatch.** A type-keyed registry: a new instrument costs one class,
  one model and one registration line. `PricingServiceTest` proves it by adding a sixth
  instrument type inline and pricing it alongside the rest, with nothing existing modified.
- **Restraint, recorded — and then settled.** The design proposal listed Template Method as
  justified for the discounted-cashflow base. Implementing it showed there was no varying step
  to override, so it was dropped and [the entry struck through](docs/DESIGN_PROPOSAL.md#6-design-patterns--used-and-deliberately-not-used)
  rather than quietly deleted. M6 brought the predicted second case — a floating swap leg,
  which genuinely does need projection before discounting — and it justified extracting a
  four-line static function, not a base class. Waiting did not vindicate the pattern; it showed
  the pattern was never the right shape. A build check also fails on any public method nobody
  calls.
- **One mechanism, three features — the first two working.** Immutable snapshots plus
  composable shocks already drive both stress scenarios and bump-and-revalue risk; Monte
  Carlo reuses the same abstraction at M12. Equity delta, DV01 and FX delta are the same two
  lines with a different shock, which is why adding rate and currency risk cost three short
  methods rather than a risk module.
- **Invariants on the type that owns them.** Each `MarketDataKey` case states what values it
  can take, so the builder and `withShock` cannot disagree about what a legal market is. They
  did: a shock could impose a negative spot price that the builder rejected, and it surfaced
  four layers down as a NaN blaming the pricing model for bad data.
- **A book with a history, not just a snapshot.** Lots, cost basis under FIFO, LIFO or average
  cost, cash per currency, and profit split into the part already taken and the part still at
  risk. Buy 100 at 50, buy 100 at 90, sell 100 at 100 and the answer is 5,000, 1,000 or 3,000
  depending on the method — all three correct, all three asserted.
- **Multi-currency valuation.** A euro bond is priced on the euro curve and converted at the
  end, never discounted at a dollar rate. Deferred from M4 through M6 and finally closed.
- **All five instruments, one dispatch.** Stock, bond, FX forward, European option and
  interest-rate swap, priced by four models through a type-keyed registry. Adding the swap at
  M6 cost one registration line and changed nothing else in the pricing stack.
- **Curves fitted, not typed in.** Deposits and par swaps go in; a discount curve comes out,
  solved pillar by pillar and validated by repricing its own inputs to par. Pillars are keyed
  by settlement date rather than tenor, which sounds like pedantry and was not — a 2Y swap
  whose nominal maturity fell on a Sunday paid one day into the next interpolation interval
  and came out 1.5 basis points off par ([ADR 0006](docs/adr/0006-curve-pillars-are-dates.md)).
  A flat rate is now the one-pillar case of the same type, which is how the whole pricing stack
  moved onto curves without a single reference value changing.
- **Concurrency chosen per component, then measured.** Three models, because the components
  genuinely differ: Monte Carlo is embarrassingly parallel over immutable snapshots, with
  random streams split per fixed-size block so the same seed gives a **bit-identical** answer
  on one worker or twelve; the event bus is synchronous by default and asynchronous by
  choice; each order book has one writer, either the calling thread under a per-book lock or a
  thread of its own fed from a command queue. The benchmarks then disagreed with the design
  doc twice - hyperthreads gave 47% where 15–30% was predicted, and the single-writer engine
  came out *slower* than the lock while callers still block for their trades. Both corrections
  are [in the design proposal](docs/DESIGN_PROPOSAL.md#56-concurrency--three-deliberate-models-not-add-threads)
  beside the original claims, not instead of them.

### Dead weight, looked for on purpose

A build check fails on any public method nobody calls, in both modules. That catches unused
API and cannot see the larger problem, so as of M7 the audits also look for abstractions built
ahead of any consumer — and label the ones being kept:

For two milestones that claim was **not true of the engine**, and the way it failed is worth
keeping. The rule exempts record accessors, and the engine's copy tested only whether a
no-argument method's name matched a field — never whether the class was a record. Since the
house style is `private final T foo` beside `public T foo()`, that exempted every getter in
the module: 61 methods, of which 2 were actual record accessors. The app module's copy had
already been tightened with `isRecord()` after a planted orphan slipped through it; the
engine's copy never received the same fix, and nothing compared the two. Closing it found 14
genuinely dead methods, all deleted. **A check that is believed to cover everything is worth
less than one that is known to cover something** — the same lesson the app-module rule was
added for, arriving a second time because a fix was applied to one copy of a duplicated rule.

| | Standing |
|---|---|
| `HasUnderlying`, `OptionTerms` | One implementor each, and callers use the concrete type. Kept for a second option type, deleted if one doesn't arrive |
| `RiskLimit.and()` / `.composite()` | Composite machinery, real and tested (`RiskLimitTest`), but only one leaf (`CounterpartyExposureLimit`) exists in production wiring - nothing actually composes two limits together yet. Kept for the reason `MarketShock`'s composite shape was kept before M11 gave it a second real user: a second `RiskLimit` type is an expected addition, not a hypothetical one |
| `AsynchronousEventBus` | Built, tested and documented at M13, and nothing in production wiring uses it: a deterministic demo and a golden-master test both want their subscribers finished before the next line runs. Kept because the live simulation is what it was built for, and because the comparison in `docs/BENCHMARKS.md` §7 - the synchronous default costs a slow subscriber's full latency, the asynchronous one costs a flat ~0.24 µs regardless, roughly 17,000× apart against a millisecond-scale subscriber - needed the real implementation to measure against, not a hypothetical one |
| `BookConcurrency.THREAD_PER_BOOK` | The single-writer matching engine, exercised by tests and the benchmark, with `INLINE` wired everywhere in production - because the benchmark says single-writer is slower while callers block for their trades. Kept as the thing that makes one-book-one-owner real, and as the measurement's own subject: deleting it would delete the evidence for the default |

Three rows this table used to carry are gone. Two because they stopped being true, not
because they were deleted unread: the `matching` package (979 lines with no production
caller, at M7) was connected at M8, and `SimulationClock` ("unused until the M14 harness",
at M7) has been production infrastructure since M8 - every `Trade` transition and both
execution venues take one. The third, `assetClass()`, went the other way: implemented by all
six instruments and read by nothing but tests through M12, it was deleted as this table said
it would be (see the amendment to [ADR 0004](docs/adr/0004-capability-interfaces-and-the-cashflow-boundary.md)). Leaving either row in place after the code caught up would itself have been the exact
failure this table exists to prevent: a claim about the code that stopped being checked
against it.

Each live entry is labelled in its own javadoc too, so the note is where the reader is, not
only here.

## Planned, not yet built

Listed separately on purpose — a README that describes intentions in the present tense is
just a claim.

- **An asynchronous submission path** for the matching engine. M13 measured why single-writer
  books cost more than they return while every caller waits for its trades — see
  [KNOWN_GAPS](docs/KNOWN_GAPS.md). The event half is built; the fire-and-forget half would
  change `ExecutionVenue`'s contract, so it waits for a caller that needs it.

Deliberate omissions and deferred fixes are listed in
**[KNOWN_GAPS.md](docs/KNOWN_GAPS.md)**, so their absence reads as a decision rather than an
oversight.

## Roadmap

**The pure Java engine is the product, and it is finished.** Not a phase one with four phases
pending — everything this page claims is about the engine, and `mvn verify` checks all of it.

Later phases are drawn up and deliberately not built: a thin Spring Boot API over an unchanged
engine, PostgreSQL persistence isolated from the domain model, richer output. None of them
would make the engine better at what it does; they would put it behind a network, a schema and
a template. They live in the [design proposal](docs/DESIGN_PROPOSAL.md#10-roadmap) rather than
here, so that this page describes what exists rather than what is intended. There is
deliberately **no web dashboard** among them; the reasoning is in
[§10.4](docs/DESIGN_PROPOSAL.md#104-phase-4--interface-and-observability).

One ordering decision is worth keeping visible. The order book shipped at M3, ahead of pricing
and portfolio, so the most technically interesting component existed first — at the cost of it
standing apart from everything else for four milestones, with 979 lines and no production
caller. M8 connected it, and the row this README used to carry about it is gone from the
dead-weight table because it stopped being true, not because it was quietly dropped.

## Building

```bash
mvn verify
```

That compiles all three modules and runs the full suite: unit tests, jqwik property
tests, the golden master, and the ArchUnit layering rules. A layering violation fails the build — that is
the point of enforcing architecture in tests rather than asserting it in a README.

Developed and benchmarked against Amazon Corretto 21.0.12 on Windows; CI runs Temurin 21
on Ubuntu.

## License

MIT - see [LICENSE](LICENSE).
