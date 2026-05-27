# Phase 3 v3 Prep

Plan reference: `execution-plan-v3.md` Phase 3  
Baseline after Phase 2 audit: **244 tests** (not 245; see PHASE-2-V3-AUDIT.md WARN-1).

---

## Scope

Convert all `Symbol` accessors and `Classpath` extension methods that carry `Sync & Abort[ReflectError]` to pure values. After this phase, `body` is the sole `Symbol` accessor with an effect row.

---

## Before / After: Verbatim Signatures

### Symbol accessors

#### declaredType

```scala
// BEFORE (Reflect.scala line 521)
def declaredType(using Frame): Type < (Sync & Abort[ReflectError])

// AFTER
def declaredType: Type
```

#### parents

```scala
// BEFORE (Reflect.scala line 538)
def parents(using Frame): Chunk[Type] < (Sync & Abort[ReflectError])

// AFTER
def parents: Chunk[Type]
```

#### typeParams

```scala
// BEFORE (Reflect.scala line 547)
def typeParams(using Frame): Chunk[Symbol] < (Sync & Abort[ReflectError])

// AFTER
def typeParams: Chunk[Symbol]
```

#### declarations

```scala
// BEFORE (Reflect.scala line 556)
def declarations(using Frame): Chunk[Symbol] < (Sync & Abort[ReflectError])

// AFTER
def declarations: Chunk[Symbol]
```

#### companion

```scala
// BEFORE (Reflect.scala line 570)
def companion(using Frame): Maybe[Symbol] < (Sync & Abort[ReflectError])

// AFTER
def companion: Maybe[Symbol]
```

#### scaladoc (already pure -- confirm no regression)

```scala
// BEFORE (Reflect.scala line 485)
def scaladoc: Maybe[String]   // already pure

// AFTER
def scaladoc: Maybe[String]   // unchanged
```

#### position (already pure -- confirm no regression)

```scala
// BEFORE (Reflect.scala line 499)
def position: Maybe[Position]   // already pure

// AFTER
def position: Maybe[Position]   // unchanged
```

#### body (remains effectful -- must not be changed)

```scala
// BEFORE (Reflect.scala line 619)
def body(using Frame): Tree < (Sync & Abort[ReflectError])

// AFTER
def body(using Frame): Tree < (Sync & Abort[ReflectError])   // unchanged
```

---

### Classpath extension methods

#### findClass

```scala
// BEFORE (Reflect.scala line 913)
def findClass(fqn: String): Maybe[Symbol] < (Sync & Abort[ReflectError])

// AFTER
def findClass(fqn: String): Maybe[Symbol]
```

#### findPackage

```scala
// BEFORE (Reflect.scala line 914)
def findPackage(fqn: String): Maybe[Symbol] < (Sync & Abort[ReflectError])

// AFTER
def findPackage(fqn: String): Maybe[Symbol]
```

#### packages

```scala
// BEFORE (Reflect.scala line 915)
def packages: Chunk[Symbol] < (Sync & Abort[ReflectError])

// AFTER
def packages: Chunk[Symbol]
```

#### topLevelClasses

```scala
// BEFORE (Reflect.scala line 916)
def topLevelClasses: Chunk[Symbol] < (Sync & Abort[ReflectError])

// AFTER
def topLevelClasses: Chunk[Symbol]
```

#### errors

```scala
// BEFORE (Reflect.scala line 917)
def errors: Chunk[ReflectError] < Sync

// AFTER
def errors: Chunk[ReflectError]
```

#### findModule

```scala
// BEFORE (Reflect.scala line 924)
def findModule(name: String): Maybe[ModuleDescriptor] < (Sync & Abort[ReflectError])

// AFTER
def findModule(name: String): Maybe[ModuleDescriptor]
```

#### findClassByBinary

```scala
// BEFORE (Reflect.scala line 931)
def findClassByBinary(binaryName: String): Maybe[Symbol] < (Sync & Abort[ReflectError])

// AFTER
def findClassByBinary(binaryName: String): Maybe[Symbol]
```

---

### Type.isSubtypeOf

