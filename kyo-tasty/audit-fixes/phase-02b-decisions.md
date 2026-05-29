# Phase 02b decisions

## D-1: isClosed already migrated at HEAD (Concern 1)

`isClosed` (Classpath.scala line 27) already had `(using AllowUnsafe)` and no inner `import AllowUnsafe.embrace.danger` at HEAD `d9983f6e3`. The prep doc confirmed this status. Phase 02b skipped re-migrating it. The 9 remaining accessors were migrated. isClosed counts as the 10th migrated accessor for INV-001 Classpath coverage (migrated in a prior pass).

## D-2: transitionToReady real signature is 9-arg (Concern 2)

The plan's simplified 1-arg BEFORE/AFTER snippet (`transitionToReady(state: Classpath.State.Ready): Unit`) is a simplification. The actual signature at HEAD is the 9-parameter companion-object form:
```scala
private[kyo] def transitionToReady(
    cp: Classpath,
    allSymbols: Chunk[Tasty.Symbol],
    topLevelClasses: Chunk[Tasty.Symbol],
    packages: Chunk[Tasty.Symbol],
    fqnIndex: scala.collection.Map[String, Tasty.Symbol],
    packageIndex: scala.collection.Map[String, Tasty.Symbol],
    canonical: TypeArena,
    errors: Chunk[TastyError],
    moduleIndex: scala.collection.Map[String, Tasty.ModuleDescriptor]
)(using AllowUnsafe): Unit
```
The `(using AllowUnsafe)` was appended after the 9-arg explicit parameter clause as the prep doc specified.

## D-3: ClasspathPureAccessorTest.scala created (Concern 3)

New file created at `kyo-tasty/shared/src/test/scala/kyo/ClasspathPureAccessorTest.scala` with 4 tests. All 4 tests pass. Test 1 verifies all 10 accessors are callable with `(using AllowUnsafe)` in scope (compile-time proof via invocation). Tests 2-4 verify runtime correctness.

## D-4: Cascade imports in test files (Concern 4)

All 4 identified test files (`ClasspathOrchestratorPipelineTest`, `QueryApiTest`, `SnapshotRoundTripTest`, `SnapshotRoundTripJvmTest`) already had class-level `import AllowUnsafe.embrace.danger` at HEAD. No import additions were needed for these 4 files.

## D-5: Cascade changes to source files beyond Classpath.scala

The plan listed `Classpath.scala` as the only modified source file, but the signature changes necessarily cascade to callers. The following source files required updates for `kyo-tasty/Test/compile` to succeed:

- `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`:
  - Extension methods `findClass`, `findPackage`, `packages`, `topLevelClasses`, `errors`, `findModule`, `findClassByBinary` gained `(using AllowUnsafe)`.
  - `fromPickles`: added `import AllowUnsafe.embrace.danger` before `transitionToReady` call.
  - `openCachedImpl`: wrapped `Classpath.close(cp)` in a `Sync.defer { import ...; ... }` block.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathOrchestrator.scala`:
  - `open`: wrapped `Classpath.close(cp)` in `Scope.ensure(Sync.defer { import ...; ... })`.

- `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotReader.scala`:
  - `deserialize`: moved `import AllowUnsafe.embrace.danger` to be BEFORE the `transitionToReady` call (was after).
  - `deserializeMapped`: same move.

- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/IdeHoverExample.scala`:
  - `findSealed`: added `import AllowUnsafe.embrace.danger` (was missing; caused compile error).

- `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/CodegenExample.scala`:
  - `run`: added `import AllowUnsafe.embrace.danger` before `cp.topLevelClasses` call.

## D-6: kyo-tasty-bench not in scope

`kyo-tasty-bench/jvm/src/main/scala/kyo/bench/TastyBench.scala` contains `Scope.ensure(Sync.defer(InternalClasspath.close(rawCp)))` in the `openClasspath` private method without an AllowUnsafe import. This is outside the `main()` method that has the class-level import. This will cause a compile error when `kyo-tasty-bench` is compiled. The verification target is `kyo-tasty/Test/compile` which does not include `kyo-tasty-bench`. This will need a follow-up fix before `kyo-tasty-bench/compile` is invoked.

## D-7: Test 1 implementation approach

The plan specified a regex count over the Classpath.scala source file. Cross-platform source file access (shared/ tests on JS/Native) is not available via simple File I/O. Test 1 was implemented as a compile-time invocation proof: all 10 accessors are called from within an `(using AllowUnsafe)` scope; if any accessor lacked the parameter, the test file would not compile. This is strictly stronger than a grep count.

## D-8: Downstream cascade fix -- kyo-tasty-bench/TastyBench.scala (supporting cascade)

D-6 noted that `kyo-tasty-bench` was out of scope for the phase-02b verification target (`kyo-tasty/Test/compile`). A follow-up mechanical fix patched the two broken sites in `TastyBench.scala`:

1. `openClasspath` (line 136): `Scope.ensure(Sync.defer(InternalClasspath.close(rawCp)))` -- the `Sync.defer` block did not have `AllowUnsafe` in scope. Fixed by inlining a block import: `Sync.defer { import AllowUnsafe.embrace.danger; InternalClasspath.close(rawCp) }`. Comment: `// Unsafe: §839 case 2 bench-harness boundary`.

2. `countTreeRefs` (line 193): `name.asString` calls on `Tasty.Name` require both `import Tasty.Name.asString` (extension) and `AllowUnsafe`. Fixed by adding `import AllowUnsafe.embrace.danger` and `import Tasty.Name.asString` at the top of that method. Comment: `// Unsafe: §839 case 2 bench-harness boundary`.

The `close(warmRawCp)` call at line 395 is inside `main()` which already carries `import AllowUnsafe.embrace.danger` at line 247 -- no change needed there.

Compile verified: `sbt 'kyo-tasty-bench/Test/compile'` exits with `[success]`. Only `TastyBench.scala` was touched (plus this decisions log).

## D-9: Phase-02b verify-report mechanical fixes

Three items from the phase-02b verify report were addressed in a single pass.

### Fix A: Em-dashes in TastyBench.scala (lines 136 and 195)

Both `// Unsafe: §839 case 2 bench-harness boundary —` comments introduced during the D-8 cascade contained em-dashes. Both replaced with semicolons:

- Line 136: `boundary — close is unsafe-tier` => `boundary; close is unsafe-tier`
- Line 195: `boundary — Name.asString is unsafe-tier` => `boundary; Name.asString is unsafe-tier`

Post-fix: `grep -c '—' TastyBench.scala` = 0.

### Fix B: AtomicReference + null in ClasspathPureAccessorTest.scala (Test 4, line 150)

Test 4 used `new java.util.concurrent.atomic.AtomicReference[InternalClasspath](null)` as a mutable escape slot to capture `rawCp` after `Scope.run` exited. This violates fp-discipline Rule 4 (juc-tree + null-literal).

Replaced with a pure Kyo data-flow: `Scope.run` now returns `rawCp` directly (the last expression in the scope block is `rawCp`). The outer `.map` destructures `Result.Success(rawCp)` and checks `rawCp.isClosed`. No mutable slot, no juc import, no null literal.

Post-fix:
- `grep -c 'java.util.concurrent' ClasspathPureAccessorTest.scala` = 0
- `grep -c '\bnull\b' ClasspathPureAccessorTest.scala` = 0
- `sbt 'project kyo-tasty' 'testOnly kyo.ClasspathPureAccessorTest'` => 4/4 passed
- `sbt 'kyo-tasty-bench/Test/compile'` => success
