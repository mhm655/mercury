# Benchmarks

Real measurements from JMH. Nothing here is estimated, extrapolated or rounded in the
project's favour, and the one prediction that failed is reported as a failure.

## Environment

| | |
|---|---|
| CPU | Intel Core i5-10400F @ 2.90 GHz — 6 physical cores, 12 threads |
| Memory | 16 GB |
| OS | Windows 10 Pro 22H2 (10.0.19045) |
| JDK | Amazon Corretto 21.0.12.9.1 (21.0.12.1+9-LTS), 64-bit Server VM |
| JMH | 1.37 |
| VM options | none — stock heap, stock GC |

**Methodology.** One fork per benchmark; 3 warmup and 5 measurement iterations. Orders are
pre-built in trial setup so allocation and id construction are excluded from the measured
path. Batched benchmarks report per-order figures via `@OperationsPerInvocation`. Books that
are consumed by the operation under test (cancellation, matching) are rebuilt in
invocation-level setup, which JMH excludes from the measurement.

**Caveats, stated up front.** A single fork does not capture run-to-run variance across JVM
instances. Several results carry wide error bars — noted inline where they affect a
conclusion. These are single-machine numbers on a consumer desktop, not a tuned server.

Reproduce with:

```bash
mvn package && java -jar mercury-benchmarks/target/benchmarks.jar
```

---

## 1. Order book throughput

A book of 100,000 resting orders across 1,000 distinct price levels — deep at few prices,
which is the shape real books take.

| Operation | ns/op | Throughput | What it covers |
|---|---:|---:|---|
| `topOfBook` | 2.70 ± 0.10 | 370 M/s | best bid price + quantity |
| `cancelOrders` | 56.9 ± 10.8 | 17.6 M/s | cancel all 100,000, incl. emptying levels |
| `depthTenLevels` | 104.6 ± 2.4 | 9.6 M/s | aggregate top 10 levels |
| `matchSweep` | 163.9 ± 7.6 | 6.1 M/s | one aggressor sweeping all 100,000 |
| `insertOrders` | 298.5 ± 24.3 | 3.35 M/s | insert 100,000 into an empty book |

Insertion is the slowest, at roughly 5× a cancellation. That ordering is expected and worth
saying plainly: each insert allocates an `OrderNode`, does a `TreeMap` lookup costing about
ten `Price` comparisons at 1,000 levels, and puts an entry in the id index. A cancellation
does a hash lookup and a handful of pointer writes, and allocates nothing.

Matching at 164 ns per fill is dominated by allocating a `Fill` record per execution and
appending it to a list. That is a deliberate trade — fills are immutable values that flow to
the event bus and the audit trail — but it is the obvious target if this ever needs to be
faster.

---

## 2. Reading top of book: O(1) versus O(n)

Best-bid price, against book size.

| Book size | Indexed (ns) | Naive (ns) | Speedup |
|---:|---:|---:|---:|
| 1,000 | 2.42 ± 0.04 | 2,879 ± 117 | 1,190× |
| 10,000 | 2.45 ± 0.11 | 39,154 ± 6,837 | 15,960× |
| 50,000 | 2.43 ± 0.12 | 268,237 ± 22,993 | **110,204×** |

The indexed book is **flat to within measurement noise** across a 50× size increase —
2.42, 2.45, 2.43 ns. That is the cached best-level reference doing exactly what it was added
for, and it is direct evidence the cache invariant holds.

The naive book grows worse than linearly: 50× the orders costs 93× the time. Pure O(n) would
predict 50×. The excess is memory hierarchy — at 50,000 orders the scanned working set no
longer fits in cache, so each element visit increasingly costs a memory round-trip rather
than a cache hit.

---

## 3. Cancellation: where the prediction failed

Cancelling orders from the **middle** of the book. The middle is the point: removing from the
front is cheap in any structure, and interior removal is both the discriminating case and the
realistic one, since most orders in live markets are cancelled rather than filled.

| Book size | Indexed (ns) | Naive (ns) | Speedup |
|---:|---:|---:|---:|
| 1,000 | 27.1 ± 2.4 | 1,045 ± 110 | 39× |
| 10,000 | 45.6 ± 30.2 | 26,041 ± 1,082 | 571× |
| 50,000 | 64.2 ± 16.6 | 133,571 ± 55,326 | **2,080×** |

### The prediction I got wrong

Before running this I wrote, in `ADR 0005` and in the benchmark's own documentation, that
the naive `ArrayList` should **win at small book sizes**, because a contiguous scan is
cache-friendly while pointer-chasing through a tree and a linked list is not. I said that if
the indexed book won at every size, that would be evidence my baseline was a strawman.

It won at every size. At 1,000 orders it is already 39× faster.

The reasoning was miscalibrated rather than wrong in kind. Cache friendliness buys a constant
factor of maybe 5–10× per element touched. But cancelling from the middle of a 1,000-element
`ArrayList` touches roughly 500 elements to find the order and then shifts roughly 500 more
to close the gap — about 1,000 memory operations against a hash lookup and four pointer
writes. A 10× constant-factor advantage cannot cover a 250× difference in work done. The
crossover must sit somewhere below a hundred orders, where the absolute counts are small
enough for the constant to matter.

This harness cannot probe that region: it cancels 500 orders per invocation, so book sizes
below ~1,000 are not measurable without changing the batch. **The crossover is therefore
unmeasured, not absent** — and stating that is more useful than quietly deleting the
prediction.

### Indexed cancellation is not perfectly flat

27.1 → 45.6 → 64.2 ns is a 2.4× increase over a 50× size increase. The error bars are wide
(±30 ns at 10,000) but the intervals at 1,000 and 50,000 do not overlap, so the growth is
real rather than noise.

This is not an algorithmic failure — it is the memory hierarchy again. The operation is O(1)
in work done: one `HashMap` lookup, one unlink, occasionally an O(log P) `TreeMap` removal
when a level empties. But at 50,000 orders the nodes and hash table span several megabytes,
comparable to this CPU's L3, so both the hash probe and the node dereference start missing
cache. The *constant* degrades with working-set size while the *complexity* does not.

The contrast is the point: over the same range the indexed book grew 2.4× and the naive book
grew 128×.

---

## 4. What these numbers do and do not show

**They show** the data structure choices in ADR 0005 are worth their complexity: constant-time
top of book and cancellation, verified empirically against the obvious alternative, with
speedups of three to five orders of magnitude at realistic book depths.

**They do not show** that this is a fast matching engine by industry standards. A production
venue would use a tick-indexed array, avoid allocating a `Fill` per execution, and be measured
in latency percentiles under load rather than in average time on an idle desktop. The
comparison here is against the naive implementation, not against LMAX.

**Single-threaded throughout.** The concurrency model is single-writer per book (§5.6), so
these figures are the per-book ceiling. Parallelism comes from running many instruments'
books at once, which is measured at M13.