```scala
// BEFORE (Reflect.scala line 958)
def isSubtypeOf(other: Type)(using cp: Classpath)(using Frame): Boolean < (Sync & Abort[ReflectError])

// AFTER
def isSubtypeOf(other: Type)(using cp: Classpath): Boolean
```

---

## File:Line Anchors for Accessors in Reflect.scala

All anchors reference `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` as of commit `624a37499`.

| Accessor | Line (approx) | Current effect row |
|---|---|---|
| `Symbol.declaredType` | 521 | `Sync & Abort[ReflectError]` |
| `Symbol.parents` | 538 | `Sync & Abort[ReflectError]` |
| `Symbol.typeParams` | 547 | `Sync & Abort[ReflectError]` |
| `Symbol.declarations` | 556 | `Sync & Abort[ReflectError]` |
| `Symbol.companion` | 570 | `Sync & Abort[ReflectError]` |
| `Symbol.scaladoc` | 485 | pure (no change) |
| `Symbol.position` | 499 | pure (no change) |
| `Symbol.body` | 619 | `Sync & Abort[ReflectError]` (stays) |
| `cp.findClass` | 913 | `Sync & Abort[ReflectError]` |
| `cp.findPackage` | 914 | `Sync & Abort[ReflectError]` |
| `cp.packages` | 915 | `Sync & Abort[ReflectError]` |
| `cp.topLevelClasses` | 916 | `Sync & Abort[ReflectError]` |
| `cp.errors` | 917 | `Sync` |
| `cp.findModule` | 924 | `Sync & Abort[ReflectError]` |
| `cp.findClassByBinary` | 931 | `Sync & Abort[ReflectError]` |
| `Type.isSubtypeOf` | 958 | `Sync & Abort[ReflectError]` |

---

## AllowUnsafe Lift Pattern

Each accessor body calls a `SingleAssign.get()` (or `Memo.get()` for `_bodyMemo`) or reads `stateRef.unsafe.get()`. Both are unsafe-tier operations requiring an `AllowUnsafe` proof.

Pattern used by `scaladoc` and `position` (already pure; use this as the model):

```scala
def scaladoc: Maybe[String] =
    // Unsafe: SingleAssign.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public accessor boundary.
    import AllowUnsafe.embrace.danger
    if _scaladoc.isSet then _scaladoc.get()
    else Maybe.Absent
```

For SingleAssign-backed accessors (`parents`, `typeParams`, `declarations`, `declaredType`), the pattern is:

```scala
def parents: Chunk[Type] =
    // Unsafe: SingleAssign.get() is an unsafe-tier helper; AllowUnsafe is embraced here at the public accessor boundary.
    // Reading immutable Ready-state data set during open, before any user access; no classpath I/O.
    import AllowUnsafe.embrace.danger
    _parents.get()
```

Note: `_parents.get()` throws `IllegalStateException` if the slot is unset. This must never happen if `open` returns properly (the orchestrator populates all SingleAssign slots during Phase B before transitioning to Ready). See Edge Cases section.

For `companion`, which calls `home.get().lookupClass(...)`, the pure form requires converting the now-pure `lookupClass` call directly:

```scala
def companion: Maybe[Symbol] =
    if isJava then Maybe.Absent
    else if !home.isAssigned then Maybe.Absent
    else
        // Unsafe: stateRef.unsafe.get() reads classpath state; AllowUnsafe is embraced at public accessor boundary.
        import AllowUnsafe.embrace.danger
        home.get().stateRef.unsafe.get() match
            case s: kyo.internal.reflect.query.Classpath.State.Ready =>
                kind match
                    case SymbolKind.Class | SymbolKind.Trait =>
                        val companionFqn = ...
                        Maybe(s.fqnIndex.get(companionFqn).orNull).flatMap:
                            case s if s.kind == SymbolKind.Object => Maybe(s)
                            case _                                => Maybe.Absent
                    ...
            case _ => Maybe.Absent
```

The key difference from the current effectful implementation is that `lookupClass` (still `Sync & Abort`) is bypassed in favor of a direct `s.fqnIndex.get(...)` read inside a `stateRef.unsafe.get()` pattern match. This avoids re-entering the effect row.

