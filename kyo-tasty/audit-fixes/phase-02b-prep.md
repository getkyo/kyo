# Phase 02b prep

Phase: 02b — Propagate AllowUnsafe through Classpath pure accessors
Prepared: 2026-05-29
Source file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala`

---

## Verbatim API signatures (10)

All 10 are in `Classpath.scala` (lines noted are from the file read at HEAD
d9983f6e34550bb30e2b2a789b5e58aadf3f5066).

### 1. `isClosed` (lines 27-28)

```scala
private[kyo] def isClosed(using AllowUnsafe): Boolean =
    stateRef.unsafe.get() == Classpath.State.Closed
```

Context (lines 23-29):
```
/** Returns true if the classpath is in the Closed state. Used by Symbol.body for the explicit pre-decode guard.
  *
  * Requires AllowUnsafe because it reads the AtomicRef without an effect context.
  */
private[kyo] def isClosed(using AllowUnsafe): Boolean =
    stateRef.unsafe.get() == Classpath.State.Closed
```

STATUS: Already carries `(using AllowUnsafe)`. No `import AllowUnsafe.embrace.danger` in body.
Phase 02b must NOT touch this one (it is already migrated). Count it as "done at HEAD".

### 2. `pureClass` (lines 68-75)

```scala
private[kyo] def pureClass(fqn: String): Maybe[Tasty.Symbol] =
    // Unsafe: reading immutable Ready-state data after open returns; see class-level scaladoc.
    import AllowUnsafe.embrace.danger
    stateRef.unsafe.get() match
        case s: Classpath.State.Ready => Maybe(s.fqnIndex.get(fqn).orNull)
        case _                        => Maybe.Absent
    end match
end pureClass
```

BEFORE signature: `private[kyo] def pureClass(fqn: String): Maybe[Tasty.Symbol]`
AFTER signature:  `private[kyo] def pureClass(fqn: String)(using AllowUnsafe): Maybe[Tasty.Symbol]`
Change: add `(using AllowUnsafe)`, drop `import AllowUnsafe.embrace.danger` line.

### 3. `purePackage` (lines 78-85)

```scala
private[kyo] def purePackage(fqn: String): Maybe[Tasty.Symbol] =
    // Unsafe: reading immutable Ready-state data after open returns; see class-level scaladoc.
    import AllowUnsafe.embrace.danger
    stateRef.unsafe.get() match
        case s: Classpath.State.Ready => Maybe(s.packageIndex.get(fqn).orNull)
        case _                        => Maybe.Absent
    end match
end purePackage
```

BEFORE: `private[kyo] def purePackage(fqn: String): Maybe[Tasty.Symbol]`
AFTER:  `private[kyo] def purePackage(fqn: String)(using AllowUnsafe): Maybe[Tasty.Symbol]`

### 4. `pureModule` (lines 88-95)

```scala
private[kyo] def pureModule(name: String): Maybe[Tasty.ModuleDescriptor] =
    // Unsafe: reading immutable Ready-state data after open returns; see class-level scaladoc.
    import AllowUnsafe.embrace.danger
    stateRef.unsafe.get() match
        case s: Classpath.State.Ready => Maybe(s.moduleIndex.get(name).orNull)
        case _                        => Maybe.Absent
    end match
end pureModule
```

BEFORE: `private[kyo] def pureModule(name: String): Maybe[Tasty.ModuleDescriptor]`
AFTER:  `private[kyo] def pureModule(name: String)(using AllowUnsafe): Maybe[Tasty.ModuleDescriptor]`

### 5. `pureTopLevelClasses` (lines 98-105)

```scala
private[kyo] def pureTopLevelClasses: Chunk[Tasty.Symbol] =
    // Unsafe: reading immutable Ready-state data after open returns; see class-level scaladoc.
    import AllowUnsafe.embrace.danger
    stateRef.unsafe.get() match
        case s: Classpath.State.Ready => s.topLevelClasses
        case _                        => Chunk.empty
    end match
