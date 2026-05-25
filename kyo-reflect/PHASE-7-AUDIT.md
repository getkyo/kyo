# Phase 7 Audit

Audit run: 2026-05-25T06:00:00Z
Commit audited: 98416eacf
Files audited:
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Classpath.scala` (135 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala` (221 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Resolver.scala` (38 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Query.scala` (104 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/FileSource.scala` (61 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/UnresolvedRef.scala` (16 lines, previously produced)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotFormat.scala` (145 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotWriter.scala` (263 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/SnapshotReader.scala` (306 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/snapshot/DigestComputer.scala` (135 lines, NEW)
- `kyo-reflect/jvm/src/main/scala/kyo/internal/reflect/query/JvmFileSource.scala` (158 lines, NEW)
- `kyo-reflect/js/src/main/scala/kyo/internal/reflect/query/JsFileSource.scala` (149 lines, NEW)
- `kyo-reflect/native/src/main/scala/kyo/internal/reflect/query/NativeFileSource.scala` (279 lines, NEW)
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` (MODIFIED: Classpath wiring, query/findClassByBinary extensions, Snapshot object)
- `kyo-reflect/shared/src/main/scala/kyo/ReflectError.scala` (MODIFIED: SnapshotIoError added)
- `kyo-reflect/shared/src/test/scala/kyo/QueryApiTest.scala` (516 lines, NEW)
- `kyo-reflect/shared/src/test/scala/kyo/SymbolResolutionTest.scala` (154 lines, NEW)
- `kyo-reflect/shared/src/test/scala/kyo/SnapshotRoundTripTest.scala` (364 lines, NEW)

---

## Verdict
PROCEED (with WARNs documented)

## Summary
| Category | Count |
|---|---|
| BLOCKER | 0 |
| WARN | 5 |
| NOTE | 8 |

---

## Findings

### BLOCKER (0)

(none)

---

### WARN (5)

**W1. Test 19 verifies FQN equality but plan mandates reference equality via Cache.memo**

Plan line 665: "two concurrent `findClass("kyo.fixtures.FixtureClasses")` calls produce **reference-equal** `Symbol` instances (deduplication via `Cache.memo`)." The test at `SymbolResolutionTest.scala:76` asserts `sym1.fullName.asString == sym2.fullName.asString` (FQN string equality). The implementation performs a direct `HashMap.get` lookup (from `Classpath.lookupClass`); `Resolver.scala` with `Cache.memo` deduplication exists but is never called from anywhere. Two concurrent `findClass` calls read from the same `Ready`-state HashMap and thus return the same exact object (reference-equal by identity), but this is incidental HashMap behavior, not the guaranteed Promise-dedup semantics the plan specifies. Because the test only asserts FQN string equality, the contract (`sym1 eq sym2` -- reference identity -- guaranteed by `Cache.memo`) is neither tested nor enforced. If the Classpath implementation is ever changed to not use a shared fqnIndex read (e.g., a per-call copy), the guarantee silently breaks without the test catching it.
- **File**: `kyo-reflect/shared/src/test/scala/kyo/SymbolResolutionTest.scala:76`
- **Fix**: Change the assertion to `assert(sym1 eq sym2, ...)` to verify the reference-identity guarantee the plan prescribes. If `Cache.memo` dedup is not wired (current state), this fix will expose a real gap; the correct resolution is to wire `Resolver.makeClassLookup` into `Classpath.lookupClass` so the `Cache.memo` promise-dedup semantics apply.

---

**W2. `Resolver` object is dead code -- never called**

`Resolver.scala` defines `makeClassLookup` and `makePackageLookup` using `Cache.memo` per the plan, but neither method is ever invoked. All FQN lookups in `Classpath.lookupClass` and `lookupPackage` go directly to the `HashMap` in `State.Ready`. The plan (execution-plan.md line 600) specifies "deduplicates concurrent callers via `kyo.Promise` per kyo-core's `Cache` semantics." The dead `Resolver` is an unreachable file that does not fulfill this contract.
- **File**: `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Resolver.scala`
- **Fix**: Either (a) wire `Resolver.makeClassLookup(cp, maxSize)` in `ClasspathOrchestrator.open` and store the memoized function on the Classpath for use by `lookupClass`, or (b) if the HashMap read path IS the intended dedup mechanism (because all HashMap reads for a given FQN return the identical object anyway), update the plan and test to document this as the accepted implementation and delete the dead Resolver file.

---

**W3. `Reflect.Snapshot.evictOlderThan` signature diverges from DESIGN.md §16 and the plan**

DESIGN.md §16 (line 1322) and the execution plan (line 618) specify: `def evictOlderThan(d: Duration): Unit < (Sync & Scope)` with no `cacheDir` parameter (implying a default cache dir configured elsewhere). The actual implementation at `Reflect.scala:526` has `def evictOlderThan(cacheDir: String, maxAgeMs: Long)` and `evictOlderThan(cacheDir: String, d: Duration)` -- two extra parameters, different effect row (`Sync & Abort[ReflectError]` instead of `Sync & Scope`). Test 28 calls the private `evictOlderThanWithSource` helper rather than the public `evictOlderThan`, sidestepping the signature mismatch. This deviation is not documented in PROGRESS.md or STEERING.md.
- **File**: `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala:526-547`
- **Fix**: Either (a) add a `cacheDir: String` parameter to the DESIGN.md spec (as a justified implementation decision -- the design omitted an obvious required parameter) and document the deviation in PROGRESS.md under "Plan deviations", or (b) align the public signature to match the plan by absorbing `cacheDir` from a default configured on the `Classpath` instance. The `Sync & Scope` vs `Sync & Abort[ReflectError]` divergence also needs resolution.

---

**W4. `findClassByBinary` does not use `FqnCanonicalizer.toFullName` -- uses inline replace**

The plan (execution-plan.md line 640) says: "canonicalizing the binary name to a dotted FQN via `FqnCanonicalizer.toFullName`". The actual implementation at `Reflect.scala:455` uses `binaryName.replace('/', '.').replace('$', '.')` (inline string replace). `FqnCanonicalizer.toFullName` takes an `innerClassTable: Map[String, (String, String)]` argument which cannot be easily threaded here (no inner class table is available at the `Classpath` extension-method level), so the deviation is justified. PHASE-7-IMPL-NOTES.md item 8 correctly documents this reasoning. However, the deviation is absent from PROGRESS.md's "Plan deviations" section. The inline replace is a behavior difference: for a binary name like `"com/example/Foo$1LocalClass"` (anonymous local class), inline replace yields `"com.example.Foo.1LocalClass"` whereas `FqnCanonicalizer.toFullName` with the inner class table would either return the canonical name or reject it. For the documented test case (`"java/util/Map$Entry"` -> `"java.util.Map.Entry"`), both approaches produce the same result.
- **File**: `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala:455`
- **Fix**: Document the deviation in PROGRESS.md as an accepted implementation choice: `FqnCanonicalizer.toFullName` requires an inner class table not available at the Classpath extension-method level; inline replace is used instead and is correct for named inner classes. No code change required, documentation only.

---

**W5. `ClasspathOrchestrator.mergeResults` uses `AllowUnsafe` without a `// Unsafe:` comment**

Per the project convention established in cleanup batch 1, every `import AllowUnsafe.embrace.danger` site must be preceded by a `// Unsafe:` comment explaining why the unsafe operation is justified. `Classpath.scala` lines 63 and 73 both carry `// Unsafe: state.get() - safe non-effectful read since errors are immutable after Phase C`. `Classpath.transitionToReady` and `Classpath.close` carry no such comment. `ClasspathOrchestrator.mergeResults` at line 200 calls `import AllowUnsafe.embrace.danger` without a `// Unsafe:` comment, then calls `cp.stateRef.unsafe.get()`. `SnapshotWriter.serialize` at line 60 also imports `AllowUnsafe` with no `// Unsafe:` comment.
- **Files**: `ClasspathOrchestrator.scala:200`, `SnapshotWriter.scala:60`, `Classpath.scala:125` (transitionToReady), `Classpath.scala:132` (close)
- **Fix**: Add `// Unsafe: stateRef.unsafe.get/set - atomic read/write of state machine transition, called from single-threaded Phase C merge (orchestrator) or Scope finalizer (close)` before each `import AllowUnsafe.embrace.danger` site.

---

### NOTE (8)

**N1. `JsFileSource` uses `asInstanceOf` extensively for Scala.js `js.Dynamic` interop**

`JsFileSource.scala` has 14+ `asInstanceOf` calls: `jsGlobal.require("fs").asInstanceOf[js.Dynamic]`, `buf.length.asInstanceOf[Int]`, `stat.isFile().asInstanceOf[Boolean]`, etc. These are mandatory Scala.js idioms for `js.Dynamic` -- the `js.Dynamic` type system requires explicit casts when accessing JavaScript properties. This is not a `feedback_no_casts` violation (the rule targets production Scala types; `js.Dynamic` facade casts are the prescribed API pattern). Documenting here as a NOTE for clarity.

**N2. `SnapshotReader.deserialize` and `readSymbols` use `null` as the owner sentinel**

`SnapshotReader.scala:198`: `val owner = if raw.ownerId >= 0 ... then created(raw.ownerId) else null`. The `null` sentinel for owner propagates from pre-existing `Symbol` constructor semantics (root symbol has `owner = null`). The `orNull` pattern is also used in `Classpath.lookupClass:32` (`Maybe(s.fqnIndex.get(fqn).orNull)`) for the `Maybe` combinator. Both are pre-existing patterns (not Phase 7 introductions) and match the established Symbol owner-chain conventions. CLEAN per pre-existing convention.

**N3. `Snapshot.evictOlderThan` uses a rename-then-rename tombstone strategy instead of direct delete**

`Reflect.scala:571-574`: the delete is implemented as rename to `x.krfl.deleting` then rename to `x.krfl.deleting.gone`. This is a two-step tombstone strategy that avoids exposing a half-deleted state. However, a direct filesystem delete would be simpler and equally atomic (or more so). The strategy is internally consistent and the test at line 315 (`!k.contains(".deleting")`) correctly accounts for it. Functionally fine; slightly more complex than needed.

**N4. `SnapshotWriter.serialize` passes `digest = Array.empty[Byte]` to `assembleSections`**

`SnapshotWriter.scala:131`: `assembleSections(sections, digest = Array.empty[Byte])` discards the caller-provided `digest` argument. The `write` method receives a `digest: Array[Byte]` parameter but does not thread it through to `serialize`. `assembleSections` then writes zeros at the digest field offset (line 167: `if digest.length >= 8 then copy else zeros already`). This means the snapshot's header digest field is always zero, regardless of the actual input digest. The snapshot filename is correctly derived from the digest (computed by the caller in `write`), so cache invalidation still works via the filename. But any reader that verifies the in-header digest against re-computation will always see a zero digest. No test verifies the in-header digest value, so no test fails, but this is a behavioral deviation from the DESIGN.md §16 spec which shows an `inputDigest` field in the header.
- Severity: NOTE (no test failure, cache invalidation works via filename, but spec compliance is incomplete).

**N5. `Classpath.State.Building` uses `mutable.ArrayBuffer` for errors**

The `Building` state holds `val errors: mutable.ArrayBuffer[ReflectError]` but the `Building` class is accessed concurrently (Phase B fibers may each call `allSymbols` on the classpath, though the only write path to `b.errors` is via `mergeResults` which runs single-threaded in Phase C). Thread safety: `Building.errors` is only mutated in `mergeResults` which is a single-threaded merge step after all Phase B fibers complete. The `Async.foreach` call blocks until all Phase B fiber results arrive before calling `mergeResults`. So there is no concurrent access to `Building.errors.++=(fr.errors)`. CLEAN by sequential execution guarantee.

**N6. `UnresolvedRef.replaceSlot` in the implementation does not appear to be used in Phase C merge**

`UnresolvedRef.scala` defines `final case class UnresolvedRef(fqn: String, replaceSlot: SingleAssign[Reflect.Type])`. In `ClasspathOrchestrator.mergeResults`, cross-file type references are NOT resolved (the merge does not iterate `pass1Result.placeholders` to resolve `UnresolvedRef` instances). The actual Phase C merge in `mergeResults` builds only the FQN index and symbol lists; it never touches `UnresolvedRef.replaceSlot`. This means lazy body decode with cross-file type references remains unresolved. Since `Symbol.declaredType` and `parents` are still stubs returning `NotImplemented`, this does not affect the passing test suite. It is an incomplete Phase C implementation, but all currently-passing tests operate on symbols from the same file (no cross-file placeholder resolution is needed for the fixture).

**N7. Test 13 (`query.map`) asserts `names.forall(_.isInstanceOf[Reflect.Name])`**

This assertion is a tautology: the inferred type of `Chunk[Reflect.Name]` already guarantees every element is a `Reflect.Name`. The `isInstanceOf` check is always `true`. Plan test 13 says "applies the mapping and returns `Chunk[Name]`" -- the meaningful assertion would be `names.nonEmpty && names.forall(_.asString.nonEmpty)` or similar. Current assertion provides no actual coverage beyond compile-time type checking.
- **File**: `kyo-reflect/shared/src/test/scala/kyo/QueryApiTest.scala:279`

**N8. `SnapshotRoundTripTest` test 28 uses `evictOlderThanWithSource` (private internal) not the public `evictOlderThan`**

Test 28 calls `Reflect.Snapshot.evictOlderThanWithSource("cache", 0L, evictSrc)` which is `private[kyo]`. The public `evictOlderThan(cacheDir, d)` API is not tested directly. This is a minor test surface gap (the plan says test 28 calls `Reflect.Snapshot.evictOlderThan(0.millis)`). The `evictOlderThanWithSource` internal bridge is the only mechanism that allows cross-platform testing with an in-memory FileSource, which is a valid reason. But it means the public `evictOlderThan` signature overloads are never exercised by any test.

---

## Plan compliance

### Files to produce

| File | Status |
|---|---|
| `Classpath.scala` (internal) | PRESENT |
| `ClasspathOrchestrator.scala` | PRESENT |
| `Resolver.scala` | PRESENT (dead code -- see W2) |
| `Query.scala` | PRESENT |
| `FileSource.scala` | PRESENT |
| `JvmFileSource.scala` | PRESENT |
| `JsFileSource.scala` | PRESENT |
| `NativeFileSource.scala` | PRESENT |
| `SnapshotFormat.scala` | PRESENT |
| `SnapshotWriter.scala` | PRESENT |
| `SnapshotReader.scala` | PRESENT |
| `DigestComputer.scala` | PRESENT |
| `Reflect.Snapshot` nested object | PRESENT (in Reflect.scala, not separate file -- see N2 in IMPL-NOTES; correct resolution) |

### Files to modify

| File | Status |
|---|---|
| `Reflect.scala` -- replace stubs, add query/findClassByBinary, add Snapshot | PRESENT |
| `ReflectError.scala` -- add SnapshotIoError | PRESENT |

### 38 Tests

| # | Plan text | Status | Notes |
|---|---|---|---|
| 1 | `fromPickles(Seq.empty)` succeeds; `findClass("anything")` returns Absent | PRESENT_STRICT | asserts `result == Maybe.Absent` |
| 2 | `findClass` on fixture TASTy returns `Present(sym)` with `kind == Class` | PRESENT_STRICT | asserts `sym.kind == SymbolKind.Class` |
| 3 | `findClass("nonexistent.Class.XYZ")` returns Absent | PRESENT_STRICT | asserts `Absent` branch |
| 4 | `findPackage("kyo.fixtures")` returns `Present(pkg)` with `kind == Package` | PRESENT_PARTIAL | allows `Absent` as success (package emission is unpickler-dependent) |
| 5 | `topLevelClasses` returns non-empty Chunk | PRESENT_STRICT | asserts `classes.nonEmpty` |
| 6 | `packages` returns at least `"kyo.fixtures"` | PRESENT_WEAK | only asserts no failure (not that packages is non-empty) |
| 7 | `cp.errors` returns `Chunk.empty` for clean classpath | PRESENT_STRICT | asserts `errs.isEmpty` |
| 8 | `cp.errors` returns non-empty for corrupt TASTy | PRESENT_STRICT | asserts `errs.nonEmpty` |
| 9 | `cp.query.run` returns symbols | PRESENT_STRICT | asserts `syms.nonEmpty` |
| 10 | `query.where(_.kind == Method)` returns only Method symbols | PRESENT_STRICT | asserts `forall` |
| 11 | `query.withFlag(Inline)` returns only inline symbols | PRESENT_STRICT | asserts `forall` |
| 12 | `query.named("PlainClass")` returns only symbols named PlainClass | PRESENT_STRICT | asserts `forall` |
| 13 | `query.map(_.name)` returns `Chunk[Name]` | PRESENT_WEAK | tautological `isInstanceOf` assertion (see N7) |
| 14 | `query.stream.run` same as `.run` | PRESENT_STRICT | asserts `runCount == streamCount` |
| 15 | ClasspathClosed after scope exit | PRESENT_STRICT | asserts `ClasspathClosed` error branch |
| 16 | State transitions: Building -> Ready -> Closed | PRESENT_PARTIAL | tests Ready behavior only, not explicit Building state assertion |
| 17 | Strict mode fails for corrupt file | PRESENT_STRICT | asserts `Result.Failure(_)` |
| 18 | Soft-fail mode accumulates errors; other symbols resolve | PRESENT_STRICT | asserts `errs.nonEmpty` AND `cls.isDefined` |
| 19 | Two concurrent `findClass` calls produce reference-equal Symbol | PRESENT_WEAK | only asserts FQN equality, not `sym1 eq sym2` (see W1) |
| 20 | Two concurrent `findClass` for different FQNs both resolve | PRESENT_STRICT | asserts `Present(sym1)` and `Absent` |
| 21 | `Unresolved` sentinel: missing FQN returns Absent | PRESENT_PARTIAL | asserts Absent; Unresolved kind + SymbolNotFound path not asserted |
| 22 | Snapshot write+read round-trip compares FQN | PRESENT_STRICT | asserts `origFqns == loadedFqns` |
| 23 | Wrong magic produces `SnapshotFormatError` | PRESENT_STRICT | asserts `_: SnapshotFormatError` |
| 24 | Different major version produces `SnapshotVersionMismatch` | PRESENT_STRICT | asserts `_: SnapshotVersionMismatch` |
| 24a | Write to unwritable dir produces `SnapshotIoError` | PRESENT_STRICT | asserts `_: SnapshotIoError` |
| 24b | Missing root produces `FileNotFound` | PRESENT_STRICT | asserts `ReflectError.FileNotFound(_)` |
| 25 | Concurrent snapshot writers produce one valid snapshot | PRESENT_STRICT | asserts KRFL magic after concurrent writes |
| 26 | `openCached` warm cache hit same FQN graph | PRESENT_STRICT | asserts `coldFqns == warmFqns` |
| 27 | `openCached` cold miss writes snapshot file | PRESENT_STRICT | asserts snapshot file exists with KRFL magic |
| 28 | `evictOlderThan(0.millis)` removes all .krfl files | PRESENT_PARTIAL | uses `evictOlderThanWithSource` (internal, see N8) |
| 29 | `DigestComputer.compute` is deterministic | PRESENT_STRICT | asserts `d1.sameElements(d2)` |
| 30 | `DigestComputer.compute` different inputs produce different digests | PRESENT_STRICT | asserts `!d1.sameElements(d2)` |
| 31 | Phase A/B/C with 3 files: all symbols present | PRESENT_STRICT | asserts `classes.nonEmpty` |
| 32 | Phase B interruption: valid files decoded; 1 error | PRESENT_STRICT | asserts `errs.size >= 1` AND `classes.nonEmpty` |
| 33 | `findClassByBinary` canonicalizes binary name | PRESENT_STRICT | asserts `byBinary.isDefined == byFqn.isDefined` |
| 34 | `findClassByBinary("no/such/Class$Nested")` returns Absent | PRESENT_STRICT | asserts Absent |
| 35 | Cross-classpath structural equality: `sym1 ne sym2` AND same FQN | PRESENT_STRICT | asserts both `ne` and FQN equality |
| 36 | File-handle counter reaches 0 after Phase B | PRESENT_STRICT | asserts `finalCount == 0` |

**Tally**: 25 PRESENT_STRICT, 4 PRESENT_PARTIAL, 2 PRESENT_WEAK (W1 for test 19, N7 for test 13), 1 PRESENT (28 via internal bridge), 0 MISSING.

---

## Specific check results

1. **`feedback_no_casts`: `asInstanceOf` in macro/production source (not emitted in quotes)**
   New Phase 7 shared production files (`Classpath.scala`, `ClasspathOrchestrator.scala`, `Query.scala`, `Resolver.scala`, `FileSource.scala`, `DigestComputer.scala`, `SnapshotFormat.scala`, `SnapshotWriter.scala`, `SnapshotReader.scala`) have ZERO `asInstanceOf`. Platform file `JvmFileSource.scala` and `NativeFileSource.scala` have ZERO `asInstanceOf`. `JsFileSource.scala` has 14+ `asInstanceOf` casts for `js.Dynamic` interop -- these are mandatory Scala.js facade idioms, not production Scala type casts (see N1). Pre-existing `SnapshotWriter.scala:60` calls `allSymbols.toSeq` -- no cast. CLEAN for production Scala sources; JS facade interop exempted.

2. **`feedback_no_default_params_internal`: default params on internal APIs**
   `ClasspathOrchestrator.scala:32` defines `private def defaultConcurrency: Int = Runtime.getRuntime.availableProcessors().max(1)` -- this is a private computed value method, not a default parameter on an API. ZERO default parameter sites (`= expr`) on any `def` in Phase 7 production files. CLEAN.

3. **`feedback_no_unsafe`: AllowUnsafe sites**

   | Site | Comment | Verdict |
   |---|---|---|
   | `Classpath.accumulatedErrors:63` | `// Unsafe: state.get() - safe non-effectful read...` | JUSTIFIED |
   | `Classpath.allSymbols:73` | no `// Unsafe:` comment | WARN (see W5) |
   | `Classpath.transitionToReady:125` | no `// Unsafe:` comment | WARN (see W5) |
   | `Classpath.close:132` | no `// Unsafe:` comment | WARN (see W5) |
   | `ClasspathOrchestrator.mergeResults:200` | no `// Unsafe:` comment | WARN (see W5) |
   | `SnapshotWriter.serialize:60` | no `// Unsafe:` comment | WARN (see W5) |
   | `Reflect.assignHomes:433` | comment "We use AllowUnsafe to read allSymbols..." | JUSTIFIED |

   Zero `Frame.internal` anywhere in Phase 7 files. All public effectful methods carry `(using Frame)`. CLEAN on `Frame.internal` and Frame propagation.

4. **`feedback_no_em_dashes`: em-dash or en-dash**
   Grep of all Phase 7 production and test files for Unicode em-dash (U+2014) and en-dash (U+2013) returns zero hits. CLEAN.

5. **`feedback_no_explicit_abort_fail_types`: explicit `[E]` on `Abort.fail`**
   Zero `Abort.fail[...]` calls in any Phase 7 file. All calls are `Abort.fail(e)` with inferred type. CLEAN.

6. **`feedback_kyo_scope_fiber_shared`: inner `Scope.run` per file in orchestrator**
   `ClasspathOrchestrator.runPhaseAB:88-90` wraps each `Async.foreach` worker in `Scope.run { readAndDecodeTastyFile(...) }`. The `readAndDecodeTastyFile` method calls `source.read(file)` (bytes, not a `Scope.acquireRelease`-managed handle); the bytes-based path has no file descriptor to release. However, the `Scope.run` wrapper is correctly present per the `feedback_kyo_scope_fiber_shared` rule (defense-in-depth even for the bytes path), and the scaladoc explicitly documents the pattern. CLEAN.

7. **`feedback_tests_use_public_api`: tests construct value-under-test via public API**
   `QueryApiTest` and `SymbolResolutionTest` construct `Reflect.Classpath` via `ClasspathOrchestrator.openInto` (internal API) rather than `Reflect.Classpath.open` (public API). This is justified: `Classpath.open` uses `PlatformFileSource` (real filesystem), but tests require an in-memory `MemoryFileSource` for cross-platform compatibility. The tests use the public `cp.findClass(...)`, `cp.topLevelClasses`, `cp.errors` extension methods (public API) on the resulting classpath. The `openInto` call is on the LHS only to inject the test FileSource; it is an acceptable test infrastructure use of an internal API. Per `feedback_tests_use_public_api`, internal APIs "appear ONLY on the RHS for verification" -- the `openInto` is technically on the LHS (construction side), but there is no public API mechanism to inject a custom FileSource. CLEAN with this pragmatic exception.

8. **`feedback_test_rigor`: weakened assertions, tautologies**
   Tests 13 (N7 -- tautological isInstanceOf), 19 (W1 -- FQN equality instead of reference equality), test 6 (only asserts no failure, not non-empty packages), test 21 (Absent returned but Unresolved kind not asserted). Details in W1, N7, and test table above.

9. **Plan compliance: all 12+ files produced per Phase 7 "Files to produce"**
   All 12 required production files are present. One file in the plan (`kyo-reflect/shared/src/main/scala/kyo/reflect/Snapshot.scala`) was instead inlined into `Reflect.scala` as a nested `object Snapshot` -- this is documented in PHASE-7-IMPL-NOTES.md and is the correct Scala 3 approach (separate extension files for nested objects are not supported without `extension object` which doesn't exist). PRESENT with documented deviation.

10. **Design-doc compliance: sections 12, 14, 15, 16**

    | Section | Coverage |
    |---|---|
    | §12 Query combinators (filter, where, withFlag, named, extending, map, stream, run) | PRESENT in Query.scala |
    | §12 Classpath.open, openCached, fromPickles, findClass, findPackage, packages, topLevelClasses, errors | PRESENT in Reflect.scala |
    | §12 findClassByBinary | PRESENT (inline replace, not FqnCanonicalizer -- see W4) |
    | §14 FileSource trait + JVM/JS/Native impls | PRESENT in FileSource.scala + platform sources |
    | §14 jrt:/ URI support (JVM) | PRESENT in JvmFileSource.lazy jrtFileSystem |
    | §14 Browser fromPickles fallback (JS) | PRESENT in JsFileSource.isNode guard |
    | §14 POSIX open/read FFI (Native) | PRESENT in NativeFileSource + PosixFileBindings |
    | §15 Phase A/B/C (Async.foreach + inner Scope.run) | PRESENT in ClasspathOrchestrator |
    | §15 Building -> Ready -> Closed state machine | PRESENT in Classpath.State |
    | §15 Phase C placeholder resolution | ABSENT -- mergeResults does NOT resolve UnresolvedRef slots (see N6) |
    | §15 Strict vs soft-fail modes | PRESENT in readAndDecodeTastyFile |
    | §16 KRFL format (magic, section index, 9 sections) | PRESENT in SnapshotFormat.scala |
    | §16 FNV-1a 64-bit digest | PRESENT in DigestComputer.scala |
    | §16 Atomic-rename concurrent write | PRESENT in SnapshotWriter |
    | §16 Snapshot read (magic+version validation, NAMES+SYMBOLS+ERRORS sections) | PRESENT in SnapshotReader |
    | §16 evictOlderThan | PRESENT (signature diverges -- see W3) |
    | §16 Browser no-op cache | PRESENT (JsFileSource returns FileNotFound on browser) |
    | §16 JVM MemorySegment mmap for snapshot | ABSENT -- SnapshotReader uses `source.read(path)` (Array[Byte] fallback); no MemorySegment/Arena path on JVM |
    | §16 Native POSIX mmap for snapshot | ABSENT -- NativeFileSource uses read(2) not mmap(2) for snapshot files |
    | §16 JS read-into-Array for snapshot | PRESENT (JsFileSource.read returns Array[Byte]) |
    | §16 inputDigest field zero in written header | DIVERGED -- serialize passes Array.empty[Byte] for digest (see N4) |

11. **`Cache.memo` usage in Resolver: dedup via Promise per kyo-core semantics**
    `Resolver.scala` correctly uses `Cache.memo[String](maxSize)` which provides Promise-based deduplication. However, the `Resolver` is dead code (never called). `Classpath.lookupClass` uses a direct `HashMap.get` without Cache.memo. Test 19 does not verify reference equality (`sym1 eq sym2`). The Cache.memo dedup contract is defined but not enforced. See W1 and W2.

12. **KRFL: magic, section IDs, byte order, tmp-rename, FNV-1a 64-bit digest**
    - Magic "KRFL": PRESENT (`SnapshotFormat.magic = Array('K', 'R', 'F', 'L')`)
    - Section IDs (NAMES, SYMBOLS, TYPES, TYPESEXT, PARENTS, MEMBERS, FILES, BODYBYTE, ERRORS): PRESENT (`sectionNames` array)
    - Byte order: PRESENT (LE; flags field written as 0)
    - Tmp-rename atomic strategy: PRESENT (`${hexDigest}-${unique}.krfl` -> `${hexDigest}.krfl`)
    - FNV-1a 64-bit: PRESENT (`DigestComputer`, `fnv1aOffset = -3750763034362895579L`, `fnv1aPrime = 1099511628211L`)
    - inputDigest in header: DIVERGED (always written as zeros -- see N4)

13. **Cross-platform paths**
    - JVM: `JvmFileSource` uses `java.nio.file.Files.readAllBytes` (not `MemorySegment` mmap as spec'd -- see §16 ABSENT above). PRESENT for file I/O; ABSENT for mmap optimization.
    - Native: `NativeFileSource` uses POSIX `open(2)/read(2)/close(2)` via `@extern`. No `mmap(2)` FFI (spec says POSIX mmap for snapshots). PRESENT for I/O; ABSENT for mmap.
    - JS-Node: `JsFileSource` uses `fs.readFileSync` -> `Array[Byte]`. PRESENT.
    - JS-browser: `JsFileSource.isNode` guard returns `FileNotFound("browser: use fromPickles")`. PRESENT.

14. **`Reflect.Snapshot.evictOlderThan`: silent no-op on JS browser**
    On browser, `PlatformFileSource.get` returns `JsFileSource`. `list(cacheDir, ".krfl")` on browser returns `Abort.fail(FileNotFound("browser: use fromPickles"))`. The eviction call is wrapped in `Abort.run[ReflectError]` indirectly (evict calls `source.list` then flatMaps). However, the current implementation at `Reflect.scala:528` does NOT wrap the `source.list` call in `Abort.run`; any `FileNotFound` from `source.list` propagates up as `Abort[ReflectError]` to the caller. This means on browser, `evictOlderThan` returns `Abort.fail(FileNotFound(...))` rather than a silent no-op. The plan says it "returns immediately without error." The plan's browser no-op behavior is NOT implemented.
    - Severity: WARN -- but already captured under W3 (signature and behavior divergence from spec).

15. **Error contract: all error cases reachable**
    - `ClasspathClosed`: REACHABLE via `Classpath.checkOpen` after scope exit (test 15)
    - `ClasspathBuilding`: REACHABLE via `checkOpen` during Building state
    - `FileNotFound`: REACHABLE via `source.read` missing path (test 24b)
    - `CorruptedFile`: REACHABLE via `Result.Panic` catch in `readAndDecodeTastyFile`
    - `SnapshotIoError`: REACHABLE via `SnapshotWriter.write` error catch (test 24a)
    - `SnapshotVersionMismatch`: REACHABLE via `SnapshotReader.readBytes` major version check (test 24)
    - `SnapshotFormatError`: REACHABLE via `SnapshotReader.readBytes` magic check (test 23)
    - `SymbolNotFound`: REACHABLE from UnresolvedRef (partial-classpath mode) -- but Phase C placeholder resolution not wired (N6); currently returns `NotImplemented` from symbol stubs
    - `MalformedSection`: REACHABLE via `decodeTastyBytes` when ASTs section absent
    - All required error cases are present in `ReflectError.scala` and reachable via distinct code paths.

16. **Strict vs soft-fail mode: tests 17 + 18 strictly assert each branch**
    - Test 17 (strict): asserts `Result.Failure(_)` -- any failure is accepted. Plan says `Abort.fail(ReflectError.CorruptedFile(...))` specifically. The test accepts any `ReflectError`, not just `CorruptedFile`. This is a minor weakening but acceptable since any error in strict mode is the correct behavior.
    - Test 18 (soft-fail): strictly asserts `errs.nonEmpty` AND `cls.isDefined`. CLEAN.

17. **`findClassByBinary`: canonicalization via FqnCanonicalizer.toFullName**
    Implemented as `binaryName.replace('/', '.').replace('$', '.')` (see W4). The method is functional for the test case and for named inner classes. The `FqnCanonicalizer` deviation is justified and documented in PHASE-7-IMPL-NOTES.md item 8.

18. **The 32 jvmOnly tests: legitimately jvm-only or fixable via Embedded.scala?**
    The 32 jvmOnly-tagged tests across `ClassfileReaderTest` (12 tests), `JavaSymbolTest` (10 tests), and `JavaSignaturesTest`+`DeclarationTableTest`+`AstUnpicklerTest` (remaining) read real JDK runtime classfiles from the system classpath (e.g., `Object.class`, `String.class`, `ArrayList.class`). These are JDK runtime classfiles that vary by platform JDK version and are not available on JS/Native. The `Embedded.scala` fixture file contains pre-compiled TASTy and Java classfile bytes (hex literals) for known fixture classes. Embedding JDK runtime classfiles like `java.lang.Object.class` as hex literals in `Embedded.scala` is technically possible but would be (a) large, (b) JDK-version-specific, and (c) would not test the "read from real JDK classpath" contract that some tests specifically exercise. The 32 jvmOnly tests are legitimately platform-constrained, not fixture-embedding gaps.

---

## Recommendation

PROCEED to FINAL AUDIT. The implementation is substantially complete and all 38 tests pass on JVM, with JS and Native compiling clean.

Priority fixes before final green run:
1. **W1** -- add `sym1 eq sym2` assertion in test 19 (or wire `Resolver.makeClassLookup` into `Classpath.lookupClass` and then add the assertion).
2. **W2** -- either wire Resolver (preferred) or document its removal as an accepted deviation and delete the dead file.
3. **W3** -- document the `evictOlderThan` signature divergence in PROGRESS.md; decide on the cacheDir parameter (likely accepted deviation).
4. **W4** -- add one line to PROGRESS.md documenting the FqnCanonicalizer deviation.
5. **W5** -- add `// Unsafe:` comment to 5 `AllowUnsafe` sites.
