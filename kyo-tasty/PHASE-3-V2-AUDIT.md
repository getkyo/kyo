# Phase 3 v2 Post-Commit Audit

**Commit**: f431545af  
**Phase**: G21 (Symbol.parents) + G22 (Symbol.typeParams) + G23 (Symbol.declarations)  
**Plan reference**: execution-plan-v2.md lines 95-143  
**Date**: 2026-05-25

---

## Summary Verdict

Implementation complete. All 7 plan-mandated tests present and strict after agent remediation
following pulse 1 (which flagged 6 of 7 missing). Two non-blocking issues tracked in cleanup
tasks. No blockers.

---

## Checklist

### Core implementation

| Check | Result | Evidence |
|---|---|---|
| Reflect.scala line 252 stub replaced | PASS | `parents` reads `_parents.get()` via SingleAssign behind `home.isAssigned` guard; no `stub(...)` call |
| Reflect.scala line 260 stub replaced | PASS | `typeParams` reads `_typeParams.get()` via SingleAssign behind `home.isAssigned` guard |
| Reflect.scala line 268 stub replaced | PASS | `declarations` reads `_declarations.get()` via SingleAssign behind `home.isAssigned` guard |
| Internal Symbol has `_parents` SingleAssign field | PASS | `Reflect.scala` (committed): `private[kyo] val _parents: SingleAssign[Chunk[Type]] = new SingleAssign` |
| Internal Symbol has `_typeParams` SingleAssign field | PASS | `private[kyo] val _typeParams: SingleAssign[Chunk[Symbol]] = new SingleAssign` |
| Internal Symbol has `_declarations` SingleAssign field | PASS | `private[kyo] val _declarations: SingleAssign[Chunk[Symbol]] = new SingleAssign` |
| All 3 `@note Not implemented` scaladoc comments removed | PASS | All three accessors now have implementation body; no `@note Not implemented` present |

### AstUnpickler

| Check | Result | Evidence |
|---|---|---|
| `Pass1Result` gains `parentsBySymbol` field | PASS | `Pass1Result(... parentsBySymbol: Map[Reflect.Symbol, Chunk[Reflect.Type]], ...)` at line 63 |
| `Pass1Result` gains `childrenByOwner` field | PASS | `childrenByOwner: Map[Reflect.Symbol, Chunk[Reflect.Symbol]]` at line 64 |
| `walkStats` populates `parentsBySymbol` for class symbols | PASS | TYPEDEF/TEMPLATE branch calls `decodeTemplateParents` and assigns `parentsBySymbol(sym) = Chunk.from(decodedParents)` |
| `childrenByOwner` built post-walk from `allSymbols` | PASS | Post-walk loop groups non-root symbols by `sym.owner` into `childrenByOwner` mutable map |

### ClasspathOrchestrator.mergeResults

| Check | Result | Evidence |
|---|---|---|
| Populates `_parents` from `parentsBySymbol` after Phase C | PASS | `for (sym, parents) <- fr.parentsBySymbol do sym._parents.set(parents)` in `mergeResults` |
| Populates `_typeParams` from `childrenByOwner` (TypeParam-kinded) | PASS | Filter `children` by `s.kind == SymbolKind.TypeParam` then `sym._typeParams.set(typeParams)` |
| Populates `_declarations` from `childrenByOwner` (all members) | PASS | `sym._declarations.set(declarations)` using all children |
| Fallback: symbols with no map entry get empty Chunks | PASS | Post-loop: `if !sym._parents.isSet then sym._parents.set(Chunk.empty)` for all three fields |

### ClassfileUnpickler

| Check | Result | Evidence |
|---|---|---|
| Populates `_parents` on `classSymbol` | PASS | `result.classSymbol._parents.set(result.parents)` after `readFromRaw` |
| Populates `_typeParams` on `classSymbol` | PASS | `result.classSymbol._typeParams.set(result.typeParams)` |
| Populates `_declarations` on `classSymbol` | PASS | `result.classSymbol._declarations.set(result.symbols)` |
| Member symbols get empty Chunks for all three fields | PASS | Loop over `result.symbols` sets `Chunk.empty` for parents/typeParams/declarations on each |
| TypeParam symbols get empty Chunks | PASS | Loop over `result.typeParams` sets `Chunk.empty` for all three |

### SnapshotReader

| Check | Result | Evidence |
|---|---|---|
| Snapshot-restored symbols get all three fields set | PASS | `if !sym._parents.isSet then sym._parents.set(Chunk.empty)` pattern for all three |
| Empty Chunks used (full restoration deferred) | NOTE | Acceptable per plan: "full restoration covered by a future BODY_BYTES phase" |

---

## Test Coverage (Plan: 7 tests)

### QueryApiTest (plan: 5 tests, including Java proxy as Test 5)

