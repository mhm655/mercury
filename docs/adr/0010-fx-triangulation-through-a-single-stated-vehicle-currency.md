# ADR 0010 — FX triangulation through a single stated vehicle currency

**Status:** Accepted
**Milestone:** M22

## Context

`MarketDataSnapshot.fxRate(from, to)` resolved a rate three ways: a currency against itself is
1, a directly quoted pair is used as stored, and a quoted pair's inverse is derived rather than
separately stored. Nothing beyond those three steps was consulted — GBP to USD was never
derived from GBP/EUR and EUR/USD even when both were present, and the method's own javadoc
named this as deliberate: "cross rates through a vehicle currency need a stated base currency
and a rule for which crosses are legal, and inferring one silently would let a portfolio value
against a rate nobody quoted."

That is a real caller-facing gap. A snapshot quoting GBP/USD and EUR/USD (both against the
dollar, the ordinary way a desk actually quotes) could not answer `fxRate(GBP, EUR)` at all,
even though the cross is fully determined by what the snapshot already holds.

## Decision

`MarketDataSnapshot.Builder` gains an optional `vehicleCurrency(Currency)` — unset by default.
When set, and only when it differs from both `from` and `to`, `fxRate` retries a pair still
missing after the existing three-step resolution as one hop through the vehicle:
`fxRate(from, vehicle) * fxRate(vehicle, to)`, each leg itself resolved by the same
direct-or-inverse rule. A pair still missing after that throws the same
`MissingMarketDataException`, now naming the vehicle currency that was tried.

This is opt-in and one hop only. No snapshot triangulates unless its builder explicitly named
a vehicle, and no snapshot triangulates through more than one currency even if it holds pairs
that would chain further (GBP→EUR→JPY, say). A caller who wants a cross the single named
vehicle cannot reach still gets a clear, loud failure rather than a silently manufactured rate.

## Rationale

**Why a single stated vehicle rather than a graph search over every currency present.** The
gap this closes was never "no triangulation is possible," it was "no *rule* exists for which
crosses are legal." A search over every currency a snapshot happens to hold would answer that
question implicitly and differently for every snapshot, and would let two snapshots holding
identical vehicle-adjacent data disagree about which crosses are legal depending on what else
happened to be quoted. That is exactly the "inferring one silently" failure mode the original
javadoc already rejected — it does not stop being a silent inference just because the search
is now automatic instead of manual. A single named vehicle is a *stated* rule: whoever builds
the snapshot says which currency real desks would actually triangulate through (conventionally
USD, but the type does not hardcode that), and every reader of that snapshot agrees.

**Why one hop, not a chain.** A two-hop-or-more search reintroduces the same problem at one
remove — now the "rule for which crosses are legal" is "however many hops it takes," which is
no rule at all. One hop through one stated currency is the smallest mechanism that actually
answers the gap's own wording, and nothing in this codebase's demos or tests needs a cross that
a single named vehicle cannot reach.

**Why a builder-level property rather than a per-call parameter on `fxRate`.** Which crosses are
legal is a property of the market data itself — the same two currencies should mean the same
thing to every caller reading a given snapshot. A parameter on `fxRate` would let two callers
reading the same snapshot disagree about which crosses were legal, which is a market-data
consistency question, not a call-site preference; it belongs on the snapshot, alongside
`curveInterpolation` (another market-wide convention already carried the same way).

## Alternatives rejected

**Graph search / shortest path over every currency present.** Rejected above — silently
infers a rule per snapshot rather than stating one, and is genuinely more code for a caller
this codebase does not have.

**A per-call vehicle-currency parameter on `fxRate`.** Rejected — fragments a market-wide
convention across call sites and risks two readers of the same snapshot computing different
answers for the same cross.

**Leave the gap open, unscheduled.** Considered — the gap is old and no caller in this
codebase's own demos triangulates yet. Picked to fix directly because the fix is small,
additive, opt-in, and the real-world case (quoting everything against a dollar vehicle rather
than every pair directly) is the actual convention every FX desk already uses, not a
speculative one.

## Consequences

**Good.** Every existing snapshot and test is unaffected — the vehicle field defaults to
`null`, and the triangulation branch is unreachable unless a builder explicitly opts in.
`MissingMarketDataException`'s new overload names the attempted vehicle, so a triangulation
failure is debuggable at the point it happens rather than reconstructed later.

**Cost.** A snapshot that wants a cross needs its builder to state a vehicle currency
explicitly; nothing infers one on the caller's behalf, which is the point, but does mean a
caller who forgets still gets the original, unhelped exception.

**Boundary noted.** Still no multi-hop graph, still one vehicle per snapshot, still throws
rather than degrades. A caller with a genuine need for a currency graph with several bridge
currencies has a real, larger design question ahead of it — this ADR does not answer that one,
and building it speculatively now would be exactly the unrequested machinery §A2.9 argues
against.

## Related

`docs/KNOWN_GAPS.md`'s former "FX | Triangulation through a vehicle currency" entry, closed by
this milestone. ADR 0006 (curve pillars keyed by date, not tenor) is the same "explicit over
inferred" reasoning applied to a different piece of market data.