end pureTopLevelClasses
```

BEFORE: `private[kyo] def pureTopLevelClasses: Chunk[Tasty.Symbol]`
AFTER:  `private[kyo] def pureTopLevelClasses(using AllowUnsafe): Chunk[Tasty.Symbol]`

### 6. `purePackages` (lines 108-115)

```scala
private[kyo] def purePackages: Chunk[Tasty.Symbol] =
    // Unsafe: reading immutable Ready-state data after open returns; see class-level scaladoc.
    import AllowUnsafe.embrace.danger
    stateRef.unsafe.get() match
        case s: Classpath.State.Ready => s.packages
        case _                        => Chunk.empty
    end match
end purePackages
```

BEFORE: `private[kyo] def purePackages: Chunk[Tasty.Symbol]`
AFTER:  `private[kyo] def purePackages(using AllowUnsafe): Chunk[Tasty.Symbol]`

### 7. `accumulatedErrors` (lines 136-144)

```scala
private[kyo] def accumulatedErrors: Chunk[TastyError] =
    // Unsafe: state.get() - safe non-effectful read since errors are immutable after Phase C
    import AllowUnsafe.embrace.danger
    stateRef.unsafe.get() match
        case s: Classpath.State.Ready    => s.errors
        case b: Classpath.State.Building => Chunk.from(b.errors)
        case Classpath.State.Closed      => Chunk.empty
    end match
end accumulatedErrors
```

BEFORE: `private[kyo] def accumulatedErrors: Chunk[TastyError]`
AFTER:  `private[kyo] def accumulatedErrors(using AllowUnsafe): Chunk[TastyError]`

### 8. `allSymbols` (lines 154-162)

```scala
private[kyo] def allSymbols: Chunk[Tasty.Symbol] =
    // Unsafe: allSymbols non-effectful read of immutable Ready state
    import AllowUnsafe.embrace.danger
    stateRef.unsafe.get() match
        case s: Classpath.State.Ready    => s.allSymbols
        case _: Classpath.State.Building => Chunk.empty
        case Classpath.State.Closed      => Chunk.empty
    end match
end allSymbols
```

BEFORE: `private[kyo] def allSymbols: Chunk[Tasty.Symbol]`
AFTER:  `private[kyo] def allSymbols(using AllowUnsafe): Chunk[Tasty.Symbol]`

### 9. `transitionToReady` (lines 202-217, companion object)

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
): Unit =
    // Unsafe: atomic state write, called from single-threaded Phase C
    import AllowUnsafe.embrace.danger
    val ready = new State.Ready(allSymbols, topLevelClasses, packages, fqnIndex, packageIndex, canonical, errors, moduleIndex)
    cp.stateRef.unsafe.set(ready)
end transitionToReady
```

Lives in `object Classpath` (not the class). Signature is a multi-parameter-list method on the companion.
BEFORE: `private[kyo] def transitionToReady(cp: Classpath, ...): Unit`
AFTER:  `private[kyo] def transitionToReady(cp: Classpath, ...)(using AllowUnsafe): Unit`
(The `(using AllowUnsafe)` goes after the last explicit parameter clause.)

### 10. `close` (lines 220-224, companion object)

```scala
private[kyo] def close(cp: Classpath): Unit =
    // Unsafe: atomic CAS Classpath state -> Closed, called from Scope finalizer
    import AllowUnsafe.embrace.danger
    cp.stateRef.unsafe.set(State.Closed)
end close
```

Lives in `object Classpath`. BEFORE: `private[kyo] def close(cp: Classpath): Unit`
AFTER:  `private[kyo] def close(cp: Classpath)(using AllowUnsafe): Unit`

---

## File anchors

Single file: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala`

| Accessor | Lines |
|---|---|
| `isClosed` | 23-28 (already migrated — verify, do not re-migrate) |
| `pureClass` | 63-75 |
| `purePackage` | 77-85 |
| `pureModule` | 87-95 |
| `pureTopLevelClasses` | 97-105 |
| `purePackages` | 107-115 |
| `accumulatedErrors` | 135-144 |
| `allSymbols` | 153-162 |
| `transitionToReady` | 198-217 (companion) |
| `close` | 219-224 (companion) |

---

## Caller cascade summary

Ripgrep over `kyo-tasty/**/*.scala` (excluding non-Scala). Unique files with at least one hit:

Source files (main/):
1. `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` — 11 hits
   (`pureClass` x3, `isClosed` x1, `transitionToReady` x1, `allSymbols` x2, `purePackage` x1, `purePackages` x1, `pureTopLevelClasses` x1, `accumulatedErrors` x1)
2. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/Classpath.scala` — self-references in body (not callers)
3. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathOrchestrator.scala` — 2 hits
   (`transitionToReady` x2: a `traceSpan` comment hit + the actual call at line 451)
4. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/query/ClasspathTestHelpers.scala` — 1 hit
   (`allSymbols` x1 at line 19)
5. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotReader.scala` — 2 hits
   (`transitionToReady` x2 at lines 168, 243)
6. `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotWriter.scala` — 1 hit
   (`allSymbols` x1 at line 63)

Source files total: 5 callers (excluding self).

Test files:
1. `kyo-tasty/shared/src/test/scala/kyo/ClasspathOrchestratorPipelineTest.scala` — 7 hits (`allSymbols` x6+)
2. `kyo-tasty/shared/src/test/scala/kyo/QueryApiTest.scala` — 8 hits (`allSymbols` x7, `accumulatedErrors` x1)
3. `kyo-tasty/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala` — 1 hit (`allSymbols` x1)
4. `kyo-tasty/jvm/src/test/scala/kyo/SnapshotRoundTripJvmTest.scala` — 1 hit (`allSymbols` x1)

Test files total: 4 callers.

Key observation: every test-file call is wrapped in `Sync.defer(rawCp.allSymbols...)` or a similar `Sync.defer` frame, which already embraces internally (per the Phase 02a audit note and Q-006 research). After Phase 02b adds `(using AllowUnsafe)` to the accessor signatures, all existing call sites that are inside `Sync.defer { ... }` (or inside an existing `import AllowUnsafe.embrace.danger` scope) will need to either have a `given` in scope or continue wrapping via embrace. The impl agent must patch each call site.

Specifically, `ClasspathTestHelpers.scala:19` calls `cp.allSymbols` with a local `import AllowUnsafe.embrace.danger` (per Q-006 research note). After Phase 02b, that local import continues to satisfy the new `(using AllowUnsafe)` parameter, so it is already a valid call site — no change needed there beyond confirming the import is still present.

---

## Cross-platform notes

All 10 accessors live in `shared/src/main/scala/`. There are no platform-specific variants.
The test file that must be created (`ClasspathPureAccessorTest.scala`) goes in `shared/src/test/scala/kyo/` to run on JVM, JS, and Native.
The jvm-only test file `SnapshotRoundTripJvmTest.scala` is legitimately jvm-only (pre-existing) and is not moved.

---

## Phase 02a audit WARNs to fold in

### WARN-1: README doctest AllowUnsafe drift

From `phase-02a-audit.md`:
> README fenced scala blocks at lines 10-25, 46-60, 91-105, 123-140, 152-170 reference `fullName.asString`, `declaredType`, `name.asString`. After Phase 02a's signature change, these blocks no longer compile in isolation. The existing doctest-presence test (TastyTest:130) does not compile the blocks.

Phase 02b's plan slice (05-plan.md lines 240-348) does NOT list `kyo-tasty/README.md` in `files_modified`. The plan only modifies `Classpath.scala` and produces `ClasspathPureAccessorTest.scala`.

Disposition: DEFERRED. Phase 02b is Classpath-only. README doctest fix belongs either at the end of the 02* group (Phase 02e or a follow-on sweep) once all AllowUnsafe propagations are complete, or can be resolved by adding a single `given AllowUnsafe = AllowUnsafe.embrace.danger` call-out near the first code block. The impl agent for Phase 02b must NOT touch README.md unless it is added to the plan. Flag as carry-forward concern to Phase 02e prep.

### WARN-2: BaseClass parent type-resolution gap

Not Phase 02b's responsibility. Tracked as Phase 10 (M7). No action here.

---

## Concerns

### C-1: `isClosed` is already migrated

At HEAD, `isClosed` (line 27) already has `(using AllowUnsafe)` and no inner `import`. The plan's `files_modified` entry cites line 27 as a target. The impl agent must verify the current state before applying the pattern and skip re-migrating `isClosed`. If the impl agent naively applies the template it will either no-op (correct) or double-add `(using AllowUnsafe)` (compile error). Defensive check required.

### C-2: `transitionToReady` signature discrepancy

The plan's BEFORE/AFTER snippet (lines 260-284) shows `transitionToReady(state: Classpath.State.Ready): Unit`, but the actual signature at HEAD is a 9-parameter method on the companion: `def transitionToReady(cp: Classpath, allSymbols, topLevelClasses, packages, fqnIndex, packageIndex, canonical, errors, moduleIndex): Unit`. The plan's snippet is a simplification. The actual change is: append `(using AllowUnsafe)` after the last explicit parameter clause of the real 9-arg form. No functional discrepancy; just a wording gap in the plan. Impl agent must use the real 9-arg signature.

### C-3: `ClasspathPureAccessorTest.scala` does not exist

Expected at `kyo-tasty/shared/src/test/scala/kyo/ClasspathPureAccessorTest.scala`.
`find` and `rg` find no such file. The impl agent must CREATE this file (4 tests per plan slice lines 310-332). This mirrors the Phase 02a pattern where `TastySymbolTest.scala` was created.

### C-4: Plan files_modified covers only Classpath.scala, but 5 source callers and 4 test callers will need updates

The plan lists ONLY `Classpath.scala` under `files_modified`. However, once the 9 non-already-migrated accessors gain `(using AllowUnsafe)`, every call site in the 5 source files and 4 test files that calls them will fail to compile unless `AllowUnsafe` is in implicit scope at the call site. The plan notes this under "Public API modifications" (line 305) but does not enumerate the cascade files. The impl agent must patch callers as a necessary cascade. Key sites:

- `Tasty.scala`: the extension methods that call `cp.pureClass`, `cp.purePackage`, `cp.purePackages`, `cp.pureTopLevelClasses`, `cp.accumulatedErrors`, `cp.allSymbols`, `cp.pureModule`, `cp.isClosed` are already inside `(using AllowUnsafe)` contexts (they were migrated in Phase 02a — verify), so they should resolve automatically. Confirm at Tasty.scala:1012, 1030, 1036, 1042, 1048, 1054, 1063, 1073.
- `ClasspathOrchestrator.scala`: calls `Classpath.transitionToReady(...)` at line 451, inside `TastyStat.scope.traceSpan`. Needs `(using AllowUnsafe)` in scope at that call site, likely via a local `import AllowUnsafe.embrace.danger` with `// Unsafe: atomic state write, Phase C finalize` comment.
- `SnapshotReader.scala`: calls `Classpath.transitionToReady(...)` at lines 168 and 243. Same pattern.
- `SnapshotWriter.scala`: calls `cp.allSymbols` at line 63. Check whether there is already an `AllowUnsafe` embrace at that site.
- `ClasspathTestHelpers.scala:19`: calls `cp.allSymbols` with an existing local `import AllowUnsafe.embrace.danger` (per Q-006). That import satisfies `(using AllowUnsafe)` implicitly — no new change needed unless confirmed otherwise by compilation.
- Test files (`ClasspathOrchestratorPipelineTest`, `QueryApiTest`, `SnapshotRoundTripTest`, `SnapshotRoundTripJvmTest`): all call `rawCp.allSymbols` inside `Sync.defer { ... }` blocks. After 02b's change, `allSymbols` requires `(using AllowUnsafe)`. `Sync.defer` does NOT provide `AllowUnsafe` in scope; the caller needs a local embrace or a `given`. Each test site must add `import AllowUnsafe.embrace.danger` with a `// Unsafe: §839 case 3` comment, or be refactored to call via the public `Tasty.Classpath` extension API (which carries `(using AllowUnsafe)` from its extension method).

### C-5: `close` call site

`Classpath.close(cp: Classpath)` is called from a `Scope.ensure` finalizer registered in `Tasty.scala`. After adding `(using AllowUnsafe)`, the finalizer body needs an `AllowUnsafe` in scope. Locate the call site in `Tasty.scala` (likely around `Scope.ensure`) and confirm it is already inside an `AllowUnsafe`-providing context or add a local embrace.