| Plan test | Status | Test name |
|---|---|---|
| T1: `sym.parents` non-empty Chunk[Type] for PlainClass | PASS (strict) | "Phase 3: sym.parents for PlainClass returns a non-empty Chunk[Type]" |
| T2: `GenericBox[A]` typeParams length 1, name "A" | PASS (strict) | "Phase 3: sym.typeParams for GenericBox[A] returns length 1 with name A" |
| T3: `sym.declarations` for PlainClass includes field "x" | PASS (strict) | "Phase 3: sym.declarations for PlainClass contains known field x" |
| T4: `sym.parents` after Scope close returns ClasspathClosed | PASS (strict) | "Phase 3: sym.parents after classpath close returns ClasspathClosed" |
| T5: Java classfile symbol parents/typeParams/declarations | PASS (strict) | "Phase 3: Java classfile symbol parents, typeParams, declarations are accessible" (taggedAs jvmOnly) |

Note: plan line 126 specifies the Java test use a "java.lang.String proxy fixture"; the actual test
uses an `ArrayRecord.class` Java record fixture instead of String. The plan was loose about the
specific Java fixture; the test exercises all three accessors on a classfile-sourced symbol and
satisfies the G21/G22/G23 classfile verification intent. Acceptable.

### AstUnpicklerTest (plan: 2 tests)

| Plan test | Status | Test name |
|---|---|---|
| T6: `Pass1Result.parentsBySymbol` for PlainClass has non-empty entry | PASS (strict) | "Phase 3: Pass1Result.parentsBySymbol for PlainClass contains entry with non-empty parents" -- directly asserts `r.parentsBySymbol.contains(classSym)` and `r.parentsBySymbol(classSym).nonEmpty` |
| T7: `Pass1Result.childrenByOwner` maps PlainClass to members including "x" and "<init>" | PASS (strict) | "Phase 3: Pass1Result.childrenByOwner for PlainClass maps class symbol to members including x and <init>" |

Context: pulse 1 review flagged 6 of 7 tests missing (T1-T5 absent, T6 weakened via indirect
placeholder check, T7 absent). The agent remediated all seven before committing; post-commit state
is fully compliant.

---

## Warnings (WARN)

**WARN-1**: `assignHomesForTest` and `assignExtraHomes` at `Reflect.scala` lines 494 and 498 are
`private[kyo]` methods inside the public `Classpath` companion, which pollutes the public API file
surface. Both are exclusively used by test helpers. Tracked in cleanup task #121 (plan: move to
`kyo.internal.reflect.query` before final green run). No functional impact.

**WARN-2**: Two bug fixes were included in this commit with no regression tests:

1. `IdentityHashMap[ClasspathRef, Boolean]` auto-unbox issue: `HashMap.get` returns `null` for
   absent keys, which Java auto-unboxes to `false` when assigned to a `Boolean`, making the
   "not previously seen" check silently fail for every iteration. Fixed by replacing with
   `HashSet[ClasspathRef]` whose `add()` returns `true` exactly once per new entry.

2. `ClasspathRef` dedup: multiple symbols share one `ClasspathRef` per TASTy file; the naive
   loop called `.assign` for each symbol and threw `SingleAssign already set` on the second.
   Fixed via the same `HashSet` dedup.

Both fixes are correct and the behavior change is verified by the passing test suite. However
there are no explicit regression tests for these two edge cases. Tracked in task #123.

---

## Notes (NOTE)

**NOTE-1**: `SingleAssign.isSet` (using `AllowUnsafe`) was added to
`kyo/internal/reflect/symbol/SingleAssign.scala` in this commit as a supporting primitive. The
addition is correctly scoped: `isSet` requires `AllowUnsafe`, has a scaladoc explaining the
side-effecting read, and is only used in the fallback zero-fill loops in `mergeResults`,
`ClassfileUnpickler`, and `SnapshotReader`. All call sites carry `// Unsafe:` comments.

**NOTE-2**: `FileResult` was extended with `parentsBySymbol` and `childrenByOwner` fields to
carry the indexed maps from `Pass1Result` through to `mergeResults`. The `ClasspathRef.scala`
diff shows `FileResult` updated accordingly. No public API change.

**NOTE-3**: CONTRIBUTING.md has no kyo-reflect-specific section; no alignment issues apply.

---

## Code Quality Checks

| Check | Result |
|---|---|
| No em-dashes in any modified file | PASS |
| No `Frame.internal` in production code | PASS |
| No new `asInstanceOf` in macro or production source | PASS (new `asInstanceOf` sites are in pre-existing `SingleAssign.scala` internals, not Phase 3 additions) |
| All new `AllowUnsafe.embrace.danger` blocks carry `// Unsafe:` comment | PASS |
| No `null` introduced in new Phase 3 blocks | PASS |
| No `var` for shared state | PASS |

---

## Final Verdict

**PASS.** Phase 3 is complete. 7 of 7 tests strict and present. Three stubs replaced with real
SingleAssign reads. Both TASTy and classfile population paths wired. SnapshotReader provides empty
Chunks as designed. Two WARNs tracked in existing cleanup tasks (#121, #123); neither is a blocker.
Total test count: 215 (208 prior + 7 new), all passing.
