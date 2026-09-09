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
| Swaps | Historical index fixings | A floating period already under way fixed its rate at the start, and Mercury stores no past fixings — so a seasoned swap is **refused**, not approximated (see E-2). An index fixing is market data, and this engine treats missing market data as an error rather than a zero. A fixing store is bookkeeping rather than a design question and belongs with the trade lifecycle at M8. |
| Swaps | Cross-currency, basis (float-float), amortising notionals, principal exchange | All are different *compositions* of the existing legs rather than new structures — which is the point of composing legs instead of subclassing. |
| Curves | Dual-curve / OIS discounting | Single-curve is the pre-2008 convention. Real desks discount OIS and project on a separate index curve; the basis between them is itself a quoted market. We are single-curve and say so. |
| Curves | Futures quotes, and their convexity adjustment | The bootstrapper takes deposits and par swaps, which cover the whole curve. Futures are the third common input and need a convexity adjustment — a genuinely subtle correction — for no new design question. |
| Curves | Spline interpolation | Deliberate, not missing. A spline removes the kink at each pillar and can produce negative forward rates between them, which is an arbitrage the input quotes never contained. Flat-forward keeps the kink and stays arbitrage-free. See ADR 0006. |
| Curves | Key-rate risk | The shock family can express a one-pillar bump, and nothing asks for one yet. DV01 is a parallel shift; key-rate duration is the same mechanism with a narrower selector, and arrives when the risk engine needs it. |
| Order types | Fill-or-kill, good-till-date, stop, iceberg | Each adds a branch in the matching loop and no new insight. Limit, market and IOC cover price-time priority, resting, partial fills and cancellation. |
| Order book | Tick-indexed price array | O(1) for everything and what a real exchange uses, but it assumes a bounded tick grid the simulation does not fix. See ADR 0005. |
| FX | Triangulation through a vehicle currency | `fxRate(from, to)` consults the pair and its inverse, nothing else, so GBP to USD fails even when GBP/EUR and EUR/USD are both present. A cross rate needs a stated vehicle currency and a rule for which crosses are legal; inferring one silently would value a book against a rate nobody quoted. Fails loudly today. |
| Currencies | Only 7 ISO codes | `Currency` is an enum for exhaustive `switch` and cheap `EnumMap` keys. Adding one is a single line. See ADR 0002. |
| Equities | Dividends | Would change option pricing (the dividend yield term in Black-Scholes). Currently a zero-dividend assumption, to be stated explicitly when pricing lands at M6. |

---

## Fixed during the M6 audit

### E-1 · `parRate` reported the wrong sign below zero · fixed

`SwapModel.parRate` normalised the floating leg with `Math.abs`, to make the answer
independent of which way round the swap is traded. That looks equivalent to normalising by
direction and is not.

A floating leg's present value carries **two** signs: one from whether the holder receives it,
and one from the curve. Above zero they agree, so `abs` happened to give the right answer.
Below zero they do not — a received floating leg on a negative curve is worth less than
nothing — and `abs` kept the wrong one:

| | |
|---|---:|
| Market 5Y par swap quote | **−0.30%** |
| `parRate()` reported | **+0.30%** |
| PV of a swap struck at the reported rate | **−302,828.70** on 10,000,000 notional |
| PV at the true par rate | 0.04 |

Three per cent of notional, out of a number that looks exactly like a rate.

**Why nothing caught it.** Every swap test used a positive-rate curve.
`CurveBootstrapperTest` did exercise a negative-rate market — but only against the *curve*,
never against the swap model built on top of it. Coverage of a component is not coverage of
its callers.

**Why it matters more than an ordinary sign bug.** The engine explicitly advertises this
market condition. `MarketDataKey.ZeroRate` says negative rates are permitted because
"rejecting them would encode a market condition as a validation rule". A capability the
documentation claims and one method silently breaks is worse than one that was never offered.

**The fix.** Normalise by `PayReceive`, not by magnitude. The direction sign is removed
deliberately; the curve's sign is left alone.

### E-2 · A seasoned swap was priced with a rate that did not match its accrual · fixed

The first version handled a floating period already under way by clamping: project the rate
from the valuation date to the period end, then accrue that rate over the period. Those are
two different lengths of time.

On a swap six weeks into a three-month period, it charged a **48-day rate for 92 days of
accrual**:

```
period            [2024-05-15 -> 2024-08-15]   tau = 0.2556 (ACT/360)
rate projected    2024-06-28 -> 2024-08-15     covering only 0.1333
coupon accrued    over the full 0.2556
```

The error is small on a flat front end and grows with its slope — invisible either way, and
worth tens of thousands on a ten-million notional.

**Why it now refuses rather than approximating better.** Every alternative that returns a
number invents one. Accruing over the remaining stub alone silently discards interest already
earned; assuming the index fixed at today's equivalent-tenor rate makes up a figure that is a
matter of public record. Both produce a valuation indistinguishable from a correct one.

