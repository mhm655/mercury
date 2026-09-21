# ADR 0009 — `CorrelationMatrix` requires positive definite, not merely semi-definite

**Status:** Accepted
**Milestone:** M20

## Context

`CorrelatedMonteCarloVaRCalculator` needs to turn several independent standard normal draws
into correlated ones. The standard construction is `L * Z`, where `Z` is a vector of
independent draws and `L` is the lower-triangular Cholesky factor of the correlation matrix,
satisfying `L * L^T = correlation`. `CorrelationMatrix.of(...)` validates a caller-supplied
matrix (square, symmetric, unit diagonal, entries in `[-1, 1]`) and computes `L` once at
construction, since a Monte Carlo run needs it on every one of tens of thousands of paths.

A correlation matrix that is positive semi-definite but not positive *definite* is
mathematically valid - a covariance/correlation matrix is never allowed to be indefinite, but
it can be singular. The simplest example is an exact `+1` or `-1` pairwise correlation: two
risk factors declared to move in perfect lockstep (or perfect opposition). The plain
Cholesky-Banachiewicz algorithm this class implements hits that case as a zero on the diagonal
during decomposition - mathematically the correct answer (the factor genuinely has no
remaining independent variance to contribute), but the algorithm's next step divides a later
row by that diagonal entry, which is division by zero.

## Decision

`CorrelationMatrix.of(...)` requires the matrix to be strictly positive definite. Encountering
a non-positive value under the square root at any row - whether from a genuinely indefinite
matrix (three pairwise correlations that cannot jointly hold, the case
`CorrelationMatrixTest.rejectsAMatrixThatIsNotPositiveDefinite` exercises) or from an exactly
singular one (`rejectsAnExactPairwiseCorrelationAsSingular`) - is rejected with a clear
`IllegalArgumentException`, not silently propagated as `NaN` into a Monte Carlo path's shock.

## Why reject rather than handle the singular case

A singular correlation matrix has a well-known fix: a pivoted or rank-revealing Cholesky
decomposition (or an eigendecomposition-based construction) handles the zero pivot correctly by
reordering rows or dropping the redundant dimension. That is real, well-understood numerical
work - not exotic, but also not free, and it buys correctness for a case this milestone has no
real caller for: nothing in this codebase's demos or tests needs an exact `+1`/`-1` pairwise
correlation between two named risk factors. A caller who genuinely wants that degenerate case
has a simpler, exact way to express it anyway - model the two factors as one shared risk
factor instead of two "correlated" ones - which makes the singular case not just rare but
actually redundant with something this engine already supports.

Building the pivoted version speculatively, for a caller that does not exist, is exactly what
`docs/DESIGN_PROPOSAL.md` §A2.9's restraint principle argues against. The honest move is the
one `MarketDataSnapshot` and `SwapModel` already make for missing or invalid data: fail loudly
at the boundary, with a message that says why, rather than let a `NaN` travel three function
calls deep into a simulated shock before it shows up as a nonsensical VaR figure nobody can
trace back to its cause.

## Alternatives rejected

**A pivoted/rank-revealing Cholesky decomposition, built now.** Would handle the singular case
correctly, at real implementation and testing cost, for a scenario nothing in this codebase
exercises. Recorded in `docs/KNOWN_GAPS.md` rather than built speculatively - the same
"revisit the moment a real caller needs it" pattern this codebase already applies elsewhere
(e.g. the former "Exposure is per-venue-instance" entry, closed at M18 once a real test could
exercise two venue instances).

**Clamping a near-singular matrix's Cholesky factor** (treating a diagonal entry below some
small epsilon as exactly zero, and skipping the division). Rejected: it would silently accept
some singular matrices and reject others depending on floating-point noise near the boundary,
which is a worse property than a clean, deterministic rejection rule - "sometimes works,
depending on rounding" is not an improvement on "fails loudly, always, for this case."

**Eigendecomposition instead of Cholesky.** Handles positive semi-definite matrices natively
(project out the zero-eigenvalue directions) and is the standard alternative construction.
Rejected for the same reason as the pivoted Cholesky option: real, correct, and not needed by
any caller this milestone has. Cholesky is also the cheaper of the two to compute per matrix,
which matters less here (computed once, not per path) than it would for a per-path operation,
but was still a reason to prefer it as the simpler default.

## Consequences

**Good.** No `NaN` can reach a simulated path's shock from a correlation input - every failure
mode is caught at `CorrelationMatrix.of(...)`, at construction, before any simulation runs.
The implementation is a single, standard, ~20-line algorithm with no special-casing.

**Costs.** A caller with a genuinely singular correlation structure - including the boundary
case of an exact `+1`/`-1` pairwise correlation - cannot use this class directly and must
either perturb the input slightly (as `CorrelatedMonteCarloVaRCalculatorTest` does, using
`-0.999` rather than `-1.0` to demonstrate near-perfect hedging) or model the redundant factor
as one shared risk factor instead of two. Documented on the class itself, not just here, so a
future caller hits the explanation at the point of failure.
