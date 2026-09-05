# ADR 0004 — Capability interfaces, and where the cashflow boundary sits

**Status:** Accepted
**Milestone:** M2
**Supersedes:** a claim in `DESIGN_PROPOSAL.md` §3.3

## Context

Five instruments — stock, bond, FX forward, European option, interest-rate swap — need a
common abstraction that lets a portfolio hold them uniformly, without the base interface
degenerating into the union of everything any instrument can do.

The obvious failure mode is a fat `FinancialInstrument` with `maturity()`, `notional()`,
`cashflows()`, `strike()` and `underlying()`, where most methods return null, empty or a
meaningless default for most implementations. Every caller then has to know which methods
are real for which instrument — the `instanceof` chain, moved into the type and made
invisible.

## Decision

Keep `FinancialInstrument` to the four properties every instrument genuinely has (`id`,
`currency`, `assetClass`, `tradability`), and express everything else as capability
interfaces an instrument implements only when the capability truly holds:

| Capability | Implemented by |
|---|---|
| `CashflowGenerating` | Bond, FxForward, FixedRateLeg |
| `Maturing` | Bond, FxForward, EuropeanOption, InterestRateSwap |
| `HasUnderlying` | EuropeanOption |
| `OptionTerms` | EuropeanOption |

`Stock` implements none of them. That is a useful signal: if every instrument implemented
every capability, the capabilities would not be carrying real distinctions.

## The hard case: floating-rate legs

`CashflowGenerating` promises `Cashflow`s carrying real `Money` amounts. A floating-rate
swap leg cannot honour that promise. Each coupon is
`notional × (indexRate + spread) × yearFraction`, and `indexRate` is a forward rate
projected from a discount curve. An instrument definition has no curve, and giving it one
would break the rule that pricing is a pure function of instrument *and* market data
(§A2.4) — an instrument reaching out for market data on its own is exactly the hidden
dependency that makes valuation irreproducible.

**So `InterestRateSwap` does not implement `CashflowGenerating`.** Its `FixedRateLeg` does;
its `FloatingRateLeg` exposes schedule, index, spread and day count, and a pricer projects
the coupons once it holds a curve at M6.

This corrects `DESIGN_PROPOSAL.md` §3.3, which claimed the seam covered "bonds, FX forwards
and swap legs". It covers *fixed* legs.

### Why not implement it anyway

The tempting alternative is to implement the interface and return something — zeros,
last-known fixings, forward estimates. That is strictly worse than not implementing it.
Callers would receive `Cashflow` objects carrying real-looking `Money` amounts that are not
the contract's amounts, with nothing in the type system to distinguish them. The interface
would come to mean "cashflows, except sometimes they are invented", and every caller would
need to know which case it was holding — reintroducing precisely the hidden conditional
that polymorphism exists to remove.

Leaving it unimplemented makes the compiler enforce the distinction. A pricer that needs
projected cashflows cannot accidentally receive fabricated ones.

## Two uses of `instanceof`, only one of which is the anti-pattern

The design forbids `instanceof` chains, but the tests deliberately use
`instanceof CashflowGenerating`. These are different things:

- **Branching on a concrete instrument type** (`if (x instanceof Bond) ... else if (x instanceof Stock)`)
  is closed. Every new instrument forces an edit to every such chain, which is the
  open-closed violation the architecture exists to avoid.
- **Asking whether something has a capability** is open. A new instrument implementing
  `CashflowGenerating` is picked up by existing code with no change at all.

`InstrumentPolymorphismTest` demonstrates the second and contains none of the first.

## Consequences

**Good.** The base interface stays honest — every method means the same thing for every
implementation. Generic machinery (exposure bucketing, venue routing, maturity filtering)
works against declared properties with no type checks. A new instrument opts into exactly
the capabilities it has.

**Costs.** Callers wanting a capability must test for it, which is more verbose than
calling a method that is always present. Accepted: the verbosity is the type system making
a real distinction visible, rather than the distinction existing anyway and being invisible.

**Enforced.** `FinancialInstrument` must never be sealed — sealing would close the set and
make the M15 extensibility proof impossible. `InstrumentPolymorphismTest` asserts it is not,
as the deliberate counterpart to `DomainIdTest` asserting that `DomainId` *is* sealed.
Identifiers are a closed set; instruments are the open set the architecture is built around.
