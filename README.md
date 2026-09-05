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

**M3 complete** — order book with price-time priority, benchmarked. 266 tests green
across unit, property-based and architecture suites; CI builds on every push.

See **[docs/DESIGN_PROPOSAL.md](docs/DESIGN_PROPOSAL.md)** for the full design: domain
model, architecture, the design problems that drive it, justified pattern choices,
anti-patterns being avoided, and the delivery roadmap. Decisions are recorded as
[ADRs](docs/adr) as they are made, not reconstructed afterwards.

| Milestone | Status |
|---|---|
| M1 — Core types, market conventions | ✅ complete |
| M2 — Instruments (stock, bond, FX forward, option, swap) | ✅ complete |
| M3 — Order book + first JMH benchmarks | ✅ complete |
| M4 — Market data, events, composable shocks | next |

Everything from M4 on is in the [roadmap](docs/DESIGN_PROPOSAL.md#10-roadmap).

## Evidence, not claims

"Build a trading engine" is a common project idea. Nothing about the premise is novel,
so this repo is organised around *verifiable* claims rather than a technology list.
Three artifacts, each checkable in about a minute:

| Artifact | Status | What it proves |
|---|---|---|
| **[Benchmarks](docs/BENCHMARKS.md)** | ✅ order book measured | Real JMH numbers on stated hardware — including a prediction of mine that the measurements disproved, reported as a failure rather than deleted |
| Extensibility-proof commit | planned (M15) | A sixth instrument added in a single diff that modifies **zero existing files** — the open-closed claim, demonstrated rather than asserted |
| Golden-master test | planned (M14) | The entire simulation is byte-for-byte reproducible from a fixed seed and clock |

### Measured so far

Order book, 50,000 resting orders, against a linear-scan baseline
([full results and caveats](docs/BENCHMARKS.md)):

| Operation | This book | Naive `ArrayList` | |
|---|---:|---:|---:|
| Read top of book | **2.43 ns** | 268,237 ns | 110,000× |
| Cancel from mid-book | **64.2 ns** | 133,571 ns | 2,080× |

Top of book measures 2.42 / 2.45 / 2.43 ns at 1,000 / 10,000 / 50,000 orders — flat to
within noise, which is direct evidence the cached-best-level invariant holds.

## What this project is trying to demonstrate

- **Open-closed instrument dispatch.** Adding a new instrument type requires one new
  class, one new pricer, and one registration line. No `instanceof` chains, and no
  Visitor (which would break open-closed on the instrument axis). This is the
  Expression Problem, and the tradeoff is documented rather than hidden.
- **One mechanism, three features.** Immutable market-data snapshots plus composable
  shocks power stress testing, bump-and-revalue Greeks, and Monte Carlo simulation —
  so every Greek works for every instrument with a pricer, at zero per-instrument cost.
  Including an honest account of where that approach is numerically delicate.
- **An order book with real data structures.** Price-time priority via `TreeMap` of
  price levels over intrusive linked lists: O(1) cancellation and O(1) best bid/ask,
  [measured](docs/BENCHMARKS.md) against a naive baseline rather than asserted. Fills
  execute at the *resting* order's price — the rule most often got wrong.
- **Curve construction that actually works.** Bootstrapping a discount curve from quoted
  market instruments by iterative root-finding, validated by repricing its own inputs
  to par — because pricing a swap without a real curve is not pricing a swap.
- **Domain conventions done properly.** Day counts, business-day rolling, holiday
  calendars, schedule generation. Unglamorous, and the clearest tell of whether a
  financial project is real.
- **Concurrency chosen per component, not applied uniformly.** Single-writer matching
  engine (serialize commands, don't lock the book); embarrassingly-parallel Monte Carlo
  over immutable snapshots with reproducible per-task RNG splitting.
- **Honest measurement.** Benchmarks are real, hardware is stated, and sub-linear
  scaling is explained rather than papered over.

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
