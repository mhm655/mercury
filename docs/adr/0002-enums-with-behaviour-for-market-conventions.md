# ADR 0002 — Enums with behaviour for market conventions

**Status:** Accepted
**Milestone:** M1

## Context

Day count conventions (ACT/360, ACT/365F, 30/360, ACT/ACT) and business-day conventions
(Following, Modified Following, Preceding, Modified Preceding, Unadjusted) each need
polymorphic behaviour: given the same two dates, each produces a different answer.

The textbook object-oriented answer is an interface plus one implementation class per
convention — which is the Strategy pattern, and which this project uses elsewhere for
pricing models.

## Decision

Model them as Java enums whose constants override an abstract method.

```java
public enum DayCountConvention {
    ACT_360("Actual/360") {
        @Override public double yearFraction(LocalDate start, LocalDate end) {
            return actualDays(start, end) / 360.0;
        }
    },
    // ...
    public abstract double yearFraction(LocalDate start, LocalDate end);
}
```

## Rationale

The distinguishing question is whether the set is **open or closed**, and whether
implementations can be supplied at runtime.

Pricing models are an open set: the whole point of the pricing architecture is that a new
model can be registered without touching existing code, and that two models can price the
same instrument. That is a Strategy, and it needs an interface.

Day count conventions are a closed set fixed by market convention. Nobody invents one at
runtime. They have no state, no dependencies, and differ only in a single arithmetic
expression. For that shape an enum gives:

- **Exhaustive `switch`.** The compiler reports any switch that fails to handle a new
  constant. An interface gives a `default` branch that silently swallows it.
- **Free identity semantics.** Comparison, `EnumMap`, `EnumSet` and serialisation all work
  without writing `equals`, `hashCode` or a registry.
- **Discoverability.** `DayCountConvention.values()` enumerates the whole domain, which the
  test suite uses to assert invariants across every convention at once.
- **Less code.** Five short constants instead of five files plus an interface plus a
  factory to resolve a name to an instance.

Using Strategy here would be the pattern applied for its own sake — the project's stated
rule is that patterns must solve a real problem, and the problem Strategy solves
(runtime-pluggable, open-ended algorithms) does not exist for these types.

## Consequences

**Good.** Compact, compiler-checked, and cheap. Tests can iterate `values()` and assert
that, for instance, every convention returns zero over a zero-length period and rejects a
backwards period — invariants that would otherwise be restated per class.

**Cost.** A convention genuinely outside the set cannot be added by a downstream user
without editing this enum. Accepted deliberately: these are market standards, not
extension points, and treating them as extensible would be modelling a flexibility the
domain does not have.

**Boundary noted.** If a future instrument needed a parameterised convention — a day count
carrying its own reference period, say — that would be a real change in shape and would
justify moving to an interface. Nothing in the five planned instruments needs it.

## Related

`HolidayCalendar` went the other way: it *is* an interface, because calendars genuinely
compose at runtime (a cross-currency trade observes the union of two centres' calendars)
and because holiday sets are data loaded per centre rather than a fixed set of behaviours.
Same domain area, opposite decision, for reasons that are visible in each case.
