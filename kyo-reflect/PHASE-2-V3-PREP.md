# kyo-reflect Phase 2 v3 Prep

**Status**: Phase 2 is ALREADY COMMITTED at `624a37499343d322fe53125740b7099b681f3a0e`.

This document was written after the commit as a post-hoc prep analysis. It captures what the plan required, what the actual implementation delivered, and what Phase 3 needs to know.

---

## Verbatim API Signatures (Phase 1 state, before Phase 2)

### Resolver.scala (now deleted)

```scala
object Resolver:
    def makeClassLookup(
        cp: Classpath,
        maxSize: Int
    )(using Frame): (String => Maybe[Reflect.Symbol] < (Sync & Async & Abort[ReflectError])) < Sync

    def makePackageLookup(
        cp: Classpath,
        maxSize: Int
    )(using Frame): (String => Maybe[Reflect.Symbol] < (Sync & Async & Abort[ReflectError])) < Sync
```

Both methods created `Cache.memo`-wrapped lookup functions that delegated to `rawLookupClass` / `rawLookupPackage`. The `Async` in the effect row came from `Cache.memo`'s Promise dedup machinery.

### Classpath fields removed

```scala
// Classpath constructor (Phase 1 state):
final class Classpath private[reflect] (
    private[kyo] val stateRef: AtomicRef[Classpath.State],
    private[kyo] val readyLatch: Latch.Unsafe                               // REMOVED in Phase 2
):
    private[kyo] val classLookup: SingleAssign[String => Maybe[Reflect.Symbol] < (Sync & Async & Abort[ReflectError])]   // REMOVED
    private[kyo] val packageLookup: SingleAssign[String => Maybe[Reflect.Symbol] < (Sync & Async & Abort[ReflectError])] // REMOVED
```

The `rawLookupClass` and `rawLookupPackage` methods were also internal and are now gone (replaced by the direct `lookupClass` / `lookupPackage` reading from `Ready.fqnIndex` / `Ready.packageIndex`).

### ClasspathOrchestrator.mergeResults (unchanged by Phase 2)

The call to `Classpath.transitionToReady` at the end of `mergeResults` accepts the same parameter list as before:

```scala
Classpath.transitionToReady(
    cp,
    Chunk.from(allSyms.toSeq),
    Chunk.from(topLevelCls.toSeq),
    Chunk.from(packages.toSeq),
    fqnIndex.toMap,
    packageIndex.toMap,
    canonical,
    Chunk.from(accErrors.toSeq),
    moduleIndex
)
```

`fqnIndex.toMap` and `packageIndex.toMap` are passed directly. The Phase 2 plan described removing "Resolver.makeClassLookup/makePackageLookup build steps in mergeResults". That description was inaccurate: `Resolver.makeClassLookup` was called in `Classpath.allocate` (not in `mergeResults`). `mergeResults` was never changed by either Phase 1 or Phase 2. No changes needed here.

### Reflect.scala extensions (Phase 1 state, before Phase 2)

```scala
extension (cp: Classpath)(using Frame)
    def findClass(fqn: String): Maybe[Symbol] < (Sync & Async & Abort[ReflectError])        // Phase 1
    def findPackage(fqn: String): Maybe[Symbol] < (Sync & Async & Abort[ReflectError])       // Phase 1
    def findClassByBinary(binaryName: String): Maybe[Symbol] < (Sync & Async & Abort[ReflectError])  // Phase 1

def companion(using Frame): Maybe[Symbol] < (Sync & Async & Abort[ReflectError])            // Phase 1
```

After Phase 2:
```scala
extension (cp: Classpath)(using Frame)
    def findClass(fqn: String): Maybe[Symbol] < (Sync & Abort[ReflectError])                // Phase 2
    def findPackage(fqn: String): Maybe[Symbol] < (Sync & Abort[ReflectError])              // Phase 2
    def findClassByBinary(binaryName: String): Maybe[Symbol] < (Sync & Abort[ReflectError]) // Phase 2

def companion(using Frame): Maybe[Symbol] < (Sync & Abort[ReflectError])                   // Phase 2
```

---