For `Classpath` extension methods (findClass, findPackage, etc.), the underlying `Classpath` methods need pure counterparts. The simplest approach is to add pure helper methods to the internal `Classpath` class that read `stateRef.unsafe.get()` under `AllowUnsafe`, then delegate the extension methods to those:

```scala
// In internal Classpath class:
private[kyo] def lookupClassPure(fqn: String)(using AllowUnsafe): Maybe[Reflect.Symbol] =
    stateRef.unsafe.get() match
        case s: Classpath.State.Ready => Maybe(s.fqnIndex.get(fqn).orNull)
        case _                        => Maybe.Absent

// In Reflect.scala extension:
def findClass(fqn: String): Maybe[Symbol] =
    // Unsafe: reads fqnIndex from immutable Ready state; AllowUnsafe embraced at public API boundary.
    import AllowUnsafe.embrace.danger
    cp.lookupClassPure(fqn)
```

Alternatively, inline the `stateRef.unsafe.get()` pattern match directly in the extension method body. Either approach is acceptable. The `// Unsafe:` comment is required at every site.

For `errors`, the current implementation calls `cp.accumulatedErrors` which already uses `stateRef.unsafe.get()` under `AllowUnsafe`. The extension method just needs to drop the `Sync.defer` wrapper:

```scala
// BEFORE:
def errors: Chunk[ReflectError] < Sync = Sync.defer(cp.accumulatedErrors)

// AFTER:
def errors: Chunk[ReflectError] =
    // Unsafe: delegates to accumulatedErrors which reads stateRef under AllowUnsafe.
    import AllowUnsafe.embrace.danger
    cp.accumulatedErrors
```

`accumulatedErrors` itself already imports `AllowUnsafe.embrace.danger` internally, so the external import in the extension body is belt-and-suspenders (the compiler requires a proof at the call site because `accumulatedErrors` lacks a `using AllowUnsafe` parameter -- it just internally imports). Double-check this: if `accumulatedErrors` already satisfies without requiring an external proof, the import in the extension may be optional. In either case the `// Unsafe:` comment must be present.

For `Type.isSubtypeOf`, `Subtyping.isSubtype` currently returns `Boolean < (Sync & Abort[ReflectError])`. After Phase 3, it must be changed to return `Boolean` directly. The classpath lookups inside `Subtyping` currently go through `checkOpen` / `lookupClass`; after Phase 3 those become pure reads. The subtyping implementation itself must be updated to use pure lookups. This is the most invasive change in Phase 3.

---

## Edge Cases

### SingleAssign.get() before transitionToReady (pre-Ready access)

`SingleAssign.get()` throws `IllegalStateException` if the slot has not been assigned. The contract is: the `open` factory only returns the `Classpath` handle to user code after `ClasspathOrchestrator.openImpl` calls `transitionToReady`, which is after Phase B (all file decodes) and Phase C (placeholder resolution and slot population) complete. Therefore:

- User code can only obtain a `Classpath` reference after `transitionToReady` fires.
- `transitionToReady` populates every `Symbol._parents`, `._typeParams`, `._declarations`, `._declaredType` slot (via `mergeResults` in `ClasspathOrchestrator`) before setting the state to `Ready`.
- Therefore no user code can reach a pure accessor before all slots are set.

The only exception is `fromPickles(Seq.empty)`, which creates a `Classpath` with no symbols at all. There are no `Symbol` instances to call accessors on, so the exception path is unreachable.

The `// Unsafe:` comment at each accessor should document: "Reading immutable Ready-state data set during open, before any user access. Cannot be called before open returns; see open factory contract."

### Post-close access (ClasspathClosed)

Phase 3 does **not** add closed-state checks to pure accessors. The plan explicitly states: "open/closed precondition is the caller's responsibility for pure accessors, enforced by `body`'s explicit check (Phase 4)." After close:

- `Symbol._parents`, `._typeParams`, `._declarations`, `._declaredType`, `._scaladoc`, `._position` are all already-set `SingleAssign` slots. Their contents do not change after close (immutable). The pure accessor returns the heap data without checking state. This is intentional.
- `cp.findClass` etc. read `stateRef.unsafe.get()`. After close, `stateRef` holds `State.Closed`. A pure implementation that pattern-matches on `Closed` returns `Maybe.Absent` for lookups and `Chunk.empty` for collection accessors. No error is surfaced.

This is documented in the plan as intentional. Phase 4 adds the ClasspathClosed check only to `body`.

The tests in `QueryApiTest` that test `sym.parents` after classpath close (Test "Phase 3 Test 4: sym.parents after classpath close returns ClasspathClosed") will **break** in Phase 3 because parents will no longer return `ClasspathClosed` -- they will return the pre-populated `Chunk[Type]` from the slot. Those tests must be updated to assert that the value is returned (not a failure).

Similarly "Phase 4: sym.companion after classpath close returns ClasspathClosed" will change: after Phase 3, `companion` returns `Maybe.Absent` (state is Closed, fqnIndex lookup returns empty). That is not a failure; the test must be updated to assert `Absent`.

These are test updates required in Phase 3, not new bugs.

### companion: fqnIndex is not accessible post-close

After close, `stateRef.unsafe.get()` returns `State.Closed`, which has no `fqnIndex`. The `companion` pure implementation must handle `Closed` by returning `Maybe.Absent`. Same for `findClass` / `findPackage` etc.

---

## Test Impact Analysis

### Tests that use effect threading on accessors -- require rewrite

These tests use `flatMap` / `.map` on accessor results because the accessor currently returns a `< (Sync & Abort[ReflectError])` value. After Phase 3, the accessor returns a plain value and the chaining ceremony must be removed.

**QueryApiTest.scala** (the majority of impacted tests):

- Line 507-522: `sym.parents` flatMapped inside `Abort.run[ReflectError](...).map:`. After Phase 3, `sym.parents` is a plain `Chunk[Type]`; the flatMap chain collapses to direct access.
- Line 527-540: `sym.typeParams` same pattern.
- Line 545-562: `sym.declarations` same pattern.
- Line 564-589: `sym.parents` after close -- test logic changes (see Edge Cases above).
- Line 594-633: `sym.parents.flatMap: parents => sym.typeParams.flatMap: typeParams => sym.declarations.map: decls` -- becomes `val parents = sym.parents; val typeParams = sym.typeParams; val decls = sym.declarations`.
- Line 641-667: `classSym.companion` flatMapped -- becomes direct `.companion` call.
- Line 671-697: `objectSym.companion` same.
- Line 701-718: `sym.companion` same.
- Line 720-743: `sym.companion` after close test -- logic changes (see Edge Cases above).
- Line 750-775: `sym.declarations.flatMap: decls => ... xSym.declaredType` -- both collapse.
- Line 780-811: same.
- Line 815-838: same.
- Line 887-915: `sym.declarations.flatMap ... sym.declaredType` after close -- logic changes (ClasspathClosed no longer returned).
- Line 81-87: `cp.findClass(...).map: result =>` -- findClass now returns `Maybe[Symbol]` directly; the `Abort.run[ReflectError]` wrapper around `openFixtureClasspath.flatMap: cp => cp.findClass(...)` collapses to `openFixtureClasspath.map: cp => cp.findClass(...)`. Every test that wraps `cp.findClass` / `cp.findPackage` etc. in `Abort.run[ReflectError]` must drop the `Abort.run` wrapper (or at minimum, the accessor call no longer needs it).

**SubtypeTest.scala**:

Each test calls `intType.isSubtypeOf(intType).map: result =>` -- after Phase 3, `isSubtypeOf` returns `Boolean` directly. All `.map: result =>` chains in SubtypeTest collapse to direct value access or can be eliminated.

**SnapshotRoundTripTest.scala** (line 393 area):

`sym.body` remains effectful and is unaffected. No test-impact there.

**TreeUnpicklerTest.scala**:

`sym.body` remains effectful. Tests calling `.body` are unaffected.

**UnifiedModelTest.scala**:

