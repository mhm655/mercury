# ADR 0008 — Fire-and-forget order submission, additive to `execute`

**Status:** Accepted
**Milestone:** M17

## Context

`docs/DESIGN_PROPOSAL.md` §5.6 a) describes the matching engine's `THREAD_PER_BOOK` mode as
an LMAX-style single-writer design, expected to beat locking the book because the data
structure is never shared. `docs/BENCHMARKS.md` §6 measured the opposite at M13:
`THREAD_PER_BOOK` was **1.7× slower on one book and 3.8× slower on twelve** than the
`INLINE` default. The cause was identified rather than guessed at: `OrderBookVenue.execute`
returns the trades it produced, so `SingleWriterBookLane.run` wraps every command in a
`FutureTask` and the calling thread parks until the writer thread wakes it — roughly 0.9µs of
handoff per order, more than the matching work itself costs. `docs/KNOWN_GAPS.md` recorded
this as a deliberate non-fix: closing it "would change `ExecutionVenue`'s contract for every
caller in the codebase."

That framing turned out to overstate the cost of fixing it. The measured problem lives
entirely inside `OrderBookVenue`/`SingleWriterBookLane` — `OtcNegotiationVenue` has no
threading model at all, and `ExecutionRouter` is a thin pass-through with no logic of its own
beyond two validations. Nothing about the fix requires touching the shared `ExecutionVenue<I>`
interface.

## Decision

1. **Add `submit`, do not change `execute`.** `BookLane` gains
   `void submit(Runnable work)` alongside the existing `<T> T run(Supplier<T> work)`.
   `SingleWriterBookLane.submit` queues a plain `Runnable` — no `FutureTask`, no `.get()` — and
   returns as soon as it is queued. `LockedBookLane.submit` runs inline under the same lock
   `run` uses, since `INLINE` has no writer thread to hand off to; it is symmetric API, not a
   latency win. `OrderBookVenue.submit(OrderBookInstruction, SimulationClock)` is new,
   `public`, and additive: `execute` and every one of its callers — `ExecutionRouter`,
   `DemoScenario`, `EndToEndDemo`, `TradeLifecycleDemo`, `TuiDemo`, every `execute`-based test —
   are unchanged.
2. **Results arrive only through the event bus.** `submit` returns `void`. Whatever it
   produces reaches a caller the same way `execute` already announces every trade — a
   `TradeExecuted` published from inside `match`, under the lane's exclusivity — never as a
   value handed back from `submit` itself. A caller with nothing subscribed simply does not
   observe what a submitted instruction did.
3. **No back-pressure.** `SingleWriterBookLane`'s command queue stays unbounded, the same
   choice `AsynchronousEventBus` already made (§5.6 c), and for the same reason: bounding it
   means either blocking the submitter — the wait `submit` exists not to impose — or dropping
   work, which needs a stated policy this class does not have. Recorded in `docs/KNOWN_GAPS.md`
   as its own entry rather than left implicit.

## Why additive over redesigning `ExecutionVenue`

A uniform async contract across `ExecutionVenue<I>` was considered and rejected. It would
force `OtcNegotiationVenue` — which has no threading model, no queue, and one `synchronized`
block guarding a credit check — to either fake asynchrony (wrap its synchronous result in an
already-completed future) or become threaded for a problem it does not have. It would also
change `ExecutionRouter`'s return type and ripple through every demo and every
`execute`-based test for a benefit that only exists on `OrderBookVenue` with
`BookConcurrency.THREAD_PER_BOOK`. The narrower, additive shape delivers the entire measured
win (§6) at a fraction of the blast radius, and is a closer reading of what
`docs/BENCHMARKS.md` §6 actually asked for: "closing that gap means an asynchronous submission
path" — a path, not a rewrite of the venue contract every caller in the codebase depends on.

## Alternatives rejected

**A bounded queue with a drop or block policy, built now.** `AsynchronousEventBus` already
carries this same open question, unresolved on purpose (§5.6 c), and for the identical reason:
picking a bound and a policy (block, drop-oldest, drop-newest) without a real caller whose
throughput and burst shape are known is sizing against a guess. The M13 event-bus decision
already set this precedent; `submit` inherits it rather than inventing a second, possibly
inconsistent answer to the same question.

**A `CompletableFuture<List<Trade>>` return from `submit`.** Gives a caller a handle to await
if it wants one, closer to a conventional async API. Rejected because it re-introduces exactly
the thing this change removes: completing a future from the writer thread is cheap, but a
caller that immediately calls `.get()` on it is back to paying the park-and-wake cost this ADR
exists to avoid, and a caller that does not call `.get()` gains nothing a `void` return does
not already give it. The event bus is already Mercury's "something happened, you don't have to
wait for it" channel (M13); a second one purpose-built for this method would duplicate it.

## Consequences

**Good.** Measured, not asserted: `docs/BENCHMARKS.md` §6's M17 section shows `submit` on
`THREAD_PER_BOOK` roughly doubling throughput on one book and clearing the twelve-book figure
too, consistent with the park-and-wake diagnosis. Zero source change for any existing caller of
`execute`.

**Costs.** Two ways into the same lane (`run`, `submit`) rather than one; a reader of
`BookLane` now has to know which one a given caller wants and why. `OtcNegotiationVenue` gets
no equivalent — a caller that wants fire-and-forget OTC negotiation still cannot have one, which
is honest given the venue has no thread to hand work to, but is an asymmetry between the two
venues worth naming rather than leaving implicit.

**Opened, not closed.** The benchmark that proved the win also reproduced an `OutOfMemoryError`
on a sustained twelve-thread synthetic load, because raw enqueue is cheaper than real matching
work and an unthrottled producer eventually outruns the writer. This is not a regression this
ADR introduces so much as a gap it makes reachable for the first time — recorded in
`docs/KNOWN_GAPS.md` next to the identical, already-accepted trade-off for
`AsynchronousEventBus`, not treated as a blocker on shipping this one.