## File:Line Anchors for Removed Items

All removals confirmed in commit `624a37499`:

| Item | Action | Location in Phase 1 source |
|------|--------|---------------------------|
| `Resolver.scala` entire file | Deleted | `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Resolver.scala` |
| `readyLatch: Latch.Unsafe` field | Removed | `Classpath.scala` class constructor, line 26 |
| `classLookup: SingleAssign[...]` field | Removed | `Classpath.scala` line 29 |
| `packageLookup: SingleAssign[...]` field | Removed | `Classpath.scala` line 30 |
| Latch creation in `allocate` | Removed | `Classpath.scala` object, `allocate` method body |
| `Resolver.makeClassLookup` call in `allocate` | Removed | `Classpath.scala` object, `allocate` body lines 189-196 |
| `Resolver.makePackageLookup` call in `allocate` | Removed | `Classpath.scala` object, `allocate` body |
| `cp.readyLatch.release()` in `transitionToReady` | Removed | `Classpath.scala` object, `transitionToReady` body line 220 |
| `rawLookupClass` method | Removed | `Classpath.scala` private[kyo] method, lines 51-59 |
| `rawLookupPackage` method | Removed | `Classpath.scala` private[kyo] method, lines 66-74 |
| `classLookup.get()(fqn)` delegation in `lookupClass` | Replaced | `Classpath.scala` lines 82-86 |
| `packageLookup.get()(fqn)` delegation in `lookupPackage` | Replaced | `Classpath.scala` lines 92-96 |
| `import kyo.internal.reflect.symbol.SingleAssign` | Removed | `Classpath.scala` top imports |
| `Async` in `findClass` effect row | Removed | `Reflect.scala` line 913 |
| `Async` in `findPackage` effect row | Removed | `Reflect.scala` line 914 |
| `Async` in `findClassByBinary` effect row | Removed | `Reflect.scala` line 931 |
| `Async` in `companion` effect row | Removed | `Reflect.scala` line 570 |

---

## Edge Cases

### Latch import cleanup

`Latch.Unsafe` was referenced in `Classpath.scala` only in the constructor signature and the `allocate` / `transitionToReady` methods. Once those are removed, `Latch` is no longer imported and the `Latch.Unsafe` field is gone. The `import kyo.*` at the top of `Classpath.scala` imports `Latch` as a wildcard, so no explicit Latch import needed cleanup. Confirmed clean: no lingering `Latch` references.

### Resolver import cleanup

`Resolver` was imported only inside `Classpath.allocate` by direct reference `Resolver.makeClassLookup(...)`. Once those calls are removed, there are no `Resolver` references anywhere in `shared/src/main/scala`. Confirmed: `grep -r "Resolver" kyo-reflect/shared/src/main/scala` returns zero hits.

### ClasspathOrchestrator.mergeResults: no interposition

The plan description ("removing Resolver.makeClassLookup/makePackageLookup build steps in mergeResults") was inaccurate. `mergeResults` never called Resolver. The Resolver calls were in `Classpath.allocate`. `mergeResults` always passed `fqnIndex.toMap` and `packageIndex.toMap` directly to `transitionToReady`. No changes were needed in `mergeResults` for Phase 2.

### companion method Async removal

The plan does not mention `companion` in Phase 2. However `companion` had `Sync & Async & Abort[ReflectError]` because it called `home.get().lookupClass(...)`, which returned `Async`. With `lookupClass` now returning `Sync & Abort`, `companion` was also updated. This is the correct behavior; the plan's "Public API modifications" list omits `companion` but the change is implied by `lookupClass`'s effect reduction.

**WARN**: The plan explicitly lists only `findClass`, `findPackage`, `findClassByBinary` in Phase 2's "Public API modifications". The `companion` method's Async removal is an undocumented change but is correct. It is auditable in the commit diff.

### SymbolResolutionTest: 2 tests deleted, plan said 1

The plan states "Deleted: 1 (SymbolResolutionTest Test 2)." In practice, both Test 2 (Building-state concurrent latch) and Test 20 (Cache.memo dedup with N=5) were deleted. The commit correctly notes both are obsolete because both depend on `readyLatch` / Cache.memo semantics which are now gone.