References `readClassBytes`, `tastySymbols` helpers that call `sym.parents`, `sym.typeParams`, `sym.declarations`. These helpers currently return `< (Sync & Abort[ReflectError])` values. After Phase 3 the helpers simplify and the test bodies using them simplify as well.

### Tests that are unaffected by Phase 3

- `AstUnpicklerTest.scala`: works at the `Pass1Result` level, no public accessors.
- `AttributeUnpicklerTest.scala`: low-level; no accessor calls.
- `ByteViewTest.scala`: pure data structure.
- `ClassfileReaderTest.scala`: operates on internal `ClassfileResult`; does not call public symbol accessors.
- `ClasspathRefDedupTest.scala`: tests `ClasspathRef` assignment.
- `CommentsUnpicklerTest.scala`: calls `sym.scaladoc` which is already pure -- no change needed.
- `DeclarationTableTest.scala`: internal.
- `FlagsTest.scala`: pure.
- `InternerTest.scala`: pure.
- `JavaSignaturesTest.scala`: internal.
- `JavaSymbolTest.scala`: calls `sym.fullName`, `sym.binaryName`, `sym.isJava` -- all pure already.
- `ModuleInfoTest.scala`: calls `cp.findModule` which becomes pure -- test simplifies but behavior is the same.
- `NameUnpicklerTest.scala`: internal.
- `PositionsUnpicklerTest.scala`: calls `sym.position` which is already pure.
- `SnapshotRoundTripTest.scala`: mostly affected by `sym.body` which stays; the `sym.declarations.flatMap` chains need updating.
- `SymbolResolutionTest.scala`: calls `cp.findClass` (which becomes pure) -- `Abort.run[ReflectError]` wrappers around `findClass` alone can be removed; the outer scope and orchestrator calls still need `Abort.run`.
- `TastyHeaderTest.scala`: internal.
- `TreeUnpicklerTest.scala`: `sym.body` unchanged.
- `TypeArenaTest.scala`: internal.
- `TypeOpsTest.scala`: internal.
- `TypeUnpicklerTest.scala`: internal.
- `Utf8Test.scala`: pure.
- `VarintTest.scala`: pure.

---

## ClasspathClosed Handling

Phase 3 does **not** add `ClasspathClosed` checks to pure accessors. This is explicit in the plan: "open/closed precondition is the caller's responsibility for pure accessors."

Behavior post-close:

| Accessor | Post-close behavior |
|---|---|
| `sym.parents` | Returns the pre-populated `Chunk[Type]` from `_parents.get()`. No failure. |
| `sym.typeParams` | Returns pre-populated `Chunk[Symbol]`. No failure. |
| `sym.declarations` | Returns pre-populated `Chunk[Symbol]`. No failure. |
| `sym.declaredType` | Returns pre-populated `Type`. No failure. |
| `sym.scaladoc` | Already pure; returns `Maybe[String]`. No failure. |
| `sym.position` | Already pure; returns `Maybe[Position]`. No failure. |
| `sym.companion` | Reads `stateRef.unsafe.get()` -> `Closed`; returns `Maybe.Absent`. No failure. |
| `cp.findClass` | Reads `stateRef.unsafe.get()` -> `Closed`; returns `Maybe.Absent`. No failure. |
| `cp.findPackage` | Same. |
| `cp.topLevelClasses` | Returns `Chunk.empty` (Closed branch). No failure. |
| `cp.packages` | Returns `Chunk.empty` (Closed branch). No failure. |
| `cp.errors` | Reads `accumulatedErrors`; `Closed` branch returns `Chunk.empty`. |
| `cp.findModule` | Reads stateRef; `Closed` returns `Maybe.Absent`. |
| `cp.findClassByBinary` | Delegates to `findClass`; `Closed` returns `Maybe.Absent`. |
| `Type.isSubtypeOf` | Reads parent chain via `cp` which is post-close; will return `false` conservatively (no parents found, no ancestry match). No failure. |
| `sym.body` | `ClasspathClosed` check remains (Phase 4 will make this explicit). |

---

## Concerns

