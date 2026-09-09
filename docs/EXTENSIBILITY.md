# The extensibility proof

Mercury's central architectural claim is that a new instrument can be added **without
modifying existing code**. Every design decision in the pricing layer — the type-keyed
registry, the capability interfaces, the refusal to use `instanceof` or a Visitor — exists to
make that true.

This document is the evidence. The claim is checkable in one command.

```bash
git show --stat <commit>
```

## The commit

An interest-rate **cap and floor** — the sixth instrument type — added in a single commit
containing only new files:

```
 docs/EXTENSIBILITY.md                                            | new
 mercury-engine/src/main/java/com/mercury/instrument/CapFloor.java | new
 mercury-engine/src/main/java/com/mercury/pricing/model/BlackCapModel.java | new
 mercury-engine/src/test/java/com/mercury/pricing/BlackCapModelTest.java | new
```

No existing class, interface, enum, registration or test was edited. The full suite passes,
including the ArchUnit layering rules and the golden master, which is byte-identical because
nothing the demo touches changed.

## Why a cap, and not something easier

A proof is only worth as much as the thing it proves. Adding a second kind of stock, or a bond
with a different day count, would demonstrate nothing that the existing five instruments do not
already show.

A cap was chosen because it pays a kind of cashflow **the engine had never seen**. Mercury had
two:

| Kind | Example | Interface |
|---|---|---|
| Known at trade time | bond coupon, FX forward leg | implements `CashflowGenerating` |
| Projected from a curve | swap floating coupon | deliberately does not |
| **Contingent** | **caplet** | **also does not, one step further along** |

A floating coupon is unknown but determined — give the curve a date and it produces a number.
A caplet's payoff is not determined by the curve at all: it depends on the *distribution* of
where the rate might go, and two desks with the same curve and different volatility will
disagree about its value.

If the capability interfaces were the wrong shape, this is where it would show. They were not.
`CapFloor` implements `FinancialInstrument` and `Maturing` and neither cashflow interface,
which is exactly what it is: a dated instrument with no cashflows anyone can write down.

## What the new code reuses without changing

The point is not that the cap is isolated. It is that it is *deeply integrated* and still
required no edits:

- **`OptionType`** — reused as-is. A caplet is a call on the forward rate and a floorlet is a
  put, so the existing enum says precisely the right thing, and its `intrinsicValue` is already
  the payoff at the fixing date. A cap-specific enum would have been a synonym.
- **`YieldCurve.simpleForwardRate`** — written at M6 for swap coupons, used here unchanged to
  get each caplet's forward.
- **`ScheduleGenerator`, `Schedule`, `SchedulePeriod`** — one caplet per period.
- **`NormalDistribution`** — the same cumulative normal Black-Scholes uses.
- **`FloatingRateIndex`, `Money`, `MarketDataSnapshot`, `PricingService`** — untouched.

## The test that makes it more than a compile check

Anything can be added if nothing checks it works. The strongest assertion in
`BlackCapModelTest` is **cap-floor parity**:

> A cap minus a floor at the same strike equals a payer swap on the same schedule.

Because `N(d1) + N(−d1) = 1`, the option terms collapse to `F − K` and the identity is exact.
It is asserted against `SwapModel` — code that knows nothing about caps — so the new model is
cross-validated against the old rather than against itself. It is also checked at three
different volatilities, because parity is an arbitrage relationship and must not depend on the
one input that is pure assumption.

The suite also pins the boundaries a lognormal model has: zero volatility gives exactly the
intrinsic value, a negative forward rate is refused with the name of the model that would work
instead, and a caplet that has already fixed is refused rather than guessed.

## What the constraint cost

Two things, and pretending otherwise would undermine the exercise.

**A duplicated concept.** `SwapModel` refuses a floating period that has already fixed, and
`BlackCapModel` now refuses a caplet that has already fixed, for exactly the same reason. The
right shape is one shared `MissingFixingException` in the pricing package. That would have
meant editing `SwapModel`, so the cap carries its own. When fixings arrive at M8 the two should
be hoisted into one.

**Two setters deleted before they were used.** `CapFloor.Builder` was written with
`businessDayConvention` and `calendar` setters, copied from the instruments around it. The
no-orphaned-API rule failed the build immediately: nothing called them. They were removed and
the conventions fixed at the engine-wide defaults. That is a build check doing its job on the
very commit meant to showcase the architecture, which is a better advertisement for the rule
than any amount of prose.

## What this does not prove

The cap is **not** in the demo portfolio, and adding it there would require editing
`DemoScenario` — one instrument, one position, one registration line. That is a real cost and
it is not zero.

What the claim means precisely is this: **the engine does not change**. The composition root
changes, because a composition root is the one place that is supposed to know what exists. No
pricing, portfolio, risk or market-data class needed a new branch, a new case, a new subclass
or a new method to accommodate an instrument whose payoff shape had never existed before.

That is the whole of the open-closed principle, and the whole of what the design promised.
