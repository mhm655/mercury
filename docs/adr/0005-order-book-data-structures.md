# ADR 0005 — Order book data structures

**Status:** Accepted
**Milestone:** M3

## Context

A central limit order book must support four operations, all of them hot:

1. **Insert** a resting order at a price.
2. **Cancel** an arbitrary resting order by id.
3. **Read top of book** — the best bid and ask.
4. **Match** an incoming order against the opposite side, in price-then-time order.

The mix is not uniform. In live markets the overwhelming majority of orders are cancelled
rather than filled, and cancellations target orders sitting anywhere in a queue, not just at
its front. Top of book is read on every incoming order and every market-data tick. Any
structure that is O(n) in cancellation or in reading the best price will dominate the
profile regardless of how fast matching is.

## Decision

```
OrderBook
├─ TreeMap<Price, PriceLevel> bids   (descending comparator)
├─ TreeMap<Price, PriceLevel> asks   (ascending)
├─ HashMap<OrderId, OrderNode>       (O(1) cancellation)
└─ cached bestBid / bestAsk level references
```

`PriceLevel` holds an **intrusive doubly-linked list** of `OrderNode`s — the links live on
the node itself, not in a wrapping collection.

| Operation | Cost | Mechanism |
|---|---|---|
| Insert, new price | O(log P) | TreeMap insertion |
| Insert, existing price | O(1) | append to the level's tail |
| Cancel | **O(1)** | id→node map, then unlink; O(log P) only if the level empties |
| Best bid / ask | **O(1)** | cached level reference |
| One fill | O(1) | always the head of the best level |
| Sweep k levels | O(k log P + f) | one TreeMap removal per emptied level |

Price priority comes from the `TreeMap` ordering levels; time priority comes from each
level's FIFO queue. Each half of the rule lives in exactly one place.

## Why intrusive linking rather than `ArrayDeque`

Both are O(1) at the ends. The difference is interior removal: `ArrayDeque` must find the
element and then shift, which is O(n). With links on the node, and the node reachable in
O(1) from the id map, cancellation is a handful of pointer writes regardless of where in the
queue the order sits.

Since cancellation is the most common operation and interior cancellation is the common
case, this single difference is the reason `OrderNode` exists instead of a collection.

## Alternatives rejected

**`ArrayList` per side, scanned linearly.** O(n) for cancellation and for finding the best
price. Correct, and what most first attempts look like. Kept as `NaiveOrderBook` in the
benchmark module so the comparison is measured rather than asserted.

> **Measured** ([BENCHMARKS.md](../BENCHMARKS.md)): at 50,000 orders the indexed book is
> 2,080× faster to cancel and 110,000× faster to read top of book. I predicted the array
> would *win* at small sizes on cache friendliness; it did not, losing 39× at even 1,000
> orders. Cache friendliness is worth a constant factor of 5–10×, and cancelling from the
> middle of a 1,000-element array does ~1,000 memory operations against a hash lookup and
> four pointer writes. The crossover is below the range this harness can measure.

**One `PriorityQueue` per side.** O(log n) insert and O(log n) to pop the best. Rejected on
three counts, the last of which is fatal: removal of an arbitrary element is O(n); there is
no grouping by price, so depth queries would have to scan; and a binary heap provides no
stable ordering among equal keys, so it **cannot express time priority at all**. A book that
cannot honour time priority is not a book.

**A fixed array indexed by tick.** O(1) for everything, and the right answer for a real
exchange, where the tick grid is bounded, known in advance, and dense. Rejected here because
the simulation fixes no price range, and the array would have to be sized to the whole grid
however sparse the book actually is. Worth naming precisely because it *is* what a
production venue would do — the choice here follows from different constraints, not from
ignorance of it.

## The cached top of book

`TreeMap.firstEntry()` is O(log P), which is already cheap. Caching the best level makes it
O(1), which matters because the matching loop consults it on every iteration.

The cost is an invariant to maintain, and a stale cache would be the most likely bug in the
class. Two mitigations:

- Insertion updates the cache in O(1) — a newly inserted level can only become the best if it
  beats the current one, which is one comparison. The expensive recompute is needed only when
  a level *disappears*, so it is paid once per emptied level rather than once per fill.
- `OrderBookProperties.cachedTopOfBookMatchesTheTree` asserts, over ~1000 randomly generated
  order sequences, that the cache always agrees with the authoritative `TreeMap`.

## Consequences

**Good.** Cancellation and top-of-book reads are constant time. Price-time priority is
expressed structurally rather than by sorting on read. Depth queries are O(levels requested)
because each level maintains its aggregate quantity incrementally.

**Costs.** More moving parts than a list: three structures that must agree, plus a cache.
The property tests exist specifically to hold that consistency, and `PriceLevel.remove`
verifies a node actually belongs to it before unlinking.

**Not thread-safe, deliberately.** The concurrency model (§5.6) is single-writer: each book
is owned by one thread and fed from a command queue, so books for different instruments run
in parallel while a single book is never contended. Synchronising this class would be slower
than the queue and would buy nothing the queue does not already give — including
deterministic replay from the command log.

**Deferred, resolved.** M1 left open whether the book should hold `Quantity` or a primitive.
It holds `long`, and the argument turned out to be domain rather than performance: only
exchange-traded instruments reach a CLOB, and those trade in whole units. The fractional
notionals that need `Quantity` belong to OTC instruments, which are negotiated bilaterally
and never touch the book. That it also keeps the matching loop allocation-free is a
secondary benefit, not the justification.
