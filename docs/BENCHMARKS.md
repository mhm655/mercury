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
books at once - measured in section 6, which confirms the spread across books and finds the
per-book ownership model costing more than it returns while callers still wait for their
trades.

---

## 5. Monte Carlo scaling, 1 to 12 workers (M13)

`MonteCarloScalingBenchmark`, raw results in
[`benchmarks/m13-monte-carlo-scaling.json`](benchmarks/m13-monte-carlo-scaling.json). Warmup
3 × 2 s, measurement 5 × 2 s, one fork. Every row computes the **same answer to the last bit**
(`PathBlocks` fixes the random streams by seed and path count, not by worker count), so each
row does identical work.

Two workloads, chosen because they should behave differently:

- **`optionPrice`**: one million GBM draws and payoffs for a European call. Arithmetic with
  almost no allocation.
- **`valueAtRisk`**: 50,000 paths, each revaluing a four-position option book through
  `PricingService`, then one serial sort of every P&L.

| Workers | `optionPrice` (ms) | Speedup | `valueAtRisk` (ms) | Speedup |
|---:|---:|---:|---:|---:|
| 1 | 25.87 ± 0.21 | 1.00× | 156.6 ± 3.4 | 1.00× |
| 2 | 14.89 ± 0.29 | 1.74× | 88.5 ± 5.2 | 1.77× |
| 4 | 8.59 ± 0.17 | 3.01× | 59.0 ± 9.5 | 2.66× |
| 6 | 6.43 ± 0.25 | 4.03× | 68.1 ± 32.5 | 2.30× |
| 8 | 5.24 ± 0.37 | 4.94× | 60.3 ± 13.1 | 2.60× |
| 12 | 4.36 ± 0.68 | **5.93×** | 61.1 ± 11.0 | 2.56× |

### The prediction, checked

§5.6 predicted near-linear scaling to about 6 workers, then a sharp knee, with hyperthreads
adding "perhaps 15–30%". **That was half right, for one workload, and wrong for the other.**

**`optionPrice` never became linear.** It scaled sub-linearly from the start (1.74× on 2
workers, 4.03× on 6), which suggests a steady per-worker cost, not a wall at the core count.
Hyperthreads added **47%** from 6 to 12 workers, well above the 15–30% predicted. There is a
knee at 6, but it is gentle. That result is plausible for this workload: GBM draws are short
and branch-light, and the working set is a few doubles per worker, so a hyperthread pair
contends for execution resources but hardly for cache or memory.

**`valueAtRisk` stopped at 4 workers, not 6.** From 4 to 12 the figure is flat within its
error bars. The 6-worker row's ±32.5 ms interval covers every other row from 4 upward, so its
slower mean is noise, not a regression. Amdahl's law does not explain the plateau. The serial
work (splitting streams, concatenating block lists, one sort of 50,000 values) is far too
small to hold a 157 ms job to 2.6×.

Running again with JMH's GC profiler (`-prof gc`, workers 1 / 4 / 12) shows why:

| Workers | Allocated per VaR run | Allocation rate | GC time in the run |
|---:|---:|---:|---:|
| 1 | 283 MB | 1.73 GB/s | 62 ms |
| 4 | 288 MB | 4.67 GB/s | 129 ms |
| 12 | 286 MB | 4.53 GB/s | 163 ms |

Each path allocates about **5.7 KB**: a shocked `MarketDataSnapshot`, valuation lines, and
`BigDecimal`-backed `Money` for every position. The allocation rate climbs to about 4.6 GB/s
at 4 workers and **does not climb further**, which is exactly where throughput stops. GC
*pauses* are not the cause: 163 ms of GC across a multi-second measurement is a few percent.
The ceiling is consistent with memory bandwidth, meaning the rate at which one dual-channel
desktop can zero and fill fresh memory. On that reading, more threads only queue for the same
memory bus. This profile shows the correlation, not the mechanism. Confirming it would take
hardware counters or a second machine with more memory channels, and neither was run.

### What this means for the engine

The concurrency design is not the bottleneck. The parallel structure scales to 5.9× on pure
arithmetic, and VaR gives exactly the same result at any worker count. The limit on VaR is the
**revaluation path's allocation profile**, which was designed for exact ledger arithmetic and
immutability, not for 50,000 revaluations a second. The obvious fixes would be a
`double`-domain revaluation path for risk (ADR 0001 already separates ledger from model
arithmetic) or reusing shocked snapshots per block. Neither is built: both trade clarity for
speed, and nothing in Mercury yet needs VaR faster than 60 ms.

For a VaR run on this machine, **4 workers is the useful maximum**. A 12-thread pool buys
nothing over it.

---

## 6. Venue concurrency: books in parallel, and the handoff that did not pay (M13)

`VenueConcurrencyBenchmark`, raw results in
[`benchmarks/m13-venue-concurrency.json`](benchmarks/m13-venue-concurrency.json). Twelve caller
threads, each pinned to one instrument, submitting a crossing sell/buy pair so the book stays
shallow. Throughput in orders per millisecond, higher is better.

| Books | `INLINE` (ops/ms) | `THREAD_PER_BOOK` (ops/ms) |
|---:|---:|---:|
| 1 | 900.7 ± 29.7 | 525.8 ± 64.9 |
| 12 | **2948.3 ± 181.8** | 783.2 ± 44.6 |
| gain from spreading | **3.27×** | 1.49× |

### The claim that held

Section 4 said parallelism comes from running many instruments' books at once. It does:
spreading the same twelve callers over twelve books instead of one is **3.27× the throughput**
with no change to the matching engine at all. One book is a queue by definition - every caller
wants the same exclusive state - and twelve books are twelve independent queues.

The gain is 3.27×, not 12×, on a 6-core machine running 12 caller threads. Matching is
microseconds of work between allocations, so callers spend much of their time contending for
memory rather than for books - the same allocation ceiling section 5 found for VaR.

### The claim that did not: single-writer was slower, everywhere

§5.6 a) describes the LMAX-style design - each book owned by one thread, fed from a command
queue - as *faster* than locking the structure, because the data is never shared. Measured
here, `THREAD_PER_BOOK` is **1.7× slower on one book and 3.8× slower on twelve**.

The reason is in this implementation rather than in the idea. `OrderBookVenue.execute` returns
the trades it produced, so a caller submits a command and then **blocks for the result**. Per
order that adds an enqueue, a park, a wake-up on the writer thread, and a hand-back through a
future: at twelve books, 0.34 µs per order inline against 1.28 µs threaded, so roughly **0.9 µs
of pure handoff** around a matching operation that is itself faster than that. Twelve writer
threads on top of twelve caller threads also oversubscribes a 12-thread CPU by 2×, which the
inline version never does.

What LMAX actually buys requires the caller *not* to wait: submissions are fire-and-forget, the
writer batches whatever has accumulated, and executions come back as events rather than as
return values - and its queue busy-spins rather than parking, which is what makes the handoff
cost tens of nanoseconds instead of a microsecond. Mercury has the event half of that (M13's
`EventBus`) but not the submission half: `ExecutionVenue.execute` is synchronous by contract,
and every caller in the codebase uses its return value.

**So `INLINE` stays the default**, and this is a measurement rather than an opinion.
`THREAD_PER_BOOK` earns its place as the thing that makes the single-writer property real -
one book, one owner, a command log, and deterministic replay - which is why it is built and
tested; it does not currently earn its place on throughput. Closing that gap means an
asynchronous submission path, not a faster queue.
