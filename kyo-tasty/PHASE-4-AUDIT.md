# Phase 4 Audit — Defer toMap in AstUnpickler (HAMT reduction)

**Commit:** `279e6b3b2` ("kyo-reflect Phase 4: defer toMap in AstUnpickler (HAMT reduction)")
**HEAD verified:** `git log --oneline -3` confirms `279e6b3b2` is the most recent kyo-reflect commit (HEAD).
**Scope of audit:** committed tree only. Dirty working tree NOT inspected.

---

## 1. Test count

Plan requires 3 new tests.

| Test ID | Status | Location |
|---|---|---|
| T-P4-1: Pass1Result holds mutable.HashMap (compile + behavioral) | **PRESENT_STRICT** | `kyo-reflect/shared/src/test/scala/kyo/AstUnpicklerTest.scala:569` |
| T-P4-2: merger processes mutable.HashMap FileResult end-to-end | **PRESENT_STRICT** | `kyo-reflect/shared/src/test/scala/kyo/QueryApiTest.scala:934` |
| T-P4-3: TastyOrigin.addrMap populated after pass1 | **PRESENT_STRICT** | `kyo-reflect/shared/src/test/scala/kyo/AstUnpicklerTest.scala:596` |

T-P4-1: assigns the four fields to explicitly typed `mutable.HashMap[...]` locals (compile-time proof that the types are mutable.HashMap, not immutable Map), then asserts non-empty and probes for a known TypeParam symbol. Strict per plan §T1.

T-P4-2: opens fixture classpath via `openFixtureClasspath`, looks up `kyo.fixtures.PlainClass`, asserts `kind == Class`. Exercises the full Phase A→B→C pipeline with mutable.HashMap FileResult fields. Strict per plan §T2.

T-P4-3: runs pass1 on `PlainClass.tasty`, matches origin as `TastyOrigin`, accesses `origin.addrMap` under `AllowUnsafe`, asserts non-empty and contains `PlainClass` by value scan. Strict per plan §T3.

---

## 2. .toMap removal verification

Four targeted call sites at AstUnpickler:153/176/177/178 should be removed.

Grep of committed AstUnpickler.scala (`git show 279e6b3b2:.../AstUnpickler.scala | grep .toMap`) returns **zero matches**.

- Line 153 `val finalAddrMap = addrMap.toMap` → REMOVED. `o._addrMap.set(addrMap)` now passes mutable.HashMap directly (AstUnpickler.scala:161 of committed file).
- Line 176 `parentsBySymbol.view.mapValues(identity).toMap` → REMOVED (line 184 passes `parentsBySymbol` directly).
- Line 177 `childrenByOwner.view.mapValues(buf => Chunk.from(buf.toSeq)).toMap` → REMOVED. Replaced by an explicit `childrenChunks` intermediate built via plain for-loop at lines 175–177; passed as `childrenByOwner = childrenChunks` at line 185.
- Line 178 `typeBySymbol.view.mapValues(identity).toMap` → REMOVED (line 186 passes `typeBySymbol` directly).

The intentional snapshot at `TypeUnpickler.scala:173` (`session.liveAddrMap.toMap`) is **preserved** as required. `grep .toMap` on committed TypeUnpickler returns exactly one match at line 173. Phase 5 will further change this to `IntMap.from`.

**Result: PRESENT_STRICT.**

---

## 3. Type-change verification

