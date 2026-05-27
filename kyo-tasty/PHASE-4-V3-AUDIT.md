# Phase 4 v3 Audit

Commit audited: `30f06bd54bb7d88c698d9f704bd0db4e5ac9fd9` (kyo-reflect v3 Phase 4: body strict ClasspathClosed check + regression test)

Note: at HEAD the worktree is at commit `73855f5ccb6e897117bbc5ea32914a3cad1ce481` (Phase 6: rename Memo to OnceCell), which was committed after Phase 4. This audit evaluates Phase 4 content as it landed in `30f06bd54` and remains structurally present (with Memo renamed to OnceCell) in HEAD.

---

## Check 1: body accessor has explicit closed-classpath guard inside AllowUnsafe block

**PASS**

The committed Phase 4 body of `Symbol.body` contains:

```scala
import AllowUnsafe.embrace.danger
// Unsafe: Reading classpath state under AllowUnsafe to detect closed classpath
// before body decode; state transitions are monotonic (Closed is terminal) so
// a stale read returns a conservative result.
if home.get().isClosed then
    Abort.fail(ReflectError.ClasspathClosed)
else
    val decoded: Either[ReflectError, Tree] =
        try Right(_bodyMemo.get())
        ...
    decoded match
        case Right(t) => Sync.defer(t)
        case Left(e)  => Abort.fail(e)
end if
```

The check uses `home.get().isClosed` rather than the raw `stateRef.unsafe.get() == State.Closed` form described in the PREP doc. This is a legitimate refactoring: `isClosed` is defined as `private[kyo] def isClosed(using AllowUnsafe): Boolean = stateRef.unsafe.get() == Classpath.State.Closed` in `Classpath.scala` and requires `AllowUnsafe` as a proof. The single-import `import AllowUnsafe.embrace.danger` covers both the `isClosed` call and the downstream `_bodyMemo.get()`. The helper improves readability without changing semantics. Both approaches are equivalent.

The outer `checkOpen` guard is retained at line 628 (`home.get().checkOpen.andThen:`), making this a double-guard (defense in depth) implementation.

---

## Check 2: Classpath.isClosed helper exposed correctly without leaking State enum

**PASS**

`isClosed` is declared as `private[kyo] def isClosed(using AllowUnsafe): Boolean` in `Classpath.scala`. Accessibility:

- `private[kyo]` means it is visible only within the `kyo` package tree. `Reflect.scala` is in package `kyo`, so the call `home.get().isClosed` compiles correctly.
- `Classpath.State` is declared `sealed private[reflect] trait State` / `private[reflect] object State` in `Classpath.scala`. The `kyo.internal.reflect.query` package is the only scope where `State` cases are named directly; they do not appear in any public API surface.
- The `isClosed` method returns `Boolean`, not `Classpath.State`. The caller in `Reflect.scala` sees only the boolean result; the `State` enum is not observable by external code.

No `State` case is reachable from outside `kyo.internal.reflect.query`. The helper is correctly scoped.

---

## Check 3: Test 10 in TreeUnpicklerTest added; strictly asserts Result.Failure(ClasspathClosed) post-Scope-close

**PASS with one NOTE**

Test 10 is present at line 412-452 of `TreeUnpicklerTest.scala` (Phase 4 commit). The test:

1. Opens a classpath inside `Scope.run` using `openPlainClassCp`.
2. Finds the first `declarations` member of `PlainClass` with `TastyOrigin.bodyStart > 0`.
3. Escapes the symbol via `Kyo.lift(sym)` inside `Abort.run[ReflectError]`.
4. After `Scope.run` exits (classpath closed), calls `Abort.run[ReflectError](sym.body)`.
5. Asserts `Result.Failure(ReflectError.ClasspathClosed)`.
6. Rejects `Result.Failure(e)` for any other `e` with `fail(...)`.
7. Rejects `Result.Success(_)` with `fail(...)`.

This is structurally correct and strictly stronger than Test 7 (which accepted both `ClasspathClosed` and `NotImplemented`). No timing dependency: `Scope.run` is synchronous and the `Scope.ensure` finalizer that calls `InternalClasspath.close` runs before `Scope.run` returns.

**NOTE-1: Test name does not include the "Test 10:" prefix.**

The test string is `"sym.body after Scope close returns Abort.fail(ClasspathClosed)"` rather than `"Test 10: sym.body after Scope close returns Abort.fail(ClasspathClosed)"`. This is a minor inconsistency with Tests 1-9, which carry the "Test N:" prefix. The section comment `// ── Test 10: regression: sym.body after Scope close returns ClasspathClosed ──` is present, so the intent is clear. No functional impact; cosmetic only.

**NOTE-2: `pending` fallback on NotImplemented remains from the PREP concern.**

If `classSym.declarations.find` returns `None`, the test calls `pending` rather than `fail(...)`. The PREP doc flagged this as CONCERN-1 (too lenient) because `AstUnpicklerTest` Test 18 proves `PlainClass.tasty` always has at least one method with `bodyStart > 0`. The `pending` path is never reached in practice, but it weakens the test in theory. This is a low-priority NOTE (see Phase 8 audit).

---

## Check 4: Test count - 245 JVM (244 + 1); JS/Native same delta

**WARN-1: Test count diverges from plan expectation.**

