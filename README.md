# MERCURY

An object-oriented financial trading and risk simulation engine, written in Java 21.

Mercury simulates a simplified financial institution: it models instruments, matches
orders, books trades through a lifecycle, maintains portfolios, prices positions, and
computes risk — including parallel Monte Carlo VaR.

> **Independent educational project.** Inspired by the problem space that real
> capital-markets software operates in. Not a clone of, and not derived from, any
> proprietary system.

---

## Status

**M5b complete** — the engine values a mixed portfolio of equities, options, a bond and an FX
forward against **discount curves bootstrapped from market quotes**, and reports its equity,
interest-rate and currency risk. CI green on every push.

```bash
mvn -q -DskipTests package
java -cp "mercury-app/target/classes:mercury-engine/target/classes" com.mercury.app.Main
```

```
POSITIONS
  INSTRUMENT         QUANTITY     UNIT VALUE     MARKET VALUE  MODEL
  --------------------------------------------------------------------------
  AAPL                   1000       195.5000        195500.00  spot
  MSFT                    250       412.2500        103062.50  spot
  AAPL-C-200               -5      2382.5395        -11912.70  black-scholes
  AAPL-P-180                8      1040.3824          8323.06  black-scholes
  CORP-5Y                 250      1012.3155        253078.87  discounted-cashflow
  FWD-EURUSD                1       423.7203           423.72  discounted-cashflow
  --------------------------------------------------------------------------
  TOTAL                                             548475.45

ACCRUED INTEREST  (unit values above are dirty: clean + accrued)
  INSTRUMENT                CLEAN        ACCRUED          DIRTY
  CORP-5Y               1010.9455         1.3700      1012.3155

DISCOUNT CURVES  (zero rates, continuously compounded, bootstrapped from quotes)
  CURRENCY          1Y        2Y        5Y       10Y
  USD          4.9461%   4.5372%   4.1839%   4.2481%
  EUR          3.2409%   3.0223%   2.9856%   2.9735%

RISK
  DELTA  (value change per unit rise in spot)
    AAPL                     487.9941
    MSFT                     250.0000
  FX DELTA  (value change per unit rise in the rate)
    EUR/USD               484092.3573
  DV01  (value change per +1bp on the discount rate)
    USD                      -71.7505
    EUR                      -51.7767

STRESS  (equities -30%, volatility +50%, FX -10%)
  P&L impact                                       -104888.50
```

Four instrument types, three models, three kinds of risk, and no `instanceof` anywhere in the
dispatch. **Nothing in the demo states a five-year zero rate** — it states deposit and swap
quotes, and the bootstrapper finds the curve that reprices all of them at once.

Every number is explainable, which is the check that the pieces agree with each other. The
covered call and protective put cut AAPL delta from 1000 to 488. The bond prices above par
because five-year discounting at 4.18% is below its 4.5% coupon. The FX delta of 484,092 is
exactly the euro notional discounted on the euro curve.

The forward is the one worth reading twice. Under the flat 4.5% and 3.2% rates this scenario
used before curves, the fair forward rate was 1.0865 and a contract struck at 1.09 was worth
**−1,675.68**. On the real curves the one-year rate differential is 1.71% rather than 1.30%,
which puts the fair rate at 1.0910 — so the same contract is now worth **+423.72**. A sign
flip out of a curve shape, which is exactly the kind of thing a flat rate hides.

The most interesting figure is the USD DV01. It is the bond's contribution, the forward's USD
leg, **and about −10 of option rho** — picked up with no rho formula anywhere in the codebase,
because sensitivities are computed by shocking the market and revaluing rather than by
per-instrument formulas.

Three audits have found five real defects and one weak test suite: a bond that reported itself
matured while still owing its principal, a market-data shock that could build a market the
builder would have refused, property tests that looked thorough while only ever building a
book of fourteen orders. The most instructive one was not a defect at all — the engine had
been measuring interest-rate and currency risk since M5 and printing none of it. All are
written up in [KNOWN_GAPS.md](docs/KNOWN_GAPS.md), along with what was deliberately left
undone.

*(This section used to lead with a test count. It was removed on purpose: the audit showed
the number was uninformative — the eight property tests it was flattering covered almost none
of the state space they were supposed to. What a suite reaches matters; how many assertions
it runs does not.)*

See **[docs/DESIGN_PROPOSAL.md](docs/DESIGN_PROPOSAL.md)** for the full design: domain
model, architecture, the design problems that drive it, justified pattern choices,
anti-patterns being avoided, and the delivery roadmap. Decisions are recorded as
[ADRs](docs/adr) as they are made, not reconstructed afterwards.

| Milestone | Status |
|---|---|
| M1 — Core types, market conventions | ✅ complete |
| M2 — Instruments (stock, bond, FX forward, option, swap) | ✅ complete |
| M3 — Order book + first JMH benchmarks | ✅ complete |
| M4 — Vertical slice: value a portfolio end-to-end | ✅ complete |
| M5 — Discounted cashflows: bonds and FX forwards | ✅ complete |
| M5 audit — invariants on market data, DV01 / FX delta, clean vs dirty | ✅ complete |
| M5b — Curve construction (bootstrapping) | ✅ complete |
| M6 — Swap pricing: floating-leg projection off a curve | next |

