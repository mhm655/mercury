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

## What this project is trying to demonstrate

Not feature count — engineering judgement. Specifically:

- **Open-closed instrument dispatch.** Adding a new instrument type requires one new
  class, one new pricer, and one registration line. No `instanceof` chains, and no
  Visitor (which would break open-closed on the instrument axis). This is the
  Expression Problem, and the tradeoff is documented rather than hidden.
- **One mechanism, three features.** Immutable market-data snapshots plus composable
  shocks power stress testing, bump-and-revalue Greeks, and Monte Carlo simulation —
  so every Greek works for every instrument with a pricer, at zero per-instrument cost.
- **An order book with real data structures.** Price-time priority via `TreeMap` of
  price levels over intrusive linked lists: O(1) cancellation, O(1) best bid/ask,
  benchmarked with JMH at realistic order counts.
- **Concurrency chosen per component, not applied uniformly.** Single-writer matching
  engine (serialize commands, don't lock the book); embarrassingly-parallel Monte Carlo
  over immutable snapshots with reproducible per-task RNG splitting.
- **Honest measurement.** Benchmarks are real, hardware is stated, and sub-linear
  scaling is explained rather than papered over.

## Planned architecture

```
mercury-core         value objects, typed ids, money, time, domain events
mercury-engine       instruments, pricing, market data, portfolio,
                     matching, risk, trade lifecycle, simulation
mercury-app          CLI demo harness, manual dependency-injection wiring
mercury-benchmarks   JMH suites
```

The core engine is framework-independent — no Spring, no persistence, no web server.
Layering is enforced by ArchUnit tests rather than asserted in documentation.

## Roadmap

| Phase | Scope |
|---|---|
| 1 | Pure Java engine — the product |
| 2 | Spring Boot REST API over the engine (engine unchanged) |
| 3 | PostgreSQL persistence, isolated from the domain model |
| 4 | WebSockets, observability, trading/risk dashboard |
| 5 | Distributed compute — only if a requirement justifies it |

Milestone-level detail is in the [design proposal](docs/DESIGN_PROPOSAL.md#9-roadmap).

## Building

Requires JDK 21+ and Maven 3.9+. Build instructions will be added with the first
implementation milestone.

## License

MIT
