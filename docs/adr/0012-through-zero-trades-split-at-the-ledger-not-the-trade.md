# ADR 0012 — Through-zero trades split ledger-side bookkeeping, not the `Trade`

**Status:** Accepted
**Milestone:** M23

## Context

`PositionLots.apply` refuses a trade that would carry a position through zero - selling
fifteen when long ten really is two trades (closing ten at the old cost basis, opening a short
five at today's price), and `PositionLots`'s own invariant is that every lot it holds shares
one sign. Its exception message says so directly: "book the two legs separately." Until this
milestone, `PortfolioLedger` never did - a `Trade` that crossed zero simply failed to book at
all.

Read literally, "book the two legs separately" suggests minting two independent `Trade`
objects - two `TradeId`s, each with its own lifecycle history. That is not what this milestone
does, and the reason is worth recording, because a future reader re-reading the gap's own
wording is likely to propose exactly that again.

## Decision

The split happens entirely inside `PortfolioLedger`'s ledger bookkeeping. A new private
`applySplittingAtZero` detects a through-zero delta before it reaches `PositionLots.apply`,
splits it into a closing `Quantity` (exactly `-held`, flattening the existing position to zero)
and an opening `Quantity` (whatever remains), prorates the trade's consideration between the
two by quantity, and calls the *unchanged* `PositionLots.apply` twice - once to close, once to
open on the result. No second `Trade` is minted, no second `TradeId` is requested.
`PositionLots` itself is not touched at all; it keeps refusing a through-zero delta in one
call, and `PortfolioLedger` simply never gives it one.

## Rationale

**A `Trade` arriving at `PortfolioLedger.book` is already a sealed fact, not raw material.**
By the time `book(Trade)` is called, the trade has already walked
`NEW -> VALIDATED -> BOOKED -> EXECUTED` upstream, at a venue, as one audited execution under
one id - `Trade.transitionTo`'s whole design is an append-only history for exactly this reason.
Retroactively declaring "this was actually two trades" after that history is already sealed
would mean inventing a second execution that never happened at the venue, under an id the venue
never minted, for an event the audit trail says was one thing. The split this milestone makes
is real and necessary - the *ledger's bookkeeping* genuinely needs two lots-level operations -
but the *trade* was one execution, and staying honest about that means the split cannot leak
into `Trade`'s own history.

**Minting a second `TradeId` would need the same shared generator the codebase already had to
fix once.** `OrderBookVenue` and `OtcNegotiationVenue` share one `TradeIdGenerator` instance
specifically because of a previously-recorded bug (`docs/KNOWN_GAPS.md`'s G-1: two independent
counters would each start at `TRD-1` and collide). If `PortfolioLedger` minted a second `Trade`
for the opening leg, it would need access to that exact same generator to avoid reopening G-1 -
and `PortfolioLedger` has no such access today, nor any other reason to need one. Threading a
`TradeIdGenerator` into a class that has never needed to mint an id would be new plumbing
purely to support a design this ADR is arguing against.

**Nothing downstream needs to query "the opening leg" as its own auditable `Trade`.** What the
original gap actually demanded was correct cost basis and correct realised P&L across the zero
boundary - both fully achieved by two calls to `PositionLots.apply`. Building two independently
lifecycle-tracked `Trade` objects, with their own settlement dates and their own entries in
`TradeSettlementBook`, would be machinery for a caller that does not exist, straight against
§A2.9. If a real caller ever needs to see the opening leg as its own auditable execution - a
regulatory reporting requirement, say - that is real, additional design work for that moment,
not something to build speculatively now.

**The consideration is prorated by quantity, rounded once.** `applySplittingAtZero` computes
the closing leg's cash as a fraction of the total consideration, and the opening leg's cash as
the *remainder* (a subtraction), never as an independently-rounded fraction of its own. This
mirrors `CostBasisMethod.AVERAGE_COST`'s existing "round the part taken once, the remainder is
a subtraction" rule, and guarantees the two legs' cash always sums back to the original
consideration exactly, to the last cent, regardless of how the fraction itself rounds.

## Alternatives rejected

**Mint two `Trade` objects, one closing and one opening, both booked through `book(Trade)`.**
The literal reading of the gap's own wording. Rejected for the reasons above: it retroactively
edits a sealed execution history, and it would reopen the exact `TradeIdGenerator`-sharing
problem G-1 already fixed once, for no caller that needs it.

**Leave `PositionLots.apply` itself able to split internally.** Rejected - `PositionLots`'s
invariant (every lot shares one sign) is simple and enforced in exactly one place today; making
`apply` itself split would mean `PositionLots` needs to know about proration and multi-step
booking, machinery that belongs to the caller orchestrating a trade's booking, not to the type
that only ever needs to know about one position's lots.

## Consequences

**Good.** A trade that crosses zero now books correctly instead of being refused. Proven by
direct comparison rather than by hand-derived numbers:
`sellingThroughZeroMatchesBookingTheTwoLegsSeparately` and
`buyingThroughAShortMatchesBookingTheTwoLegsSeparately` assert the one-call crossing trade
produces *identical* ledger state (quantity, cost basis, realised P&L, cash) to booking the
same two legs as separate calls at the same price - the strongest correctness proof available,
and one that caught a real sign error (adding the closing delta instead of subtracting it)
during implementation, before it ever reached a commit.

**Cost.** A caller that genuinely wants the opening leg as its own auditable `Trade` - with its
own settlement date, its own lifecycle - cannot have one from this mechanism; it would need to
book two `Trade`s itself, upstream, rather than let one crossing trade imply a split here.

**Boundary noted.** If a real caller ever needs the opening leg to be independently
auditable (not just correctly booked), that is a larger, different design question - a second
`TradeId` from the shared generator, its own settlement date, its own entry in
`TradeSettlementBook` - and deserves its own ADR when it actually arises.

## Related

`docs/KNOWN_GAPS.md`'s G-1 entry (order ids reusable after a fill), whose fix is the reason a
second minted `TradeId` here would need the shared `TradeIdGenerator`. `TradeIdGenerator`'s own
javadoc, which records why it is shared rather than one per venue.