An index fixing is market data. The snapshot does not hold it. The engine's existing rule for
that case is `MissingMarketDataException` — loud beats plausible — and `MissingFixingException`
now follows it. Swaps valued on or before their start date, which is every swap at the moment
it is traded, are unaffected.

---

## Fixed during M6

### D-1 · The stress scenario had been missing its rates leg since M4 · fixed

`DESIGN_PROPOSAL.md` §5.3 specifies the market-crash scenario as equities −30% **and**
volatility +50% **and** FX −10% **and rates +150bp**. The implementation had three of the four:

```java
MarketShock crash = MarketShock.scaleAllSpots(0.70)
        .and(MarketShock.scaleAllVolatilities(1.50))
        .and(MarketShock.scaleAllFxRates(0.90));   // and nothing for rates
```

**Why it went unnoticed.** Until M5 the book was equities and options, which have almost no
rate sensitivity, so the missing leg moved the answer by a few dollars. A bond, an FX forward
and a swap made it expensive: the headline number went from −104,888.50 to −56,815.17 once the
rates leg was added — the scenario had been overstating the loss by 46%.

**Why this is the worst kind of gap.** A stress test that silently omits a factor is more
dangerous than one that is absent, because it produces a number people act on. Nothing failed,
nothing warned, and the output looked exactly as authoritative as it does now.

**The fix.** The leg, and a golden-master test asserting the scenario names every factor it
shocks — so the label and the shock cannot drift apart again.

The number reconciles: rates alone contribute +47,661.76, the other three −104,888.50, and the
interaction between them +411.57 (the options' rho changes once spot has fallen 30%). First
order, DV01 × 150bp would predict +49,457.59; the −1,795.83 difference is convexity over a
move that large, which is about the right size for a book of duration 4.4.

---

## Fixed during the M5 audit

### C-1 · `withShock` could build a market the builder would refuse · fixed

`MarketDataSnapshot.Builder` rejected a non-positive spot price. `withShock` — the only other
way to create a snapshot — validated nothing, so a shock could impose one anyway:

```
builder rejects spot 0.0:  Spot price for AAPL must be positive, but was 0.0
withShock produced:        spot(AAPL) = -195.5
```

The negative spot then reached Black-Scholes and failed four layers down with
`N(x) is undefined for NaN`, naming neither the instrument, nor the observation, nor the
shock. Worse, `ValuationResult` blamed the model — its message read *"This indicates a broken
calculation, not an extreme market"* — when the market data was the thing at fault.

**How it was found.** Not by a test. By asking what else could reach a snapshot, after
noticing that the validation lived on the builder rather than on the data.

**The fix.** The rule moved onto `MarketDataKey`: each sealed case now says what values it
can legally take, and both the builder and `withShock` ask the key. The invariant ends up on
the type that owns it, the builder stops knowing rules for data it does not own, and there is
no third construction path left to forget — `MarketDataSnapshotTest.oneRulePerKeyNotPerPath`
asserts both routes reject through the same rule.

### C-2 · The engine measured risk it did not report · fixed

M5 added a bond and an FX forward to the demo portfolio. The risk section still showed equity
delta and nothing else, while the book carried:

| Risk factor | Exposure | Reported before |
|---|---:|---|
| USD rates, per bp | −69.92 | no |
| EUR rates, per bp | −51.80 | no |
| EUR/USD, per unit | 484,295.75 | no |

The exposure was real and the stress line moved with it, which is what made it easy to miss:
nothing was wrong, something was merely absent. An engine that computes risk it does not
print is indistinguishable, to a reader, from one that cannot compute it.

**The fix.** `dv01` and `fxDelta` on `SensitivityCalculator`, both built on the existing
shock-and-revalue mechanism — three short methods, no new machinery, which is the argument
for `MarketShock` making its own case. `MarketShock.scaleFxRate` was added alongside them:
the shock family had a single-key form for spot, volatility and rates but only
`scaleAllFxRates` for FX, so one currency could not be moved on its own.

Worth noting what fell out for free: the USD DV01 of −69.92 includes the options' rho. The
bond contributes −112, the forward's USD leg +52, and the two option legs −10 between them.
There is no rho formula anywhere in the codebase.

### C-3 · A bond's dirty price was printed as though it were clean · fixed

The report showed `998.9954` under the same **UNIT VALUE** heading as a share price and an
option premium. For a bond that number is the present value of everything still owed — the
*dirty* price — and bond markets quote clean. `Bond.accruedInterest` had existed since M2,
with four tests and no production caller.

**The fix.** An `AccruingInterest` capability interface, and an accrued-interest block in the
report:

```
ACCRUED INTEREST  (unit values above are dirty: clean + accrued)
  INSTRUMENT                CLEAN        ACCRUED          DIRTY
  CORP-5Y                997.6254         1.3700       998.9954
```

The renderer filters on the capability rather than testing `instanceof Bond`, which would
have been the first branch of the chain this design exists to avoid. One implementor today;
the swap fixed leg is the second at M6.

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
