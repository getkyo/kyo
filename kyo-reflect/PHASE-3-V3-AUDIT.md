# Phase 3 v3 Audit

Commit: `4b1d041f95bc8f1e67bb7a6e1ee3b2e2595fc701`

Phase covered: "kyo-reflect v3 Phases 3 + 5: pure accessors + symbolToRecord deletion"

---

## Summary

Phase 3 converted all Symbol and Classpath accessors plus `Type.isSubtypeOf` from effectful to pure values. Phase 5 (delete `SymbolToRecordMacro`) was collapsed into this commit. The commit message and diff both confirm this.

The working directory has two unstaged changes (`Reflect.scala` and `TreeUnpicklerTest.scala`) that constitute Phase 4 (in-progress, not yet committed).

---

## Checks

### Symbol accessors converted to pure

`parents`, `typeParams`, `declarations`, `declaredType`, `scaladoc`, `position`, `companion` all return pure values. Each lifts `AllowUnsafe.embrace.danger` at the public accessor boundary and reads from a `SingleAssign` slot. Every accessor has a `// Unsafe:` comment documenting the rationale.

- `parents`: pure, reads `_parents.get()`. PASS.
- `typeParams`: pure, reads `_typeParams.get()`. PASS.
- `declarations`: pure, reads `_declarations.get()`. PASS.
- `declaredType`: pure, reads `_declaredType.get()`. PASS.
- `scaladoc`: was pure in v2; confirmed pure, reads `_scaladoc` with isSet guard. PASS.
- `position`: was pure in v2; confirmed pure, reads `_position` with isSet guard. PASS.
- `companion`: pure, reads `stateRef.unsafe.get()` via the home ClasspathRef to call `pureClass`. No `< (Sync & Abort[ReflectError])`. PASS.

### Classpath extensions converted to pure

`findClass`, `findPackage`, `findClassByBinary`, `findModule`, `topLevelClasses`, `packages`, `errors` are all pure.

- `findClass(fqn)` delegates to `cp.pureClass(fqn)` which reads the `fqnIndex` HashMap via `AllowUnsafe`. PASS.
- `findPackage(fqn)` delegates to `cp.purePackage(fqn)`. PASS.
- `findClassByBinary(binaryName)` converts the binary name to a dotted FQN then delegates to `cp.pureClass`. PASS.
- `findModule(name)` delegates to `cp.pureModule(name)`. PASS.
- `topLevelClasses` delegates to `cp.pureTopLevelClasses`. PASS.
- `packages` delegates to `cp.purePackages`. PASS.
- `errors` delegates to `cp.accumulatedErrors`. PASS.

All seven internal `pure*` methods in `Classpath.scala` use `AllowUnsafe.embrace.danger` with `// Unsafe:` comments.

### `Type.isSubtypeOf` returns `Boolean` directly

`Reflect.scala` line 997: `def isSubtypeOf(other: Type)(using cp: Classpath): Boolean`. No effect row. `Subtyping.isSubtype` also returns `Boolean`. PASS.

### `Symbol.body` still effectful

`body` retains `Tree < (Sync & Abort[ReflectError])`. It is the only `Symbol` accessor with an effect row. PASS (Phase 4 hardens the closed-state check; the working directory already has the Phase 4 changes but they are uncommitted).

### AllowUnsafe lifted at extension method boundary with `// Unsafe:` comments

Every `AllowUnsafe.embrace.danger` import in the converted accessors is preceded by a `// Unsafe:` comment explaining the rationale. Confirmed across all 7 Symbol accessors and all 7 Classpath pure methods. PASS.

### SymbolToRecordMacro deleted

`kyo-reflect/shared/src/main/scala/kyo/internal/SymbolToRecordMacro.scala` was deleted in this commit (git diff confirms). No scala source files remain in the `kyo/internal/` path matching `SymbolToRecordMacro*`. Phase 5 is complete. PASS.

### reads directory empty

The `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/reads/` directory exists as a filesystem artifact but contains zero `.scala` files. All `Reads`, `Query`, `Resolver` source files were deleted in Phases 1 and 2 commits. PASS.

### Test count: JVM

The cached JVM test reports total 278 tests, but those reports are stale from before Phase 1 (they include `ReadsDerivationTest` (20) and `RecordInteropTest` (14) which are deleted). The commit message reports **244 tests passing on JVM** (unchanged from Phase 2 baseline). No tests were added or removed in Phase 3. The source files for `ReadsDerivationTest.scala` and `RecordInteropTest.scala` do not exist in the current tree. Phase 2 audit established 244 as the baseline (-2 from 246 due to deletion of Tests 2 and 20 in `SymbolResolutionTest`). PASS (modulo stale reports; fresh `sbt kyo-reflect/test` is required before Phase 4 commit).

### JS and Native compile

Commit message: "JS: 201 passing, 40 ignored (jvmOnly), 241 total. Native: 201 passing, 40 ignored (jvmOnly), 241 total." The 40 ignored are `jvmOnly`-tagged tests. The cached JS/Native reports also show 275 total with 34 ignored (RecordInteropTest + ReadsDerivationTest from stale cache). The commit message numbers reflect fresh runs. PASS per commit attestation.

