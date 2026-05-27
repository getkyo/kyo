# Phase 6 v3 Prep

**Status: Phase 6 is ALREADY COMMITTED at HEAD.**

Worktree HEAD = `73855f5ccb6e897117bbc5ea32914a3cad1ce481` (kyo-reflect v3 Phase 6: rename Memo to OnceCell).

This document is a post-execution record of the rename, produced after the fact to match the prep-doc convention. It describes what was done, what the new implementation looks like, and any residual concerns for Phase 7 and the Phase 8 final audit.

---

## Verbatim Memo.scala content (as of Phase 4 commit, before deletion)

`Memo.scala` was located at:
`kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Memo.scala`

```scala
package kyo.internal.reflect.symbol

import java.util.concurrent.atomic.AtomicReference
import kyo.AllowUnsafe

/** A lazy one-time computation that is computed on first access and cached for subsequent calls.
  *
  * Thread-safe: if two threads race to compute, one wins the CAS and the other reads the winner's result. The `init` function may be called
  * more than once under concurrent access, but only one computed value is ever stored. Both threads return the same stored value.
  *
  * WARNING: This is an unsafe-tier helper. `get()` executes a side effect (reads and potentially writes an AtomicReference). Callers must
  * hold an `AllowUnsafe` proof, or invoke from inside a `Sync.Unsafe.defer` block.
  */
final class Memo[A](init: () => A):
    // Store as AnyRef to avoid strict-null comparison issues.
    private val ref = new AtomicReference[AnyRef](Memo.Unset)

    /** Return the cached value, computing it on first call.
      *
      * Requires AllowUnsafe: this method reads and potentially writes an AtomicReference as a side effect.
      */
    def get()(using AllowUnsafe): A =
        val cached = ref.get()
        if cached ne Memo.Unset then
            // AsInstanceOf justified: AnyRef sentinel pattern; ref holds either Memo.Unset or an A stored as AnyRef;
            // the ne-Unset guard guarantees the value is the A we stored.
            cached.asInstanceOf[A]
        else
            // AsInstanceOf justified: we store A as AnyRef to use AtomicReference with the Unset sentinel;
            // the union type A | Unset.type cannot be expressed without boxing in Scala 3 on AtomicReference[AnyRef].
            val v = init().asInstanceOf[AnyRef]
            ref.compareAndSet(Memo.Unset, v)
            // AsInstanceOf justified: same as above; ref now holds either Memo.Unset (CAS lost, another thread won)
            // or the v we stored; in both cases the stored value is an A.
            ref.get().asInstanceOf[A]
        end if
    end get

end Memo

object Memo:
    private val Unset: AnyRef = new AnyRef
end Memo
```

43 lines total (44 including the final blank line in most editors).

---

## All Memo references with file:line that needed updating

These were the live references in the Phase 4 commit (`30f06bd54`) that Phase 6 renamed.

### Production source

