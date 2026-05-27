# Phase 2 v3 Audit

Commit: `624a37499`
Plan reference: `execution-plan-v3.md` Phase 2

---

## Checklist Results

### Resolver.scala deleted

PASS. `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/` no longer contains `Resolver.scala`. The directory listing confirms only `Classpath.scala`, `ClasspathOrchestrator.scala`, `ClasspathRef.scala`, `ClasspathTestHelpers.scala`, `FileSource.scala`, and `UnresolvedRef.scala` remain. `grep -r "Resolver"` across the entire `shared/src/main/scala` tree returns zero hits.

### Classpath.scala: classLookup, packageLookup, readyLatch removed

PASS. `grep -n "classLookup|packageLookup|readyLatch|Latch"` in `Classpath.scala` returns zero hits. The `Classpath` class body contains no `SingleAssign` fields for lookup tables and no `Latch.Unsafe` field. The `allocate` factory creates only an `AtomicRef[State]`; `transitionToReady` now accepts `fqnIndex`/`packageIndex` as parameters passed directly and writes them into `State.Ready`.

### lookupClass / lookupPackage return Sync & Abort[ReflectError] (no Async)

PASS. Both methods read directly from the `fqnIndex`/`packageIndex` immutable `HashMap` in the `Ready` state via a pattern match on `stateRef.get`:

```scala
private[kyo] def lookupClass(fqn: String)(using Frame): Maybe[Reflect.Symbol] < (Sync & Abort[ReflectError]) =
    stateRef.get.map:
        case s: Classpath.State.Ready    => Maybe(s.fqnIndex.get(fqn).orNull)
        case _: Classpath.State.Building => Abort.fail(ReflectError.ClasspathBuilding)
        case Classpath.State.Closed      => Abort.fail(ReflectError.ClasspathClosed)
```

No `Async` in either signature. No latch suspension. The `Building` branch is now a hard fail (defense-in-depth; user code cannot reach a `Building` classpath through the public API).

### Reflect.scala: findClass / findPackage / findClassByBinary / companion effect rows

PASS. Extension method signatures (lines 913-934):

```
def findClass(fqn: String): Maybe[Symbol] < (Sync & Abort[ReflectError])
def findPackage(fqn: String): Maybe[Symbol] < (Sync & Abort[ReflectError])
def findClassByBinary(binaryName: String): Maybe[Symbol] < (Sync & Abort[ReflectError])
def findModule(name: String): Maybe[ModuleDescriptor] < (Sync & Abort[ReflectError])
def topLevelClasses: Chunk[Symbol] < (Sync & Abort[ReflectError])
def packages: Chunk[Symbol] < (Sync & Abort[ReflectError])
```

`companion` (lines 570-603) has effect row `Maybe[Symbol] < (Sync & Abort[ReflectError])` with no `Async`. The only `Async` remaining in the file is in the `open`/`openCached` factory methods (correct: those still require `Async` for the orchestrator fibers).

### SymbolResolutionTest: test deletions

WARN. The plan spec says `-1` test (deleting only Test 2 "concurrent findClass during Building state"). The implementation deleted **2** tests: Test 2 and Test 20 ("N=5 concurrent Building-state findClass calls -- Cache.memo dedup"). The git diff confirms both were removed. The plan's summary table also says `-1` (net delta for Phase 2 = -1, cumulative 245). The actual result is **244 tests** (246 - 2).

The deletion of Test 20 is substantively correct: that test existed solely to verify Cache.memo Promise deduplication, which was deleted with the Resolver. There is no runtime behavior it could have tested after Phase 2. The plan simply undercounted by 1. See NOTE below.

Test 19 comment was updated to reflect HashMap identity instead of Cache.memo. The test body is unchanged and still verifies reference equality via `sym1 eq sym2`. PASS.

### Test count

WARN (see above). Actual: **244 tests on JVM**, not 245 as specified.

```
[info] Total number of tests run: 244
[info] Suites: completed 29, aborted 0
[info] Tests: succeeded 244, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
```

### JS / Native cross-platform

PASS.

```
JS:     201 tests run, 201 succeeded, 40 ignored (jvmOnly). All passed.
Native: 201 tests run, 201 succeeded, 40 ignored (jvmOnly). All passed.
```

JS and Native ignore the 40 jvmOnly tests (classfile-reader tests and Java-runtime-dependent tests tagged `jvmOnly`). That leaves 201 executable tests on each. No failures, no panics.

---

## Findings

### WARN-1: Test count is 244, not 245

**Category**: WARN

The plan specified `Phase 2 delta = -1, cumulative = 245`. The implementation deleted two tests (Test 2 and Test 20 from `SymbolResolutionTest`), yielding 244. The second deletion (Test 20) was substantively correct: it tested Cache.memo Promise deduplication, which no longer exists. The plan was written before noticing that Test 20 was equally obsolete. The 244 baseline carries forward to Phase 3.

**Action required**: Phase 3 prep and subsequent phase plans must use 244 as the baseline, not 245. The Phase 3 plan already states `Expected: 245 tests passing` -- that number is off by 1.

### NOTE-1: lookupClass/lookupPackage now fail-fast on Building state

**Category**: NOTE

The pre-Phase-2 code with readyLatch would suspend on `Async.sleep`-style blocking when called during `Building` state. The new code returns `Abort.fail(ClasspathBuilding)` immediately. This is a behavior improvement (no hidden suspension path) and is correct because the `open` factory never returns the `Classpath` handle to user code until after `transitionToReady` completes. The `Building` branch is defense-in-depth.

### NOTE-2: allTopLevelClasses / allPackages still use checkOpen + Sync & Abort[ReflectError]

**Category**: NOTE

`allTopLevelClasses` and `allPackages` still call `checkOpen` and carry `Sync & Abort[ReflectError]`. This is the correct v2 baseline; Phase 3 will convert these to pure accessors. No action needed here.

### NOTE-3: ClasspathOrchestrator has no Resolver references

**Category**: NOTE (positive confirmation)

`grep -r "Resolver" kyo-reflect/shared/src/main/scala` returns zero hits. `ClasspathOrchestrator` no longer calls any `Resolver.make*` step; it passes `fqnIndex` and `packageIndex` maps directly to `Classpath.transitionToReady`.

---

## Summary

| Check | Result |
|---|---|
| Resolver.scala deleted | PASS |
| classLookup / packageLookup / readyLatch removed from Classpath | PASS |
| lookupClass / lookupPackage: Sync & Abort (no Async) | PASS |
| findClass / findPackage / findClassByBinary / companion: Sync & Abort (no Async) | PASS |
| Test 2 deleted from SymbolResolutionTest | PASS |
| Test 19 still passes (HashMap identity) | PASS |
| JVM test count | WARN: 244 (plan expected 245; Test 20 also deleted) |
| JS pass (jvmOnly ignored) | PASS: 201/201 |
| Native pass (jvmOnly ignored) | PASS: 201/201 |

**Blockers**: 0
**Warns**: 1 (test count off by 1; baseline for Phase 3 is 244 not 245)
**Notes**: 3