Everything from M4 on is in the [roadmap](docs/DESIGN_PROPOSAL.md#10-roadmap).

## Evidence, not claims

"Build a trading engine" is a common project idea. Nothing about the premise is novel,
so this repo is organised around *verifiable* claims rather than a technology list.
Three artifacts, each checkable in about a minute:

| Artifact | Status | What it proves |
|---|---|---|
| **[Benchmarks](docs/BENCHMARKS.md)** | ✅ order book measured | Real JMH numbers on stated hardware — including a prediction of mine that the measurements disproved, reported as a failure rather than deleted |
| Extensibility-proof commit | planned (M15) | A sixth instrument added in a single diff that modifies **zero existing files** — the open-closed claim, demonstrated rather than asserted |
| **[Golden-master test](mercury-app/src/test/java/com/mercury/app/GoldenMasterTest.java)** | ✅ running from M4 | The whole engine is byte-for-byte reproducible from a fixed clock — and it caught a real bug before it was even written |

### Measured so far

Order book, 50,000 resting orders, against a linear-scan baseline
([full results and caveats](docs/BENCHMARKS.md)):

| Operation | This book | Naive `ArrayList` | |
|---|---:|---:|---:|
| Read top of book | **2.43 ns** | 268,237 ns | 110,000× |
| Cancel from mid-book | **64.2 ns** | 133,571 ns | 2,080× |

Top of book measures 2.42 / 2.45 / 2.43 ns at 1,000 / 10,000 / 50,000 orders — flat to
within noise, which is direct evidence the cached-best-level invariant holds.

## Built so far

- **An order book with real data structures.** Price-time priority via a `TreeMap` of
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
- **Restraint, recorded.** The design proposal listed Template Method as justified for the
  discounted-cashflow base. Implementing it showed there was no varying step to override, so
  it was dropped and [the entry struck through](docs/DESIGN_PROPOSAL.md#6-design-patterns--used-and-deliberately-not-used)
  rather than quietly deleted. A build check also fails on any public method nobody calls.
- **One mechanism, three features — the first two working.** Immutable snapshots plus
  composable shocks already drive both stress scenarios and bump-and-revalue risk; Monte
  Carlo reuses the same abstraction at M12. Equity delta, DV01 and FX delta are the same two
  lines with a different shock, which is why adding rate and currency risk cost three short
  methods rather than a risk module.
- **Invariants on the type that owns them.** Each `MarketDataKey` case states what values it
  can take, so the builder and `withShock` cannot disagree about what a legal market is. They
  did: a shock could impose a negative spot price that the builder rejected, and it surfaced
  four layers down as a NaN blaming the pricing model for bad data.
- **Curves fitted, not typed in.** Deposits and par swaps go in; a discount curve comes out,
  solved pillar by pillar and validated by repricing its own inputs to par. Pillars are keyed
  by settlement date rather than tenor, which sounds like pedantry and was not — a 2Y swap
  whose nominal maturity fell on a Sunday paid one day into the next interpolation interval
  and came out 1.5 basis points off par ([ADR 0006](docs/adr/0006-curve-pillars-are-dates.md)).
  A flat rate is now the one-pillar case of the same type, which is how the whole pricing stack
  moved onto curves without a single reference value changing.

## Planned, not yet built

Listed separately on purpose — a README that describes intentions in the present tense is
just a claim.

- **Concurrency chosen per component** (M13). Single-writer matching engine;
  embarrassingly-parallel Monte Carlo over immutable snapshots with reproducible per-task
  RNG splitting.

Deliberate omissions and deferred fixes are listed in
**[KNOWN_GAPS.md](docs/KNOWN_GAPS.md)**, so their absence reads as a decision rather than an
oversight.

## Planned architecture

```
mercury-engine       value objects, market conventions, instruments, pricing,
                     curves, market data, portfolio, matching, risk,
                     trade lifecycle, simulation
mercury-app          CLI / terminal UI, manual dependency-injection wiring
mercury-benchmarks   JMH suites
```

The core engine is framework-independent — no Spring, no persistence, no web server.
Layering is enforced by ArchUnit tests rather than asserted in documentation.

## Roadmap

| Phase | Scope |
|---|---|
| 1 | Pure Java engine — **this is the product** |
| 2 | Thin Spring Boot REST API over the engine (engine unchanged) |
| 3 | PostgreSQL persistence, isolated from the domain model |
| 4 | Terminal UI, generated HTML reports, observability |
| 5 | Distributed compute — only if a requirement justifies it |

The order book ships early (milestone 3), ahead of pricing and portfolio, so the most
technically interesting component exists first. Milestone-level detail is in the
[design proposal](docs/DESIGN_PROPOSAL.md#10-roadmap).

There is deliberately **no web dashboard** on the roadmap; the reasoning is in
[§10.4](docs/DESIGN_PROPOSAL.md#104-phase-4--interface-and-observability).

## Building

Requires JDK 21+ and Maven 3.9+.

```bash
mvn verify
```

That compiles all three modules and runs the full suite: unit tests, jqwik property
tests, and the ArchUnit layering rules. A layering violation fails the build — that is
the point of enforcing architecture in tests rather than asserting it in a README.

Developed and benchmarked against Amazon Corretto 21.0.12 on Windows; CI runs Temurin 21
on Ubuntu.

## License

MIT
