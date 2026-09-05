# ADR 0003 — Generate payment schedules backwards from maturity

**Status:** Accepted
**Milestone:** M1

## Context

Bonds, both legs of an interest-rate swap and an FX forward's cashflows all need a payment
timetable derived from an effective date, a maturity, and a frequency. When the term is not
an exact whole number of periods, one period must be irregular — the **stub**. Where that
stub goes is a design decision with real consequences.

## Decision

Generate period boundaries by stepping **backwards from maturity**, so any stub lands at
the front as a short first coupon. Additionally, anchor every boundary to maturity
(`maturity.minusMonths(months * step)`) rather than chaining from the previously computed
boundary.

## Rationale

**Why backwards.** Maturity is the contractually fixed date. It is when principal is
repaid, and the final coupon must settle alongside it. Generating forwards from the
effective date puts the irregular period at the *end*, which separates the last coupon date
from the maturity date — so principal and the final interest payment fall on different days.
That is wrong, and it is also not what the market does.

Worked example: a bond issued 15 February 2024, maturing 30 June 2029, paying
semi-annually.

- **Backwards (chosen):** coupons on 30 June and 30 December each year, all anchored to
  maturity's day of month, with a short first period from 15 February to 30 June 2024.
- **Forwards (rejected):** coupons on the 15th of each month, with a stub at the end — so
  the final coupon lands on 15 February 2029 and principal on 30 June 2029, four months
  apart.

**Why anchor to maturity rather than chain.** Repeatedly subtracting one period from the
previous boundary ratchets month-end dates downward and never recovers. From 31 December,
subtracting a month gives 30 November (November has 30 days); subtracting again gives
30 October, not 31. The schedule silently drifts off month-end. Multiplying the offset from
a fixed maturity keeps every date on the 31st where the month allows it. This is asserted
directly in `ScheduleGeneratorTest.monthEndDoesNotDrift`.

**Why adjust each boundary independently.** Dates are generated unadjusted, then rolled to
business days — but each from its *own* unadjusted date, never from the previous adjusted
one. If adjustments chained, a single weekend early in a thirty-year swap would shift every
subsequent date, and the schedule would wander away from the intended day of the month.
Asserted in `adjustmentDoesNotAccumulate`.

## Consequences

**Good.** Coupon dates line up with maturity, principal and final coupon settle together,
and month-end schedules stay on month-end. One implementation serves bonds, both swap legs
and FX forwards, so the month-end and stub rules cannot diverge between instruments — which
is the single largest duplication this milestone removes.

**Costs.** A short first coupon is slightly less intuitive to read in test output than a
short last one. The three dates per period (accrual start, accrual end, payment) are more
than a naive model needs, but conflating accrual with payment would force a choice between
computing the cashflow's size correctly and discounting it correctly.

**Not supported.** Only frequencies dividing evenly into twelve months (annual,
semi-annual, quarterly, monthly). A four-monthly coupon would complicate the generator for a
case that effectively does not occur. Long stubs (merging the odd period into the first
regular one) and end stubs are also unsupported; both are configurable in production systems
and neither is needed by the five planned instruments. If one becomes necessary, it is a
parameter on the generator, not a change of approach.
