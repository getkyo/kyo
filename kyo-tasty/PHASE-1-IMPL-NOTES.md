# Phase 1 Implementation Notes

## AllowUnsafe comment cleanup: DONE

All five `// Unsafe: <reason>` comments updated to match the plan's exact wording:

- `Classpath.scala` line 73: `// Unsafe: allSymbols non-effectful read of immutable Ready state`
- `Classpath.scala` (transitionToReady): `// Unsafe: atomic CAS transition Building -> Ready, called from single-threaded Phase C`
- `Classpath.scala` (close): `// Unsafe: atomic CAS Classpath state -> Closed, called from Scope finalizer`
- `ClasspathOrchestrator.scala`: `// Unsafe: stateRef.unsafe.get() read of Building state, single-threaded Phase C merge`
- `SnapshotWriter.scala`: `// Unsafe: stateRef.unsafe.get() non-effectful read of immutable Ready state for snapshot serialization`

## Resolver.scala: RECREATED (file exists, wiring PENDING)

`Resolver.scala` was re-created at `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Resolver.scala`
with the original content from git commit 98416eacf, plus a NOTE block explaining the pending constraint.

**Wiring into lookupClass/lookupPackage: PENDING**

Constraint: `Cache.memo` returns `(A => B < (Async & S)) < Sync`, so the memoized lookup function
has `Async` in its effect row. The public `Reflect.Classpath.findClass` is typed as
`Maybe[Symbol] < (Sync & Abort[ReflectError])` with no `Async`. Wiring the Resolver would require
either:

1. Adding `Async` to `findClass`'s effect row (public API modification -- plan says none), or
2. Materializing `Async` via `Fiber.block` (STEERING prohibits `Fiber.block`), or
3. Restructuring the state machine so Building-state callers block differently.

The current code provides the same reference-equality guarantee via the immutable `HashMap`
returned by Phase C: two reads of the same FQN key always return the same `Symbol` object reference.
Test 19 verifies this with `sym1 eq sym2`.

The supervisor must decide whether to accept the `Async` surface change before wiring is activated
(likely in Phase 2 or a dedicated follow-up).

## Test 19 strengthening: ALREADY DONE (prior session)

Test 19 in `SymbolResolutionTest.scala` already uses `sym1 eq sym2` (reference equality).
The comment in the test explains the HashMap dedup guarantee. No change needed.

## Test 2 (new): Building-state blocking test

Added as "findClass during Building state blocks until Ready then returns reference-equal symbols"
in `SymbolResolutionTest.scala`. The test manually constructs a `Classpath` in `Building` state,
launches a concurrent `findClass` call, transitions to `Ready`, and verifies the caller unblocks
and receives the correct symbol.