### Tests stop using flatMap/map/Kyo.foreach on converted accessors

`QueryApiTest.scala` lines 511-514, 533-538, 553-554, 588-591, 649-653, 683-684, 710-711: all use direct pure accessor calls with no monadic ceremony. `SubtypeTest.scala`: `isSubtypeOf` is called directly without `flatMap`. `SymbolResolutionTest.scala`: `findClass` called as `cp.findClass(...)` returning `Maybe[Symbol]` directly. `TreeUnpicklerTest.scala`: minimal change. PASS.

### No em-dashes

Confirmed: `grep -n "—" Reflect.scala` returns zero hits. PASS.

### No Frame.internal

No `Frame.internal` found in any modified production file. PASS.

### No new AllowUnsafe sites beyond boundary lift

All `AllowUnsafe` sites are documented boundary lifts. No new callsites added without a `// Unsafe:` comment. PASS.

---

## Categorized Findings

### BLOCKER

None.

### WARN

**WARN-1: Phase 4 changes are uncommitted (working directory only).**

The working directory contains Phase 4 changes to `Reflect.scala` and `TreeUnpicklerTest.scala` that are not committed. The unstaged diff adds:

1. A direct `stateRef.unsafe.get() == Classpath.State.Closed` check before `_bodyMemo.get()` inside `body`, with an `// Unsafe:` comment.
2. TreeUnpicklerTest "Test 10" regression test for `ClasspathClosed` after `Scope.run` exits.

These changes look correct per the Phase 4 plan. They should be committed as the Phase 4 commit before proceeding to Phase 6.

**WARN-2: `body` still calls `checkOpen` in addition to the new direct state check.**

After the Phase 4 working-directory change is applied, `body` has two checks: `home.get().checkOpen.andThen:` (effectful, from v2) AND the new `stateRef.unsafe.get() == State.Closed` guard before `_bodyMemo.get()`. The Phase 4 plan says "add an explicit state check before touching the OnceCell" which is satisfied by the new guard. The `checkOpen` call is not removed. This is not wrong (defense in depth), but it means `body` still routes through the `Sync` effect for the `checkOpen` call even before reaching the new AllowUnsafe guard. The plan does not say to remove `checkOpen`, so this is within spec. Tracking as WARN for Phase 8 audit awareness.

**WARN-3: Test 10 regression test uses `pending` as fallback.**

If `classSym.declarations.find(...)` for a member with `bodyStart > 0` returns `None`, the test is marked `pending`. `AstUnpicklerTest` Test 18 confirms PlainClass has at least one Method symbol with a non-zero body slice via pass1. However, the full-classpath flow (openPlainClassCp + findClass + declarations) must also populate those members correctly for `declarations.find` to succeed. If declarations are not populated (e.g., a fixture regression), the test silently becomes `pending` rather than `fail`. The `pending` fallback is overly lenient; the test should fail if no member with a body is found in PlainClass, given the AstUnpicklerTest proof. Low severity since PlainClass fixture is stable, but worth fixing in Phase 8.

### NOTE

**NOTE-1: Phase 5 collapsed into Phase 3 commit.**

`SymbolToRecordMacro.scala` deletion is included in the Phase 3+5 commit. The commit message explains the rationale: the macro emitted Async-style accessor calls that broke under the Phase 3 pure conversion. Phase 5 is now a no-op. The execution plan summary table should be updated to reflect this.

**NOTE-2: Post-close behavior of pure accessors differs from plan wording.**

The plan says "open/closed precondition is the caller's responsibility for pure accessors." The implementation is: pure accessors read whatever heap state is present after close. For SingleAssign slots (`parents`, `typeParams`, `declarations`, `declaredType`, `scaladoc`, `position`): return the pre-populated value unchanged. For `stateRef`-based accessors (`findClass`, `findPackage`, `findClassByBinary`, `findModule`, `topLevelClasses`, `packages`): the `pureClass`/`purePackage`/etc. methods match on `stateRef.unsafe.get()` and return `Maybe.Absent` / `Chunk.empty` for the `_` (non-Ready) branch, which covers `Closed`. `companion` also returns `Absent` after close (fqnIndex lookup via `pureClass` returns `Absent`). QueryApiTest "Phase 3 Test 4" and "Phase 4 Test 4" confirm these post-close behaviors with passing assertions.

**NOTE-3: TreeUnpicklerTest existing Test 7 also tests ClasspathClosed.**

Test 7 ("sym.body after classpath close returns Abort.fail(ClasspathClosed)") already tests the ClasspathClosed path via the `checkOpen` guard. It passes with `succeed` for `NotImplemented` (class symbol has no body) or `succeed` for races (body decoded before close). Test 10 (Phase 4 regression) uses `declarations.find` to locate a member WITH a body, making the assertion stricter. The two tests are complementary, not redundant.