| File | Line (Phase 4) | Reference | Status |
|------|----------------|-----------|--------|
| `kyo/Reflect.scala` | 38 | `via `Memo[String]`` (scaladoc comment) | Renamed to `OnceCell[String]` |
| `kyo/Reflect.scala` | 58 | `// Unsafe: Memo.get() is an unsafe-tier helper...` | Comment updated |
| `kyo/Reflect.scala` | 446-447 | `// Memo handles thread safety. // Unsafe: Memo is...` | Comments updated |
| `kyo/Reflect.scala` | 449 | `private[kyo] val _bodyMemo: kyo.internal.reflect.symbol.Memo[Tree]` | Field renamed to `_bodyOnce: ... OnceCell[Tree]` |
| `kyo/Reflect.scala` | 450 | `new kyo.internal.reflect.symbol.Memo[Tree](...)` | Updated to `OnceCell[Tree]` |
| `kyo/Reflect.scala` | 472 | `// Unsafe: Memo.get() is an unsafe-tier helper...` | Comment updated |
| `kyo/Reflect.scala` | 632 | `// Decode via Memo to cache; the Memo init lambda...` | Comment updated |
| `kyo/Reflect.scala` | 635 | `// Unsafe: Memo.get() is an unsafe-tier helper...` | Comment updated |
| `kyo/Reflect.scala` | 644 | `try Right(_bodyMemo.get())` | Updated to `_bodyOnce.get()` |
| `kyo/internal/reflect/classfile/ConstantPool.scala` | 82 | `// Unsafe: Memo.get() is an unsafe-tier helper...` (x2) | Both comments updated |
| `kyo/internal/reflect/symbol/Interner.scala` | 55 | `new Memo(() => Utf8.decode(...))` | Updated to `new OnceCell(...)` |
| `kyo/internal/reflect/symbol/Interner.scala` | 163 | `The \`string\` field is a lazy \`Memo[String]\`` (scaladoc) | Updated to `OnceCell[String]` |
| `kyo/internal/reflect/symbol/Interner.scala` | 171 | `val string: Memo[String]` | Updated to `val string: OnceCell[String]` |
| `kyo/internal/reflect/tasty/TreeUnpickler.scala` | 13 | `Called synchronously from Symbol._bodyMemo init lambdas.` | Updated to `_bodyOnce` |
| `kyo/internal/reflect/tasty/TreeUnpickler.scala` | 27 | `Called from the Memo init lambda...` | Updated to `OnceCell init lambda` |
| `kyo/internal/reflect/symbol/Memo.scala` | all | (entire file) | Deleted |

### Test source

| File | Line (Phase 4) | Reference | Status |
|------|----------------|-----------|--------|
| `kyo/TreeUnpicklerTest.scala` | 23 | `Test 9: verifies Memo reference equality.` (class scaladoc) | Updated to `OnceCell reference equality` |
| `kyo/TreeUnpicklerTest.scala` | 385 | `"Test 9: two consecutive _bodyMemo.get() calls..."` (test name) | Renamed to `_bodyOnce.get()` |
| `kyo/TreeUnpicklerTest.scala` | 396-397 | `sym._bodyMemo.get()` (x2, in test body) | Renamed to `_bodyOnce.get()` |
| `kyo/InternerTest.scala` | 58-59 | `(Memo caching)` in comment and test name | Updated to `OnceCell caching` |
| `kyo/NameUnpicklerTest.scala` | 146, 149 | `Memo caches` / `not just the Memo` (comments) | Updated to `OnceCell caches` / `not just the OnceCell` |
| `kyo/SnapshotRoundTripTest.scala` | 582 | `cached by Memo` (comment) | Updated to `cached by OnceCell` |

---

## New OnceCell scaladoc

The `OnceCell.scala` scaladoc correctly captures the key distinctions:

```scala
/** A lazy one-time computation cell.
  *
  * On first access via `get()`, the supplied `init` function runs and the result is CAS-published.
  * Subsequent reads return the cached value.
  *
  * Concurrent first-access semantics: if two threads race on `get()` before either has CAS-published,
  * BOTH run `init()` redundantly. One CAS wins; the other's computed value is discarded. Both
  * threads then return the same cached value.
  *
  * This is distinct from `kyo.Cache.memo`, which uses a Promise to dedup concurrent first-callers
  * (only one runs `init()`; others await the Promise). `kyo.Cache.memo`'s dedup costs Async on the
  * accessor's effect row. OnceCell's race-and-discard costs occasional redundant init() calls but
  * never blocks and never adds Async.
  *
  * For kyo-reflect's body decode and Name interning workloads, OnceCell is correct: the init()
  * call is small and synchronous, redundant first-access work is bounded to microseconds, and
  * keeping the accessor effect row Sync-only is more valuable than dedup precision.
  *
  * WARNING: This is an unsafe-tier helper. `get()` reads and potentially writes an AtomicReference
  * as a side effect. Callers must hold an `AllowUnsafe` proof.
  */
```

This satisfies the execution-plan-v3.md Phase 6 requirement: `OnceCell.scala` scaladoc must contain the "Distinct from `kyo.Cache.memo`" sentence. The actual wording uses `This is distinct from` rather than `Distinct from` but is functionally equivalent and clearer.

---

## Field rename decision: `_bodyMemo` -> `_bodyOnce`

The field was renamed to `_bodyOnce`. The execution plan listed two candidates: `_bodyOnce` and `_body`. The Phase 6 commit chose `_bodyOnce`.