| Element | Expected | Committed location | Status |
|---|---|---|---|
| `Pass1Result.addrMap` | `mutable.HashMap[Int, Reflect.Symbol]` | AstUnpickler.scala:64 | OK |
| `Pass1Result.parentsBySymbol` | `mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Type]]` | AstUnpickler.scala:67 | OK |
| `Pass1Result.childrenByOwner` | `mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Symbol]]` | AstUnpickler.scala:68 | OK |
| `Pass1Result.typeBySymbol` | `mutable.HashMap[Reflect.Symbol, Reflect.Type]` | AstUnpickler.scala:69 | OK |
| `FileResult.parentsBySymbol` | `mutable.HashMap[...]` | ClasspathOrchestrator.scala:59 | OK |
| `FileResult.childrenByOwner` | `mutable.HashMap[...]` | ClasspathOrchestrator.scala:60 | OK |
| `FileResult.typeBySymbol` | `mutable.HashMap[...]` | ClasspathOrchestrator.scala:61 | OK |
| `FileResult` does NOT add `addrMap` field | absent | ClasspathOrchestrator.scala:54–63 | OK (9-field shape preserved) |
| `TastyOrigin._addrMap` type | `SingleAssign[mutable.HashMap[Int, Reflect.Symbol]]` | Reflect.scala:776 | OK |
| `TastyOrigin.addrMap` accessor return type | `scala.collection.Map[Int, Reflect.Symbol]` (widened supertype, NOT narrowed to mutable) | Reflect.scala:779 | OK |
| `PositionsUnpickler.read` addrMap param | `scala.collection.Map[...]` | PositionsUnpickler.scala:45 | OK |
| `PositionsUnpickler.readSync` addrMap param | `scala.collection.Map[...]` | PositionsUnpickler.scala:60 | OK |
| `CommentsUnpickler.read` addrMap param | `scala.collection.Map[...]` | CommentsUnpickler.scala:35 | OK |
| `CommentsUnpickler.readSync` addrMap param | `scala.collection.Map[...]` | CommentsUnpickler.scala:47 | OK |
| `TypeUnpickler.TreeTypeSession.addrMap` field | `scala.collection.Map[...]` | TypeUnpickler.scala:98 | OK |
| `TypeUnpickler.DecodeCtx.addrMap` field | `scala.collection.Map[...]` | TypeUnpickler.scala:208 | OK |
| `TreeUnpickler.DecodeCtx.addrMap` field | `scala.collection.Map[...]` | TreeUnpickler.scala:112 | OK |

Notable: the public-tier accessor `TastyOrigin.addrMap` is correctly widened to the **common supertype** `scala.collection.Map`, not narrowed to `mutable.HashMap`. This is the type stability point that lets Phase 5 swap the stored type to `IntMap` without breaking callers (TreeUnpickler.scala:37 binds the result by inference).

Error-path `FileResult` constructors in `readAndDecodeTastyFile` updated from `Map.empty` to `mutable.HashMap.empty[...]` at ClasspathOrchestrator.scala:422–424. Required for compilation per PHASE-4-PREP concern #4.

**Result: all expected type changes present and correct.**

---

## 4. CONTRIBUTING.md violations

Scanned committed diff against CONTRIBUTING.md core principles, API design, code conventions, testing patterns, Unsafe boundary.

