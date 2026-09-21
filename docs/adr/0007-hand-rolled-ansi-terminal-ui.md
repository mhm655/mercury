# ADR 0007 — Terminal UI: hand-rolled ANSI, replaying a stepped scenario

**Status:** Proposed
**Milestone:** M16 (planned)

## Context

§10.4 of the design proposal names a terminal UI rendering the live order book, blotter,
P&L and risk as the preferred Phase 4 interface — ahead of a static HTML report, and
instead of a web dashboard, on the reasoning that a mediocre frontend contaminates a
reviewer's read of a backend they have not inspected yet. Building it raises two
questions the design proposal did not settle: what "live" can mean for an engine with no
market feed, and what a terminal UI is built *out of*.

**There is nothing to render live in the literal sense.** `SimulationClock` only advances
when a caller calls `advanceTo`/`advanceDays` — nothing in the engine ticks on its own.
The only thing a UI can show moving is the same fixed demo scenario `EndToEndDemo` already
narrates, replayed one step at a time instead of printed as one pass.

**The event surface is narrower than "live order book, blotter, P&L and risk" implies.**
`EventBus` carries exactly one event type today, `TradeExecuted`, published from
`OrderBookVenue` and `OtcNegotiationVenue`. A rejected negotiation (`NegotiationResult`) and
a limit breach (`LimitBreach`) are return values, not events — a blotter subscribed to the
bus alone will not show them.

**Every dependency in this repository today is test or build tooling** — JUnit, AssertJ,
jqwik, ArchUnit, JMH. There has never been a runtime dependency, and the README's own
first line calls this "a trading and risk engine in plain Java 21."

## Decision

1. **Replay, not simulate.** The UI steps through the same deterministic order/negotiation
   sequence the existing demos narrate, advancing `SimulationClock.Advancing` itself
   between steps. "Live" means *stepped interactively*, not *streamed from a market
   generator the engine does not have*. Determinism — the property the golden master and
   the README's "byte for byte reproducible" claim both depend on — is unaffected, because
   nothing new is invented for the market to do.
2. **Hand-rolled ANSI, not a library.** Screen clearing, cursor positioning and basic
   color come from writing the escape sequences directly, not from Lanterna or an
   equivalent. The drawing surface is a handful of panels redrawn on each step — order
   book depth, a blotter, a P&L/risk block — not a windowing toolkit's worth of layout,
   focus and resize handling.
3. **The renderer lives entirely in `mercury-app`.** `Main`'s own javadoc already states
   the boundary this decision extends: "only this and the demos it dispatches to know a
   console exists." `mercury-engine` gains no method, event type or interface for this
   feature alone to consume — the UI is built from what `OrderBook.depth(...)`,
   `PortfolioValuationService.value(...)`, `SensitivityCalculator` and the event bus
   already expose.

## Why hand-rolled over a library

- **The claim it would cost.** "Plain Java 21" is stated first in the README and is
  checkable the same way every other claim in this project is meant to be — against
  `pom.xml`. A terminal-UI library would be true in every module *except* the one a reader
  is most likely to run first.
- **The size of the actual job.** A handful of panels, redrawn on a step, is cursor math
  and string formatting — not enough surface to need a toolkit's resize/focus/layout
  machinery.
- **Consistency with everything else here.** The order book, the curve bootstrapper and
  the day-count conventions were all built rather than imported, on the stated reasoning
  that unglamorous domain work done properly is "the clearest tell of whether a financial
  project is real." A rendering layer is the same kind of unglamorous work, just outside
  the domain rather than inside it.

## Alternatives rejected

**Lanterna or an equivalent full-screen library.** Less drawing code to write and
resize/redraw handled for you — rejected because the surface it would save work on is
small, and it would be the one dependency in the entire codebase that exists for
convenience rather than necessity.

**An auto-advancing "live" simulation** (a market-data generator ticking on its own).
Rejected: the design proposal never asked for one, it would be new market-generation logic
with no test coverage of its own, and it risks the byte-for-byte reproducibility the
golden master exists to guarantee — a UI that free-runs the market is a UI whose output
depends on wall-clock timing.

**Building the static HTML report (§10.4's second-choice alternative) first or instead.**
Deferred, not rejected. It is cheaper and orthogonal — nothing about it depends on this
decision — so it remains available as a separate, later piece of work.

## Consequences

**Good.** No new dependency, at any point in the tree. The view-model that assembles a
render frame from book/ledger/valuation state is ordinary data and is testable the usual
way; only the final ANSI-writing step is not, and that step stays as thin as `Main`'s
existing console-facing methods.

**Costs.** Cursor positioning, redraw-without-flicker and terminal-capability handling are
written by hand instead of delegated. ANSI escape processing needs to be confirmed on the
terminals this project already documents building against — Windows Terminal and
PowerShell 7+ enable it by default; legacy `cmd.exe` may not — which belongs in the
README's troubleshooting section alongside the existing JDK-version note, the same shape
of problem for the same reason: code that runs correctly can still fail to reach the
reader.

**Left open, deliberately.** Whether the blotter gains new event types for rejections and
breaches, and how often VaR is recomputed against the panel's redraw cadence, are M16
implementation decisions, not architecture — they belong in the milestone write-up once
M16 ships, not in this ADR.
