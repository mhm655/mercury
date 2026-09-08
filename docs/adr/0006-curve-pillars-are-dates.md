# ADR 0006 · Curve pillars are dates, and a flat rate is a curve with one pillar

**Status.** Accepted, M5b.

## Context

Until M5b the engine held one discount rate per currency and every maturity discounted at
it. That is wrong in a way that is easy to state: a five-year bond and an overnight deposit
do not borrow at the same rate, and the difference between the front and the back of a real
curve is routinely a full percentage point.

Fixing it raises four questions that are easier to get wrong than they look.

## Decision 1 · The curve stores zero rates, not discount factors

They are the same information — `DF(t) = e^(-r t)` — so this is a question of which is
easier to reason about. Zero rates win because they are what a bootstrapper solves for and
what a person reads. "The five-year point is at 4.18%" is a sentence about a market; "the
five-year discount factor is 0.8114" is a sentence about arithmetic.

Continuous compounding throughout, matching Black-Scholes and the discounted-cashflow model,
so a bond and an option in one portfolio agree about what a rate means. Quoting on the wrong
compounding basis is an error of order `r²T/2` — about five basis points on a ten-year point
at 4% — which is why the conversion from a market quote lives in the quote type
(`DepositQuote` knows its rate is simple interest) and not in the solver.

## Decision 2 · Pillars are keyed by date, not by tenor

This is the decision that cost something to learn.

A tenor is how a rate is *quoted*; a date is where the money *is*. The first version keyed
pillars by `Tenor` and it broke the bootstrap, quietly:

> Reference date 28 June 2024. Two years later is **Sunday** 28 June 2026, so a 2Y par swap
> makes its final payment on Monday the 29th — one day past the pillar placed for it. That
> payment therefore interpolated between the 2Y and 5Y points, so solving the 2Y depended on
> a 5Y that had not been solved yet, and adding the 5Y afterwards pushed the 2Y swap off par
> by 1.5 basis points of present value.

Every quote in the test market repriced exactly except that one, which is the signature of a
date problem rather than a numerical one. Bootstrapping is only a sequential algorithm if
each quote's last cashflow lands exactly on the pillar being solved for, and only the
instrument knows where that is — it depends on its schedule, its business-day convention and
its calendar. So `CurveInstrument.pillarDate` is asked, and `YieldCurve` stores dates.

**The moral, which generalises.** A sequential algorithm is only sequential if the data is
arranged the way its correctness argument assumed. The argument here was three lines long and
sounded airtight, and the assumption it rested on was never written down.

A second, smaller defect came from the same direction. `Tenor.compareTo` orders by nominal
length in days and is deliberately inconsistent with its `equals`, so `52W` and `364D` compare
equal. A `TreeMap<Tenor, ...>` merged them and built a curve from a quote the caller never
gave. `LocalDate` has no such split between ordering and equality.

## Decision 3 · Outside the pillars, the rate is held flat

Extrapolating along the last slope is the obvious alternative and is worse in a specific way:
an upward-sloping long end, continued, rises forever, and the implied forward rates run away
with it. On the test curve, continuing the 5Y-to-10Y slope would put the 100-year point near
5.9% — a number no quote in the curve supports.

Flat extrapolation says the only honest thing about a horizon nobody quoted, which is that the
curve has no information there. The short end needs less care than it appears to: `DF(0) = 1`
whatever rate is extrapolated back to zero.

## Decision 4 · A flat rate is a curve with one pillar

The tempting shape is to keep the old flat rate as a fast path and add curves alongside it.
That would mean two ways to discount, two places for a compounding convention to drift, and a
branch at every call site.

Instead `MarketDataSnapshot.Builder.discountRate(ccy, rate)` is sugar for a single pillar. One
pillar extrapolates flat in both directions, so it discounts at exactly `e^(-rt)` — bit for
bit what the pre-curve code computed. That is not a coincidence to be grateful for; it is the
property that let the entire pricing stack move onto curves **without a single reference value
changing**, which is how the refactor was verified.

## Decision 5 · Curves enter market data as individual pillars

A curve could have been one opaque value in the snapshot. Storing it pillar by pillar instead
is what keeps `MarketShock` the only mechanism the engine needs:

| Risk number | Shock |
|---|---|
| DV01 | matches every `ZeroRate` pillar of one currency |
| Parallel shift, all currencies | matches every `ZeroRate` pillar |
| Key-rate duration (not built) | would match one pillar |

None of those needs code that knows what a curve is. An opaque curve value would have needed
its own shock type, its own composition rules, and its own place in the stress engine.

## Decision 6 · Bisection, not Newton-Raphson

Newton converges quadratically; bisection cannot fail to converge. Three reasons the slower
one is right here, in order of weight:

1. **Guaranteed convergence.** Given a bracket with a sign change, the root stays trapped.
   Newton can oscillate, diverge, or hit a stationary point. A bootstrapper that occasionally
   returns nonsense is far worse than one slower than it needed to be.
2. **No derivative to hand.** The function is "reprice this instrument under a curve built
   with this pillar value", which differentiates through the interpolator. Newton would need a
   numerical derivative — two evaluations per step instead of one — spending most of its
   advantage.
3. **Not a hot path.** A curve is built once and read many times. Twelve pillars at sixty
   iterations is under a thousand evaluations for the whole construction.

Brent's method is the drop-in if a measurement ever justifies it: it keeps the bracket, so it
keeps the guarantee.

## Consequences

**Good.** One discounting mechanism, with the flat case as its degenerate instance. Curve risk
falls out of the existing shock abstraction. `MarketDataSnapshot` now carries the date it
always claimed to describe, which closes a class of error — a market from one day used to
value a portfolio on another — that previously produced a plausible number and no complaint.

**Costs.** A curve is rebuilt from its pillars on every read rather than cached, because a
cache would be the first mutable state in a type whose immutability is what makes it safe to
share across Monte Carlo workers without synchronisation. If a profile ever shows this
mattering, the fix is to hoist the curve out of the pricing loop, not to make the snapshot
stateful.

**Not supported.** Dual-curve (OIS) discounting, convexity adjustment on futures quotes, and
turn-of-year effects. All are listed in `KNOWN_GAPS.md`. Spline interpolation is deliberately
absent: it removes the kink at each pillar and can produce negative forward rates between
them, which is an arbitrage the input data never contained.
