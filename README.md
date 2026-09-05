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

**Design phase.** The architecture is specified but implementation has not begun.

See **[docs/DESIGN_PROPOSAL.md](docs/DESIGN_PROPOSAL.md)** for the full design: domain
model, architecture, the design problems that drive it, justified pattern choices,
anti-patterns being avoided, and the delivery roadmap.

## Evidence, not claims

"Build a trading engine" is a common project idea. Nothing about the premise is novel,
so this repo is organised around *verifiable* claims rather than a technology list.
Three artifacts, each checkable in about a minute:

| Artifact | What it proves |
|---|---|
| Benchmark tables | Real JMH numbers on stated hardware — order-book throughput and Monte Carlo scaling, including where scaling stops being linear and why |
| Extensibility-proof commit | A sixth instrument added in a single diff that modifies **zero existing files** — the open-closed claim, demonstrated rather than asserted |
| Golden-master test | The entire simulation is byte-for-byte reproducible from a fixed seed and clock |

These are planned deliverables, not yet built. They will be linked here as they land.

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
  price levels over intrusive linked lists: O(1) cancellation, O(1) best bid/ask,
  benchmarked with JMH at realistic order counts.
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

Requires JDK 21+ and Maven 3.9+. Build instructions will be added with the first
implementation milestone.

## License

MIT
