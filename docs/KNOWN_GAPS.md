# Known gaps

Deliberate omissions and deferred fixes, recorded so they are choices rather than
oversights. Anything a reviewer might reasonably expect and not find should be here with a
reason and a milestone.

Found during the pre-M4 audit unless noted otherwise.

---

## Deferred to a later milestone

### G-1 · Order ids are reusable after an order fills · → M8

`OrderBook.submit` rejects an id that is **currently resting**, but once an order fills, its
id is free again:

```java
submit(X, sell 100)  // fills completely
submit(X, sell 50)   // accepted - id is no longer resting
```

`Fill` records therefore reference ids that are not unique over time, so a reconstructed
audit trail can be ambiguous about which order a given execution belonged to.

**Why deferred rather than fixed now.** The honest fix is not "remember every id forever" —
that is an unbounded set in a long-running book. Real venues assign their own unique
execution and order identifiers rather than trusting client-supplied ones. That belongs with
the trade lifecycle and audit work at M8, where identifier generation gets designed properly.
Patching it now with a growing `HashSet` would create a memory leak and a false sense of
having solved it.

**Interim risk.** None inside a single simulation run: ids are generated uniquely by the
harness. The gap only matters once an external client supplies ids.

### G-2 · No self-trade prevention · → M8

Two orders from the same participant will happily cross:

```java
submit(A, sell 100 @ 100)
submit(B, buy  100 @ 100)   // fills against A
```

Real venues are required to prevent this — self-matching is wash trading, and regulators
treat it as market manipulation whether or not it was intended.

**Why deferred.** Mercury has no concept of a participant yet. `Counterparty` arrives with
the OTC venue and the risk limits at M8, and self-trade prevention is a one-line check *once
there is an identity to compare*. Adding a participant field to `Order` now, used by nothing,
would be speculative.

---

## Deliberate scope limits

These are not defects and are not scheduled. They are named so their absence reads as a
decision.

| Area | Not modelled | Why |
|---|---|---|
| Bonds | Amortisation, call/put schedules, floating-rate notes, inflation linkage | The vanilla bullet bond already answers the design question (how a cashflow-bearing instrument exposes itself to a generic pricer). The rest is domain surface without architectural gain. |
| Swaps | Cross-currency, basis (float-float), amortising notionals, principal exchange | All are different *compositions* of the existing legs rather than new structures — which is the point of composing legs instead of subclassing. |
| Curves | Dual-curve / OIS discounting | Single-curve is the pre-2008 convention. Real desks discount OIS; we do not, and say so. |
| Order types | Fill-or-kill, good-till-date, stop, iceberg | Each adds a branch in the matching loop and no new insight. Limit, market and IOC cover price-time priority, resting, partial fills and cancellation. |
| Order book | Tick-indexed price array | O(1) for everything and what a real exchange uses, but it assumes a bounded tick grid the simulation does not fix. See ADR 0005. |
| Currencies | Only 7 ISO codes | `Currency` is an enum for exhaustive `switch` and cheap `EnumMap` keys. Adding one is a single line. See ADR 0002. |
| Equities | Dividends | Would change option pricing (the dividend yield term in Black-Scholes). Currently a zero-dividend assumption, to be stated explicitly when pricing lands at M6. |

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