Source-code test count at HEAD (Phase 4 commit, before Phase 6 changes):

| File | Count |
|------|-------|
| QueryApiTest | 38 |
| AstUnpicklerTest | 20 |
| ByteViewTest | 14 |
| TypeUnpicklerTest | 13 |
| SnapshotRoundTripTest | 13 |
| TreeUnpicklerTest | 10 |
| TastyHeaderTest | 9 |
| SubtypeTest | 9 |
| FlagsTest | 9 |
| JavaSignaturesTest | 8 |
| TypeOpsTest | 6 |
| SymbolResolutionTest | 6 |
| NameUnpicklerTest | 6 |
| InternerTest | 6 |
| TypeArenaTest | 5 |
| ModuleInfoTest | 5 |
| CommentsUnpicklerTest | 5 |
| Utf8Test | 4 |
| PositionsUnpicklerTest | 4 |
| AttributeUnpicklerTest | 4 |
| DeclarationTableTest | 3 |
| ClasspathRefDedupTest | 2 |
| UnifiedModelTest | 1 |
| JavaSymbolTest | 1 |
| ClassfileReaderTest | 1 |
| **TOTAL** | **202** |

The plan expected 245 (per CONCERN-4 in the PREP doc, which corrected the plan's stated 246). The actual source count is 202, which is 43 below the plan's corrected expectation.

Root cause of the discrepancy: the plan stated v2 final test count as 280. The actual pre-Phase-1 source count was 232 (verified by counting at commit `3c39188c1`). The plan overstated the baseline by 48 tests, likely because `sbt test` reports a higher number than `grep "in run {" | wc -l` (for example, if some test suites expand tests via `forAll` or nested `describe` blocks, or if the plan's count included companion tests from other subprojects accidentally). The Phase 4 addition of 1 test (TreeUnpicklerTest: 9 to 10) is correct. Phase 2's removal counts also check out at the source level (SymbolResolutionTest: 8 before Phase 1, 6 after Phase 2 = 2 removed, plan said 1; the plan was off by 1 here too).

The `sbt test` run count has not been verified here (no sbt run was performed). The plan's numeric targets are unreliable as written; the Phase 8 audit should establish the true `sbt test` count and correct the plan tables accordingly.

JS/Native delta: Phase 4 adds exactly 1 test that uses `run {}` (the test harness's `Async`-based runner). This test is in `shared/src/test/scala/kyo/TreeUnpicklerTest.scala` and runs on all three platforms. The JS/Native delta is therefore also +1.

---

## Check 5: No em-dashes, no Frame.internal, no new AllowUnsafe sites

**PASS**

- Em-dashes (`—`): `grep -rn " — \|—"` across all modified `kyo-reflect` source and test files returns no hits.
- `Frame.internal`: `grep -rn "Frame\.internal"` across all `kyo-reflect/shared/src/main/scala/` returns no hits.
- New AllowUnsafe sites: Phase 4 added one `AllowUnsafe` site (the `isClosed` call at line 636-640 of the Phase 4 commit). This site has the required `// Unsafe:` comment block directly above `import AllowUnsafe.embrace.danger`. No other new AllowUnsafe sites were introduced by Phase 4. The one site at line ~907 (`assignHomes`) uses an inline comment `// We use AllowUnsafe to read allSymbols...` rather than the `// Unsafe:` prefix convention; this pre-dates Phase 4 and is not a Phase 4 regression.

---

## Check 6: Defense-in-depth pattern documented in scaladoc

**WARN-2: Defense-in-depth pattern is documented in inline comments but not in the public-facing scaladoc for `body`.**

The `body` accessor's scaladoc lists `ReflectError.ClasspathClosed` in the `Fails with:` section, which is correct. However, the scaladoc does not explain that two guards are present (`checkOpen` at the top-level and `isClosed` before the Memo decode) or that the inner guard is the defense-in-depth against in-flight decode races.

The inline comments do document the rationale:
- `checkOpen.andThen:` has no comment but `Classpath.checkOpen`'s scaladoc says "defense-in-depth" (for Building state).
- The `isClosed` block has `// Unsafe: Reading classpath state under AllowUnsafe to detect closed classpath before body decode; state transitions are monotonic (Closed is terminal) so a stale read returns a conservative result.`

Scaladoc-visible documentation of the double-guard design is absent. Callers reading only the scaladoc will know that `ClasspathClosed` is possible but not why there are two checks or what the race-window guarantee is. This should be added in Phase 8 (final audit).

The task criterion asks for this to be documented in scaladoc; it is not, making this a WARN.

---

## Summary

| Severity | Count | Items |
|----------|-------|-------|
| BLOCKER | 0 | |
| WARN | 2 | WARN-1 (test count 202 vs plan's 245), WARN-2 (defense-in-depth not in scaladoc) |
| NOTE | 2 | NOTE-1 (Test 10 name missing "Test 10:" prefix), NOTE-2 (pending fallback) |

**WARN-1** is the most actionable item: the plan's test count targets need to be corrected in the Phase 8 audit to reflect the actual `sbt test` count rather than the source-grep count or the original (incorrect) plan estimate.

**WARN-2** is purely a documentation gap; the runtime behavior is correct.

Neither WARN blocks Phase 5-8 execution.