### CONCERN-1: companion implementation complexity

`companion` currently uses effectful `home.get().lookupClass(...)` which returns `Maybe[Symbol] < (Sync & Abort[ReflectError])`. Making it pure requires bypassing the effectful `lookupClass` and reading `stateRef.unsafe.get()` directly inside an `AllowUnsafe` scope. The logic is not large but involves a `stateRef.unsafe.get()` call that was previously hidden behind `checkOpen`. The `// Unsafe:` comment must be precise.

The internal `Classpath.lookupClass` method still carries `Sync & Abort[ReflectError]` after Phase 2 (it is correct for it to do so; it is a `private[kyo]` method used by the extension). Phase 3 must either:

1. Add a pure companion to `lookupClass` in the `Classpath` class (e.g., `lookupClassPure`), or
2. Inline the HashMap lookup directly in the extension body / `companion` implementation.

Option 1 is cleaner. Whichever is chosen, document it clearly.

### CONCERN-2: Subtyping.isSubtype internal effect row

`kyo.internal.reflect.type_.Subtyping.isSubtype` currently returns `Boolean < (Sync & Abort[ReflectError])`. The plan says `Type.isSubtypeOf` becomes pure. This cascades into the `Subtyping` implementation: all classpath lookups inside `Subtyping.isSubtype` must also become direct pure reads. Inspect `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/type_/Subtyping.scala` to enumerate all effectful calls before implementing.

### CONCERN-3: Test updates are extensive

Tests in `QueryApiTest` that test post-close behavior (e.g., "Phase 3 Test 4: sym.parents after classpath close returns ClasspathClosed") currently assert a `ReflectError.ClasspathClosed` failure. After Phase 3 those assertions change to assert that the value is returned (parents are still accessible post-close). These are legitimate test updates, not reward-hacking: the behavior change is the point of Phase 3.

**Do not** weaken these tests by simply removing the failure assertion and adding a trivial `succeed`. Replace them with a positive assertion that the value returned is a valid (non-null, non-exception) result.

### CONCERN-4: Baseline test count is 244, not 245

The Phase 2 audit (WARN-1) established that the actual baseline is 244. The plan summary table says Phase 3 target is 245. The correct target is **244** (no tests added or removed in Phase 3 per the plan). Verify with `sbt 'kyo-reflect/test' 2>&1 | tail -5` after implementing.

### CONCERN-5: Frame parameter removal

Current effectful accessors carry `(using Frame)` in their signatures. These must be removed when the signature becomes pure. Search for residual `using Frame` on the pure accessor signatures after implementation.

### CONCERN-6: errors extension method -- AccumulatedErrors internal AllowUnsafe

`cp.accumulatedErrors` (internal `Classpath` method) currently performs its own `import AllowUnsafe.embrace.danger` internally. The extension method wraps it in `Sync.defer(cp.accumulatedErrors)`. After Phase 3 the `Sync.defer` wrapper drops and the extension body calls `cp.accumulatedErrors` directly. Since `accumulatedErrors` does not declare `using AllowUnsafe` in its signature (it imports internally), the extension body does not need a proof. However, add a `// Unsafe: delegates to accumulatedErrors which reads stateRef.unsafe.get() under AllowUnsafe internally` comment in the extension body for auditability.

---

## Verification Commands

After implementation, run sequentially per `feedback_sequential_test_runs`:

```
sbt 'kyo-reflect/test' 2>&1 | tail -10
sbt 'kyo-reflectJS/test' 2>&1 | tail -10
sbt 'kyo-reflectNative/test' 2>&1 | tail -10
```

Expected:
- JVM: 244 tests passing (no change from Phase 2 baseline).
- JS: 201 tests passing, 40 ignored.
- Native: 201 tests passing, 40 ignored.

Supervisor check from the plan:
- `grep "def parents" kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` shows return type `Chunk[Type]` with no `<` effect row.
- `sym.body` is the only `Symbol` accessor with a `< (Sync & Abort[ReflectError])` return type.
- All test files compile and pass without effect threading on the listed accessors.
- JS and Native compile clean.
