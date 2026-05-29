# Phase 02f Prep: Delegate Classpath.open one-arg overload

Plan reference: 05-plan.md §580-641 (A2, INV-025).

---

## 1. Both open signatures captured

File: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` lines 905-919.

```scala
// One-arg (CURRENT -- delegates to openImpl, not to the canonical public overload)
def open(roots: Seq[String])(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
    openImpl(roots, strict = false)

// Two-arg canonical (CURRENT -- delegates to openImpl directly)
def open(roots: Seq[String], strict: Boolean)(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
    openImpl(roots, strict)
```

The BEFORE state already matches the plan's BEFORE block (plan lines 594-597). The one-arg body calls `openImpl(roots, strict = false)` rather than delegating to the public overload `open(roots, strict = false)`.

AFTER body for the one-arg overload (plan lines 600-601):

```scala
def open(roots: Seq[String])(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
    open(roots, strict = false)
```

The two-arg body is unchanged. The delegation form is `open(roots, strict = false)` -- named argument, not positional `open(roots, false)`, not a default-param shim.

---

## 2. openCached signatures captured

File: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` lines 931-932.

```scala
def openCached(roots: Seq[String], cacheDir: String)(using Frame): Classpath < (Sync & Async & Scope & Abort[TastyError]) =
    openCachedImpl(roots, cacheDir)
```

`openCached` has exactly one public overload (no strict parameter, no pair). Q-007 confirms: "it has no strict parameter and no overload pair, so no canonical-delegation question arises there." Phase 02f does NOT touch `openCached`.

---

## 3. Delegation form verification

Proposed AFTER body: `open(roots, strict = false)` -- explicit named argument.

This satisfies INV-025: "The no-strict Classpath.open(roots) overload delegates by name to the canonical Classpath.open(roots, strict) with `strict = false` explicit; no default-parameter shim."

Positional form `open(roots, false)` is NOT used. Default-param shim is NOT introduced. Both correct.

---

## 4. Caller cascade

Command run:

```
rg -n 'Tasty\.Classpath\.open\b|Tasty\.openCached\b|Classpath\.open\b' \
  /Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar
```

External callsites (non-Tasty.scala, non-audit-doc):

| File | Line | Call form |
|------|------|-----------|
| `kyo-tasty-bench/jvm/.../TastyQueryCompareBench.scala` | 141 | `Tasty.Classpath.open(rootStrings)` |
| `kyo-tasty-bench/jvm/.../ColdLoadBench.scala` | 91 | `Tasty.Classpath.open(Seq(root))` |
| `kyo-tasty-bench/jvm/.../ColdLoadFullBench.scala` | 150 | `Tasty.Classpath.open(allRoots)` |
| `kyo-tasty/README.md` | 15 | `Tasty.Classpath.open(roots)` (doc) |

Confirmed 3 bench callsites, all use one-arg form without `strict`. No callers pass an explicit `strict` argument. The README reference is documentation, not compiled code. No caller cascade impact from this change: the one-arg signature is unchanged; only its body changes from `openImpl(...)` to `open(..., strict = false)`.

---

## 5. Concerns

**Test file:** The plan (line 617) places the new test in the existing `kyo-tasty/shared/src/test/scala/kyo/TastyTest.scala` -- not a new file. Consistent with `feedback_test_placement`.

**openCached scope:** Phase 02f explicitly excludes `openCached`. Q-007 confirms `openCached` has no overload pair and needs no delegation fix.

**Current state vs BEFORE:** The current source already has scaladoc on both overloads (lines 895-919) added in a prior phase (phase 01). The plan's BEFORE block shows no scaladoc, but the AFTER block adds it. The scaladoc is already present, so the only real change is the one-arg body: `openImpl(roots, strict = false)` -> `open(roots, strict = false)`. The scaladoc text may need reconciliation: current line 903 comment says "One-arg variant: delegates to the canonical two-arg form with `strict = false`" which is already correct in intent but the body still calls `openImpl`. No risk of doc/body mismatch after the fix.

**No default-param risk:** `openImpl` is `private[kyo]`; the public two-arg overload is unmodified. No default added anywhere.

---

## Self-check verdict

PASS. All five steps complete. Both open signatures captured with exact line anchors. openCached confirmed single-overload, out of scope. Delegation form is `open(roots, strict = false)` named-arg, not positional, not default-param. Caller cascade: 3 bench callsites, all one-arg, no cascade impact. Concerns: test goes in existing TastyTest.scala; openCached untouched; current scaladoc already accurate, body is the only change.