The plan's test count table states Phase 2 delta as "-1" and cumulative as "245". The actual implementation deleted 2 tests, yielding a runtime count of **244** (246 - 2). This is a deviation from the plan's 245 target.

**WARN**: Phase 2 test count is 244, not 245 as the plan states. The extra deletion (Test 20) is correct and justified (Cache.memo dedup is gone so the test has no valid contract). The plan's "-1" count was wrong because it overlooked Test 20's dependency on the same mechanism.

---

## Test Impact: SymbolResolutionTest Tests 2 and 20

### Test 2 (Phase 1 label) - deleted in Phase 2

Test description: "concurrent findClass calls during Building state both receive reference-equal symbols after Ready"

The test allocated a `Classpath` manually in Building state, launched `openInto` as a background fiber, then ran two concurrent `findClass` calls with `Async.zip`. It asserted `sym1 eq sym2` via the Building-state latch semantics.

Why deleted: `readyLatch` no longer exists. `lookupClass` in Building state now returns `Abort.fail(ClasspathBuilding)` immediately. The test's mechanism (concurrent suspension awaiting `readyLatch.release`) is gone.

### Test 20 (Cache.memo dedup) - deleted in Phase 2

Test description: "N=5 concurrent Building-state findClass calls all resolve to the same Symbol instance (Cache.memo dedup)"

The test ran 5 concurrent `findClass` calls on a Building-state classpath (via background `openInto` fiber) and asserted all 5 returned reference-equal symbols, claiming Cache.memo Promise dedup was operative.

Why deleted: Both the `readyLatch` (which suspended the 5 callers during Building state) and `Cache.memo` (which would have deduplicated in-flight resolutions) are now gone. The test's contract is vacuous.

**Note on Test 19**: Retained. It tests that two concurrent `findClass` calls on a READY classpath return reference-equal Symbols. This contract holds via immutable HashMap identity and does not depend on Cache.memo. The comment was updated to reflect "HashMap identity" rather than "Cache.memo dedup".

---

## Current State of Phase 2 (post-commit verification)

All Phase 2 plan supervisor checks pass:

- `Resolver.scala` deleted: confirmed `ls ...query/` shows no Resolver.
- `grep -r "readyLatch|classLookup|packageLookup|Resolver" kyo-reflect/shared/src/main/scala` returns zero hits.
- `findClass` signature: `Maybe[Symbol] < (Sync & Abort[ReflectError])` with no `Async`.
- `findPackage` signature: same.
- `findClassByBinary` signature: same.
- `companion` signature: `Maybe[Symbol] < (Sync & Abort[ReflectError])` with no `Async` (undocumented bonus cleanup).
- Test 19 comment updated to reflect HashMap identity.
- JS and Native: commit message confirms clean compile.

---

## Concerns

**WARN-1**: Plan says Phase 2 cumulative test count is 245. Actual is 244 (2 tests deleted: Test 2 and Test 20). Both deletions are correct. The plan was wrong about the delta being -1. Downstream phases (Phase 3 onward) should use 244 as the baseline, not 245. The Phase 3 plan's "Expected: 245 tests passing" target needs to be read as 244.

**WARN-2**: The plan does not mention `companion`'s Async removal in Phase 2's API modifications list. The change was made (correctly) and is in the commit, but the plan omission means the Phase 3 plan's statement "restore `findClass`, `findPackage`, `findClassByBinary` signatures" did not anticipate `companion` needing the same treatment. Phase 3 should verify `companion` is already updated (it is).

**NOTE-1**: `ClasspathOrchestrator.mergeResults` was not changed in Phase 2 at all, contrary to the plan's description of removing "Resolver.makeClassLookup/makePackageLookup build steps in mergeResults." This is because the plan description was inaccurate: those calls were in `Classpath.allocate`, not `mergeResults`. `mergeResults` was always calling `transitionToReady` with direct map arguments.

**NOTE-2**: The `Latch` import in `Classpath.scala` was covered by `import kyo.*`, so there was no explicit `import Latch` to remove. The cleanup was automatic.
