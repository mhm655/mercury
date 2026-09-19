# Architecture

[DESIGN_PROPOSAL.md](DESIGN_PROPOSAL.md) argues for the shape of this engine before most of it
existed, and [MILESTONES.md](MILESTONES.md) records what each milestone actually built and
what its reviews found. Neither is a map of the finished thing. This is: the modules, the
package layers inside the engine, and the two or three mechanisms that do most of the work,
each pinned to the tests that keep it true rather than asserted in prose alone.

## Modules, and the one-way dependency between them

```
   mercury-app                         mercury-benchmarks
   composition root, CLI,              JMH microbenchmarks,
   console rendering                   its own module so a
        |                              slow suite never runs
        | depends on                   with `mvn test`
        v                                    |
   mercury-engine  <--------------------------
   the domain: matching, pricing, portfolio,
   risk, simulation, execution, trade lifecycle.
   No framework, no I/O, no console, no clock.
```

`mercury-engine` depends on nothing project-specific and nothing that reaches outside the JVM -
no Spring, no JPA, no Jackson (`LayeringRulesTest.engineIsFrameworkFree`), no system clock
outside `com.mercury.core.time` (`nothingReadsTheSystemClock`). `mercury-app` is the only module
that knows a console exists (`ValuationReport`'s own javadoc says so) and the only one that
assembles the engine's objects by hand (`DemoScenario`, "the composition root"). Dependency runs
one way - the app imports the engine, never the reverse - which is what
`AppLayeringRulesTest`'s javadoc means by "the app knows about the engine and never the
reverse": the engine's own dead-code rule cannot see into `mercury-app`, so that module carries
an identical rule scoped to itself. `mercury-benchmarks` depends on the engine the same way and
runs under `mvn -pl mercury-benchmarks exec:java`, never under the default test phase - see
[BENCHMARKS.md](BENCHMARKS.md) for methodology.

This is also the shape a Spring module would slot into without touching the engine (§10.2 of
the design proposal): a fourth box depending on `mercury-engine` the same way `mercury-app`
does, wiring the same constructors from configuration instead of by hand.

## Layers inside the engine

Package dependencies run one way, enforced by `LayeringRulesTest.noPackageCycles` (no cycles,
anywhere) and two rules naming specific directions explicitly, "as the layers they describe
come into existence". This is the real import graph, not an idealised one - every arrow below
is a package that actually appears in another's `import` statements, checked by grep rather
than remembered:

```
core (money, ids, time, math) - everything below depends on this; it depends on nothing

instrument   curve   matching   trade   event      <- depend on core alone
    |          |
    +----+-----+
         v
     marketdata                                    <- curve + core

  instrument   curve   marketdata
       \         |         /
        +--------+--------+
                 v
              pricing                               <- PricingService: type-keyed dispatch
                 |
     trade ------+
       \         v
        \    portfolio                              <- ledger, cost basis, cash
         \       |
          +------+------+
                 v
               risk                                 <- Greeks, VaR, scenarios, limits
                 |
       +---------+---------+
       v                   v
   execution           simulation                   <- venues, lifecycle    Monte Carlo
   (+ event, matching)
```

`execution` and `simulation` sit at the top and do not depend on each other: `execution` is the
only package that also reaches into `matching` and `event`, and `simulation` is the only one
that reaches back into `pricing` directly (a `MonteCarloOptionModel` is itself a `PricingModel`)
as well as through `risk`.

Two directions are asserted by name rather than left to the general no-cycles rule, because a
direction can be right today and drift tomorrow without ever becoming a cycle:

- **`trade` and `marketdata` do not depend on `risk` or `execution`**
  (`riskDependsOnTradeNotTheOtherWayAround`). `RiskLimit` judges a proposed trade's projected
  exposure; the trade and the market data it is judged against must stay usable without a risk
  engine or an execution venue existing at all.
- **`risk`, `pricing` and `marketdata` do not depend on `simulation`**
  (`simulationDependsOnRiskNotTheOtherWayAround`). Monte Carlo VaR generates scenarios and hands
  them to `HistoricalVaRCalculator`; a simulation consumes risk and pricing, and neither should
  need to know a simulation exists.

`instrument`, `curve` and `matching` sit side by side rather than one on top of another - a
`Bond` does not import `OrderBook`, and neither imports `YieldCurve`. What ties an instrument to
how it prices is a *type*, not an import: see the registry below.

## The registry: one mechanism, checked by two independent proofs

`PricingService` maps a `FinancialInstrument`'s runtime type to the `PricingModel` registered
for it - the **Expression Problem** answer this project picked over an `instrument.price()`
method, an `instanceof` chain, or a Visitor (§5.1 of the design proposal names all three and
why each was rejected). §5.1 also says "naming it explicitly in ARCHITECTURE.md is itself a
signal" - written before this file existed, which is as good a reason as any for this section
to open with the same name it asked for. Adding a sixth instrument costs one class, one model
and one registration line, and never touches the four that already exist.

That claim is checked twice, at two different distances from the truth:

- **`PricingServiceTest`** adds a sixth pricer *inline, in the test*, and asserts it dispatches
  correctly alongside the other five. Cheap, fast, and proves the mechanism.
- **[EXTENSIBILITY.md](EXTENSIBILITY.md)** adds a real sixth instrument - an interest-rate cap,
  which pays a kind of cashflow (contingent on a rate distribution) the engine had never
  modelled - as one commit containing only new files. `git show --stat` on that commit is the
  proof a reader can run themselves; §7.1 of the design proposal explains why a cap was chosen
  specifically because an easier addition would have proven nothing the first five instruments
  did not already show.

Routing to a venue uses the same shape a level up: `FinancialInstrument.tradability()` returns
a `TradabilityProfile`, and `ExecutionRouter` sends `EXCHANGE_TRADED` instruments to
`OrderBookVenue` and `OVER_THE_COUNTER` ones to `OtcNegotiationVenue` - polymorphism on a
declared property, never an `instanceof` chain over instrument types.

## One mechanism, three features

`MarketDataSnapshot` is immutable; `MarketShock` describes a change to one (`Composite`, so
shocks combine); `SensitivityCalculator` applies a shock and revalues. That single mechanism -
build a shocked snapshot, reprice, difference - is the entire implementation of three things
that look unrelated from the outside:

```
                     MarketDataSnapshot.withShock(shock)
                                  |
                                  v
                          re-price the portfolio
                                  |
                                  v
                    (shocked value) - (base value)
                    /              |              \
                   /               |               \
              DELTA/GAMMA/     a named          Monte Carlo VaR:
              VEGA/FX DELTA/   Scenario's       one shock per
              DV01 (M4/M10)    impact (M11)     simulated path (M12/M13)
```

An infinitesimal bump produces a Greek; a scenario-sized shock produces a stress-test impact; a
different random shock per Monte Carlo path produces a VaR distribution. §5.3 of the design
proposal names this as the reason pricing is a registry and shocks are composable at all - M4
proves the mechanism on one Greek, and every later milestone that needed a new kind of risk
(M10's Gamma and Vega, M11's scenarios, M12/M13's Monte Carlo VaR) reused it rather than adding
parallel machinery.

## Execution: an instruction becomes a position

```
OrderBookInstruction / OtcInstruction
         |
         v
ExecutionRouter -----------------------------+
   |  (by TradabilityProfile)                |
   v                                         v
OrderBookVenue                     OtcNegotiationVenue
(price-time-priority                (prices via PricingService,
 matching, one writer                applies a spread, checks a
 per book - §5.6 a))                 RiskLimit before executing)
   |                                         |
   +------------------+----------------------+
                       v
                 Trade (state machine:
                 NEW -> VALIDATED -> BOOKED ->
                 EXECUTED -> CONFIRMED -> SETTLED,
                 append-only audit trail)
                       |
                       v
              events.publish(TradeExecuted)
                       |
                       v
              LedgerKeeper.accept(event)
              (books only this owner's trades,
               ignores the other side of a fill)
                       |
                       v
                PortfolioLedger
              (lots, cost basis, cash,
               realised P&L - immutable)
                       |
                       v
                  toPortfolio()
                       |
                       v
          PortfolioValuationService.value(...)
                       |
                       v
         PortfolioValuation -> ValuationReport
```

Both venues announce every execution on an `EventBus` rather than returning it to a caller who
must remember to book it - `LedgerKeeper` is the one place the "book only your own trades" rule
lives, tested once (`LedgerKeeperTest`) instead of re-implemented in every demo that needs it.
`EndToEndDemo` (the `walkthrough` command) and `DemoScenario.ledger()` (the golden-master
report, since M14) are two different scenarios run through the identical path - proof that the
diagram above is what actually executes, not an idealisation of it.

## Reproducibility, end to end

Every box in the diagram above is a pure function of its inputs, and the one thing that could
break that - the wall-clock - enters the engine through exactly one interface,
`SimulationClock`, checked by `LayeringRulesTest.nothingReadsTheSystemClock` across sixteen
real and easily-missed entry points (`ZonedDateTime.now()`, `new Date()`,
`Calendar.getInstance()`, and more - see the A-4 defect in
[KNOWN_GAPS.md](KNOWN_GAPS.md) for why the list needed to be that specific).

`GoldenMasterTest` is the artifact that makes reproducibility a checked property rather than an
architectural aspiration: a fixed clock, a fixed seed where randomness is involved, and every
snapshot immutable, so the same scenario run twice - or run a year apart, on a different machine
- produces byte-identical output. §7.2 of the design proposal calls this a regulatory-realism
signal as much as a testing one: a real trading system has to be able to explain why a number
was what it was on a given day, which this engine can only claim because the test in
`GoldenMasterTest` would fail the moment it stopped being true.

## Where to look next

| Question | Where |
|---|---|
| Why this design, and what was rejected | [DESIGN_PROPOSAL.md](DESIGN_PROPOSAL.md) |
| What each milestone delivered, and what its reviews found | [MILESTONES.md](MILESTONES.md) |
| What's deliberately missing, and why | [KNOWN_GAPS.md](KNOWN_GAPS.md) |
| A specific decision, recorded when it was made | [docs/adr](adr) |
| Real numbers on stated hardware | [BENCHMARKS.md](BENCHMARKS.md) |
| The extensibility claim, proven rather than asserted | [EXTENSIBILITY.md](EXTENSIBILITY.md) |
