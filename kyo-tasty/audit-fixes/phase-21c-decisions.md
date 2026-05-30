# Phase 21c Decisions

## API Reconciliations

### D1: ClasspathRefTest exception messages adapted

Plan said `IllegalStateException` containing "not assigned" (unassigned get) and "already assigned"
(double assign). Actual messages from `SingleAssign`:

- `get()` when unset: `"SingleAssign not yet set"`
- `set()` when already set: `"SingleAssign already set"`

Tests adapted to assert `ex.getMessage.contains("not yet set")` and `ex.getMessage.contains("already
set")` respectively.

### D2: UnresolvedRefTest: no make factory exists

Plan described `UnresolvedRef.make(fqn, classpathRef)` producing a `Tasty.Symbol` with `kind ==
Unresolved`. Actual API: `UnresolvedRef` is a `final case class` in `kyo.internal.tasty.query` with
fields `fqn: String` and `replaceSlot: SingleAssign[Tasty.Type]`. No factory method and no direct
Symbol production.

Adaptation: test directly constructs `new UnresolvedRef(fqn, new SingleAssign[Tasty.Type])` and
asserts `ref.fqn == "missing.X"` and `ref.replaceSlot.isSet == false`. This verifies the data class
preserves its constructor arguments and starts with an unset slot, which is the structural
precondition for Phase C resolution.

### D3: TastyStatTest: `Attributes` imported from `kyo.stats`

`Attributes.empty` is defined in `kyo.stats.Attributes`, not re-exported at top level. Added
`import kyo.stats.Attributes` to `TastyStatTest`. The test calls
`TastyStat.scope.traceSpan("test", Attributes.empty) { ... }` which returns `A < (Sync & S)`;
the `run {}` harness handles the Sync effect.

### D4: PerfCountersTest: no incJarOpen / incEntryRead helpers

Plan referenced `incJarOpen` and `incEntryRead` methods that do not exist. `PerfCounters` exposes
raw `AtomicInteger` fields `jarOpenCount` and `entryReadCount`. Test adapted to call
`PerfCounters.jarOpenCount.incrementAndGet()` and `PerfCounters.entryReadCount.incrementAndGet()`
directly in a loop.
