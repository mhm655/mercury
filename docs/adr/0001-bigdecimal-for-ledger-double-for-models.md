# ADR 0001 — `BigDecimal` for ledger facts, `double` for model output

**Status:** Accepted
**Milestone:** M1

## Context

Financial code has to represent two kinds of number, and they have opposite requirements.

A **ledger fact** — a cash balance, a trade's consideration, realised P&L, a settlement
amount — must be exact. It reconciles to the cent against a counterparty's records, and a
one-cent discrepancy is a break somebody has to investigate. Binary floating point cannot
represent `0.10`, so a hundred additions of one cent in `double` gives
`1.0000000000000007`, not `1.00`.

A **model output** — a Black-Scholes price, a Greek, a discount factor, a Monte Carlo path
— is an approximation. It rests on assumptions (lognormal returns, constant volatility)
that are wrong in the fourth significant figure long before arithmetic precision matters.
These values are produced in the hottest loops in the project: a Monte Carlo run evaluates
hundreds of thousands of paths.

Picking one representation for everything fails in one direction or the other.

## Decision

Use both, with a single explicit boundary between them.

- `BigDecimal`, wrapped in `Money`, for ledger facts. Normalised on construction to the
  currency's minor units, HALF_EVEN rounding.
- `double` for model output — pricing results, Greeks, curve values, basis points.
- Exactly one crossing point: `Money.fromModelValue(double, Currency)`.

`Money` deliberately has **no** `of(double, Currency)` factory. The named method makes
every crossing greppable and impossible to perform by accident, and it rejects NaN and
infinity, which is usually how a broken model first announces itself.

## Consequences

**Good.** Cash arithmetic is exact and reconciles. Monte Carlo runs at `double` speed —
`BigDecimal` in that loop would be roughly an order of magnitude slower and would allocate
heavily, which matters directly for the scaling benchmarks in M13. The split is visible in
the type system rather than being a convention people remember.

**Costs.** Two numeric worlds means developers must know which side they are on. Mitigated
by the naming (`Money` versus plain `double`), by `fromModelValue` being the only door
between them, and by an ArchUnit rule forbidding `double` and `float` fields in the money
package.

**Rounding.** Multiplication and division round once, HALF_EVEN — banker's rounding, chosen
over HALF_UP because always rounding halves upward introduces a systematic drift across a
large book, which is exactly the kind of error that surfaces as an unexplained
reconciliation break.

**Not chained.** Multi-step arithmetic must happen in `BigDecimal` or `double` and convert
once, because chaining through `Money` would round at every step.

## Alternatives rejected

- **`double` everywhere.** Fast and simple, and wrong for cash. Non-starter.
- **`BigDecimal` everywhere.** Correct for cash, unacceptably slow and falsely precise for
  models. It would also make the Monte Carlo benchmarks meaningless.
- **Long minor units (cents as `long`).** Exact and fast, and genuinely used in production
  ledgers. Rejected because it forces every currency's scale into the call site, handles
  JPY (zero minor units) badly alongside USD, and overflows silently on large notionals.
  `BigDecimal` with a normalising wrapper gives the same exactness with fewer traps.