- **Core principles / API design.** No public API delta in spirit: `AstUnpickler`, `ClasspathOrchestrator`, `PositionsUnpickler`, `CommentsUnpickler`, `TreeUnpickler`, `TypeUnpickler` are all `private[kyo]` or `kyo.internal.*`. `TastyOrigin` is in the public `Reflect.Symbol` tree; its `_addrMap` is `private[kyo]` (unchanged) and the public `addrMap` accessor return type widened from `Map` to `scala.collection.Map` (a supertype). This is a backward-compatible read-only widening.
- **Code conventions.** No new opaque types. No new `AllowUnsafe` introductions in production code (the only AllowUnsafe import in changed production code is at the pre-existing `_addrMap.set` site, unchanged). No `Sync.Unsafe.defer`, no `Frame.internal`, no `null`. No `asInstanceOf`. No default params on internal APIs. No semicolons.
- **Testing patterns.** Tests use the existing `Test` base trait. T-P4-2 wraps the orchestrator call in `Scope.run` (correct per kyo Scope/Async rule). T-P4-1 uses `runPass1WithArena` (existing helper). All three tests use `Result.{Success,Failure,Panic}` match arms with explicit panic re-throw — strict and matches the surrounding test style.
- **Unsafe boundary.** The PHASE-4-PREP single concern (#1 — TypeUnpickler/TreeUnpickler parameter widening) was correctly handled. The `_addrMap.set` site at AstUnpickler.scala:161 still imports `AllowUnsafe.embrace.danger` (this import existed prior; only the call's value type widened).
- **Mutation safety doc.** New scaladoc blocks at Pass1Result (AstUnpickler.scala:58–62) and FileResult (ClasspathOrchestrator.scala:50–53) document the single-fiber ownership invariant explicitly. Good practice; matches CONTRIBUTING type-level scaladoc guidance.

**Result: no violations.**

---

## 5. Unsafe markers in new code

`git show 279e6b3b2 | grep -E "^\\+" | grep -v "^\\+\\+\\+" | grep -E "asInstanceOf|Frame\\.internal|AllowUnsafe|Sync\\.Unsafe\\.defer|= null|: Null"`

Matches found, all benign:

1. `def addrMap(using AllowUnsafe): scala.collection.Map[Int, Reflect.Symbol] =` — the unchanged-from-before signature of the `TastyOrigin.addrMap` accessor; only the return type was widened. Pre-existing AllowUnsafe boundary, not a new one.
2. `assert(parentsByH != null, ...)`, `assert(childrenByH != null, ...)`, `assert(typeByH != null, ...)` in test T-P4-1. These `!= null` assertions are sanity probes on freshly constructed `mutable.HashMap` locals. Cosmetic; could be removed without weakening the test. **NOTE-class only.**
3. `import AllowUnsafe.embrace.danger` in T-P4-3. Required to call `TastyOrigin.addrMap(using AllowUnsafe)`. The plan and PHASE-4-PREP both prescribe AllowUnsafe access at this site; the API contract demands it. Acceptable test code.

No `asInstanceOf`. No `Frame.internal`. No `Sync.Unsafe.defer`. No production `null` assignments. No new AllowUnsafe boundaries in production code.

**Result: clean.**

---

## 6. Mutation-safety reasoning

Plan claim: Pass1Result and FileResult carry mutable.HashMap, but are single-threaded after Phase 3's merger fiber owns mutation.

Verification against committed code:

- **Pass1Result** (AstUnpickler.scala:58–70) is constructed once at the tail of `runPass1` on the decoder fiber. The four maps are produced by single-threaded population during the pass-1 walk and are never written after the constructor returns. The decoder fiber's only consumer of `pass1Result` is `decodeTastyBytes` (ClasspathOrchestrator.scala:441–467) which reads the maps and forwards them into a new `FileResult` (line 471). No concurrent writer.
- **FileResult** is put to the result channel by the decoder fiber after `decodeTastyBytes` returns; the channel put provides a happens-before edge to the merger fiber's take. The merger is the only reader of `FileResult` maps (`finalizeMerge` iterates `fr.parentsBySymbol`, `fr.childrenByOwner`, `fr.typeBySymbol` with for-comprehensions, which are read-only). No concurrent reader-writer pair.
- **TastyOrigin._addrMap** is set once (AstUnpickler.scala:161) under `SingleAssign.set` (write-once guard) during pass-1 of every file containing the origin. Read-side access (TreeUnpickler.scala:37 lazy body decode) is gated on `OpenState == Ready`, which only transitions after Phase C completes — after all decoder fibers and the merger fiber are joined. No decoder fiber can be writing while a TreeUnpickler reads.

The scaladoc invariants on Pass1Result (lines 58–62) and FileResult (lines 50–53) document this single-fiber ownership chain inline. The reasoning is sound.

**One subtle observation (NOTE):** the same `mutable.HashMap` instance for `addrMap` is **shared** between `Pass1Result.addrMap` and every `TastyOrigin._addrMap` for origins in that file (set at AstUnpickler.scala:161). After `runPass1` returns the only retainer is the TastyOrigin holders (FileResult does not carry addrMap). From that point forward all access is read-only via the `addrMap` accessor. Correct under the OpenState lifecycle invariant.

**Result: sound.**

---

## 7. Cross-platform consistency

Phase 4 changes live entirely under `kyo-reflect/shared/`. `scala.collection.mutable.HashMap` and `scala.collection.Map` are in `scala-library`, available on JVM, Scala.js, and Scala Native.

Commit message claims "Native and JS Test/compile clean." Files added/modified are all in `shared/`; no JVM-only or platform-specific files were touched. Cross-platform compile claim is plausible and not contradicted by file paths.

**Result: no cross-platform risk introduced by Phase 4.** (Caveat: the audit does not re-run cross-platform test compile; relies on agent's claim.)

---

## 8. Steering deviation

Files actually modified (from `git show 279e6b3b2 --name-only`):

```
kyo-reflect/shared/src/main/scala/kyo/Reflect.scala
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/CommentsUnpickler.scala
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/PositionsUnpickler.scala
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TreeUnpickler.scala
kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TypeUnpickler.scala
kyo-reflect/shared/src/test/scala/kyo/AstUnpicklerTest.scala
kyo-reflect/shared/src/test/scala/kyo/QueryApiTest.scala
kyo-reflect/shared/src/test/scala/kyo/TreeUnpicklerTest.scala
```

Plan's `### Files to modify` lists: AstUnpickler, ClasspathOrchestrator, TastyOrigin (in Reflect.scala), PositionsUnpickler, CommentsUnpickler, TypeUnpickler. PHASE-4-PREP's "Concerns" #1 explicitly anticipated the TypeUnpickler deviation.

**Deviations from plan text:**

1. **TreeUnpickler.scala modified** — not explicitly listed in execution-plan-perf.md Phase 4 file list, but listed in PHASE-4-PREP downstream-impact table (TreeUnpickler.scala:37) and required because TreeUnpickler's `DecodeCtx.addrMap` field receives the value returned by `TastyOrigin.addrMap` (whose return type widened). One-line type widening at line 112. **Acceptable** — necessary mechanical consequence; matches the pattern the plan applied to TypeUnpickler.
2. **TreeUnpicklerTest.scala modified** — one-line test fixture fix (`Map.empty` → `scala.collection.mutable.HashMap.empty`) at a `_addrMap.set(...)` call site (line 364). PHASE-4-PREP concern #5 stated "the only `set` site is AstUnpickler.scala line 158" — that overlooked this test site. The agent correctly fixed it. **Acceptable** — pure compile fix.

Both deviations are scope-preserving and mechanically forced by the prescribed type changes. No silent scope expansion.

**Result: deviations acceptable, NOTE-class.**

---

## 9. Anti-flakiness measures

- **T-P4-1:** type-level assignment compiles iff the four `Pass1Result` fields are `mutable.HashMap`. No I/O, no concurrency, no timing dependency. Behavioral assertions deterministic against the fixed `GenericBox.tasty` embedded fixture. **Robust.**
- **T-P4-2:** end-to-end via `openFixtureClasspath` (existing helper). Fixture classpath is read-only and stable. Wrapped in `Scope.run` — correct lifecycle management. Deterministic. **Robust.**
- **T-P4-3:** runs pass1 on `PlainClass.tasty` fixture bytes (no I/O). Uses `AllowUnsafe` to access `origin.addrMap`. Plan and PHASE-4-PREP both prescribe AllowUnsafe at this site; the API contract demands it. No safer alternative exists for reading `_addrMap` in test code. **Robust.** Minor NOTE: if a public non-AllowUnsafe `addrMap` accessor were added later, this test should migrate. Not a Phase 4 blocker.

The three `assert(... != null, ...)` lines in T-P4-1 are pure noise — `mutable.HashMap`-typed locals cannot be null in normal execution. Harmless. **NOTE-class.**

**Result: no anti-flakiness concerns.**

---

## Categorization

**BLOCKER:** (none).

**WARN:** (none).

**NOTE:**
1. Three `assert(... != null, ...)` checks in T-P4-1 are useless (mutable.HashMap-typed locals cannot be null). Harmless, but consider removing in a future cleanup pass.
2. T-P4-3 uses `AllowUnsafe.embrace.danger` in test code to access `TastyOrigin.addrMap`. The accessor's `using AllowUnsafe` is pre-existing and prescribed by the plan; the only NOTE is that test-side AllowUnsafe is uncommon and should migrate if a safe accessor is introduced post-Phase 4.
3. TreeUnpickler.scala and TreeUnpicklerTest.scala edits are not listed in the plan's "Files to modify" but are mechanically required by the prescribed type changes. PHASE-4-PREP anticipated the production-file edit; the test edit was not flagged in prep but is a necessary trivial fix. Scope-preserving.
4. Cross-platform compile cleanliness for JS and Native was claimed by the agent but is not re-verified in this audit. Files are all in `shared/`; risk is minimal but not zero. Phase 5/6 build runs will confirm.
5. The mutable.HashMap shared between `Pass1Result.addrMap` and `TastyOrigin._addrMap` (set via `SingleAssign.set(addrMap)`) is the SAME instance. After `runPass1` returns the only retainer is the TastyOrigin holders. Read-only access from then on is single-threaded under the `OpenState` guard. Safe by construction, but future refactors that expose Pass1Result via a different path should re-validate this aliasing.

---

## Phase 6 SLOT-A launch gate

**No BLOCKERs.** Phase 4 is correctly implemented: tests are strict (PRESENT_STRICT × 3), `.toMap` removals are surgical and complete, type changes match the plan and PHASE-4-PREP, mutation-safety reasoning is sound, no Unsafe expansion, no CONTRIBUTING violations.

**Phase 6 SLOT-A is clear to launch.**