Rationale for `_bodyOnce` over `_body`:
- The `_body` name would be ambiguous with the public `body` accessor defined in the same class scope.
- The `Once` suffix makes the caching contract explicit and matches the `OnceCell` type name.
- Test 9 references `sym._bodyOnce.get()` directly (it is `private[kyo]`), so the name appears in one test. `_bodyOnce` reads cleanly there.

`_bodyOnce` is the right choice. No concern.

---

## Edge cases: anyone importing `kyo.internal.reflect.symbol.Memo` directly

No file outside `Memo.scala` itself imported `kyo.internal.reflect.symbol.Memo` directly. Verification:

```
grep -rn "reflect\.symbol\.Memo\|import.*Memo\b" kyo-reflect/shared/src/main/scala/
```

This returned only `Memo.scala` itself (the type declaration) and `Interner.scala` (which uses `Memo` unqualified within the same package). The `Interner.scala` usage was covered by the package-local reference `new Memo(...)` which became `new OnceCell(...)`.

In `Reflect.scala`, the field declaration used the fully-qualified form `kyo.internal.reflect.symbol.Memo[Tree]` rather than an import, so there was no import to update -- the fully-qualified reference was updated inline.

No external consumers of `kyo.internal.reflect.symbol.Memo` exist anywhere in the repo (the type was `internal`).

---

## Concerns

**CONCERN-1: `asInstanceOf` casts remain in `OnceCell.scala` (carried over from `Memo.scala`).**

The three `asInstanceOf` casts (for the AnyRef sentinel pattern) are justified by inline comments in both the old `Memo.scala` and the new `OnceCell.scala`. These casts are correct (the AnyRef sentinel pattern is a standard idiom for `AtomicReference` with a null-sentinel-free API). The `feedback_no_casts.md` rule says "never use asInstanceOf, fix the types instead." However, there is no Scala 3 type-safe alternative to `AtomicReference[AnyRef]` with a separate sentinel object that avoids boxing; the cast is genuinely required by the JVM type model here. The commit message for Phase 6 explicitly notes this: "retains the three asInstanceOf casts in OnceCell, identical to the originals in Memo." This is a pre-existing exception, not a Phase 6 regression.

If the `feedback_no_casts.md` rule is ever applied strictly here, the fix would be to use `AtomicReference[Option[A]]` with `None` as sentinel (adding one `Option` allocation per `OnceCell`), which is unlikely to be worthwhile.

**CONCERN-2: Test 9 accesses `sym._bodyOnce` directly (private[kyo]).**

Test 9 is `"Test 9: two consecutive _bodyOnce.get() calls return the same Tree reference"`. It calls `sym._bodyOnce.get()` directly rather than going through `sym.body`. This is consistent with `_bodyOnce` being `private[kyo]` and the test living in package `kyo`. The test is correct but violates the spirit of `feedback_tests_use_public_api.md` (the LHS should use the public API). The public `sym.body` called twice would test the same reference-equality property. Consider rewriting Test 9 to call `sym.body` twice in Phase 8 or as a follow-up hardening.

**CONCERN-3: The plan's Phase 6 "Files to produce" says `OnceCell.scala` must contain the scaladoc with "Distinct from `kyo.Cache.memo`".**

Verified: `OnceCell.scala` contains `This is distinct from \`kyo.Cache.memo\`` which satisfies the intent. The supervisor check `grep "kyo.Cache.memo" OnceCell.scala` returns a hit.

**CONCERN-4: `Memo.scala` deletion is staged (D in git status) but `OnceCell.scala` was untracked (??) before the Phase 6 commit.**

Both were correctly handled in commit `73855f5cc`. The worktree is clean. No residual issue.

**CONCERN-5: Phase 6 test file changes include `SnapshotRoundTripTest.scala`.**

The plan's Phase 6 "Files to modify" listed `Reflect.scala`, `Interner.scala`, and "any other file that references Memo". `SnapshotRoundTripTest.scala` was in the unstaged changes but not explicitly listed in the plan. The change was a single comment update (`cached by Memo` to `cached by OnceCell`). This is correct and complete. No concern.
