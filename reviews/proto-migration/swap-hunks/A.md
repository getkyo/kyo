# Swap-hunk minimization, batch A

Reference: `origin/main` (bd7f20b146). Diff command for every count below:
`git --no-pager diff --numstat origin/main -- <path>`.

Hunk categories:

- **(a)** design divergence, carrying a `// Diverges from main:` or `// Not on main:` comment at its site
- **(b)** doc text updated because the member's meaning changed
- **(c)** change forced by (a), e.g. an import that exists only because of a designed member

| File | Before (add/del) | After (add/del) |
|---|---|---|
| `kyo/kernel.scala` | 2 / 0 | 2 / 0 |
| `kyo/kernel/internal/package.scala` | 24 / 6 | 23 / 6 |
| `kyo/kernel/internal/KyoInternal.scala` | 8 / 75 | 8 / 75 |
| `kyo/kernel/internal/Context.scala` | 36 / 48 | 35 / 47 |
| `kyo/kernel/internal/CanLift.scala` | 42 / 21 | 35 / 12 |
| `kyo/kernel/internal/Implicits.scala` | 72 / 0 | 72 / 0 |

---

## 1. `kyo-kernel/shared/src/main/scala/kyo/kernel.scala` (2 / 0)

| Lines | What | Cat | Design item |
|---|---|---|---|
| 7-8 | `export kernel.Arrow` plus its `// Not on main:` comment | a | `Arrow` is a public type; the continuation is first class |

No other hunk. Nothing was changed in this file.

## 2. `kyo-kernel/shared/src/main/scala/kyo/kernel/internal/package.scala` (23 / 6)

| Lines | What | Cat | Design item |
|---|---|---|---|
| 3-4 | `import kyo.Frame`, `import kyo.kernel.Arrow` replace `import kyo.kernel.ArrowEffect` | c | Frame/Arrow are needed by `short`/`site`; ArrowEffect only served the removed `EX` alias |
| 8-10 | `// Diverges from main:` comment covering both budgets | a | fixed budgets |
| 11 | `maxStackDepth = 512` instead of `kyo.internal.Platform.maxStackDepth` | a | `maxStackDepth = 512` |
| 12 | `maxTraceFrames = 64` instead of `16` | a | `maxTraceFrames = 64` |
| 14 | comment marking the removal of the erased aliases `IX`, `OX`, `EX` | a | the erased aliases live in `Eval` with `VX`/`CX` |
| 16-29 | `short` and `site` rendering helpers | a | `// Not on main:` node and arrow `toString` rendering |

Changed in this pass: the two budget defs were put back adjacent and `=`-aligned exactly as main
has them (they had been split by a blank line and a second comment block), and the two divergence
comments were merged into the one block that sits directly above them. Main's original
platform-specific comment is kept verbatim above it.

## 3. `kyo-kernel/shared/src/main/scala/kyo/kernel/internal/KyoInternal.scala` (8 / 75)

| Lines | What | Cat | Design item |
|---|---|---|---|
| 3 | only `import kyo.Frame` survives of main's five imports | c | the suspension ADT that used `<`, `Const`, `Tag`, `ArrowEffect` is gone |
| 5-15 | class scaladoc rewritten | b | `Kyo` now means the supertype of `Pending` and `Arrow`, not the suspension encoding |
| 16-21 | `trait Kyo[+A, -S]` with `def frame`, replacing `Kyo`/`Nested`/`KyoSuspend`/`KyoContinue`/`Defer`/`KyoDefer` | a | `Kyo` as the supertype of `Pending` and `Arrow` with `frame`; the node family lives in `PendingInternal` |

The scaladoc first line and both `@tparam` blocks are main's text verbatim and do not appear in the
diff. Nothing else in this file is restorable: every removed member is a member the design deletes.

## 4. `kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Context.scala` (35 / 47)

| Lines | What | Cat | Design item |
|---|---|---|---|
| 3, 7 | `import kyo.Maybe` and `import scala.annotation.tailrec` added; main's `import Context.internal.*` gone | c | `get` returns `Maybe`, the walk is `@tailrec`, and `NoninheritableFlag` no longer exists |
| 12-13 | doc sentence describing region order and shadowing | b | the storage model changed from a map to an ordered list |
| 15-17 | `// Diverges from main:` comment | a | region-ordered context |
| 18-34 | `sealed abstract private[kernel] class Context` with `bind` / `get` / `unbind` | a | `Context` as a region-ordered list with `bind`/`get`/`unbind` |
| 36-46 | companion with `Empty` and `Bound` | a | same |

Changed in this pass: `import kyo.kernel.ContextEffect` was put back to main's `import kyo.kernel.*`,
which drops that line from the diff. `import kyo.Tag`, `import kyo.bug` and the closing `end Context`
are main's lines and are now diff context.

## 5. `kyo-kernel/shared/src/main/scala/kyo/kernel/internal/CanLift.scala` (35 / 12)

| Lines | What | Cat | Design item |
|---|---|---|---|
| 41-46 | `// Diverges from main:` comment and `inline def checkSingleton` replacing main's `inline given derived` inside `CanLiftMacro` | a | only singletons reach the macro |
| 56-59 | the nested-effect `errorAndAbort`, and `'{ null.asInstanceOf[CanLift[A]] }` instead of `'{ CanLift.unsafe.bypass... }` | a | no `CanLift.unsafe.bypass` |
| 66-75 | `derived` / `derivedCaseObject` / `derivedSingleton` / `CanLift[Nothing]` replacing `export CanLiftMacro.derived` and `object unsafe` | a | plain givens plus a singleton macro check, no bypass |
| 78-94 | `object LiftMacro` with `abortCastUnitImpl` | a + c | main's `LiftMacro` was the lift; here only the issue-903 guidance remains. See the honest note below on its placement |

Changed in this pass, all of it non-design noise that is now gone from the diff:

- `import kyo.kernel.<` restored to main's `import kyo.<` (`kyo.<` is the alias declared in
  `kyo/kernel.scala`, so the type is identical).
- The blank line main has inside the `@implicitNotFound` message (after the `.flatten` example) was
  restored; it had been dropped, which showed up as a whitespace hunk in a user-facing error string.
- Definition order restored to main's: `CanLiftMacro` first, then `CanLift`.
- `object CanLiftMacro` visibility restored to main's (was `private[kernel]`).
- The macro implementation renamed back to main's `liftImpl` with main's `private[internal]`
  visibility (was `private[kernel] checkImpl`); its signature line and its `end liftImpl` marker are
  now byte-identical to main and are diff context.
- `inline given nothing: CanLift[Nothing]` made anonymous, as main writes it.
- The blank line before `end CanLift` removed, matching main.
- `abortCastUnitMacro` renamed to main's `abortCastUnitImpl`, and its `(using Quotes)` given main's
  named `(using quotes: Quotes)` form. Its single call site is in `Implicits.scala`, also in this
  batch, and was updated.

## 6. `kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Implicits.scala` (72 / 0)

Main has no file at this path, so every line is an addition. The counterpart text is the `lift`
section of main's `kyo/kernel/Pending.scala` and main's `internal/LiftMacro.scala`.

| Lines | What | Cat | Design item |
|---|---|---|---|
| 6-9 | `// Diverges from main:` comment | a | the lift is a plain implicit gated by `CanLift`, in a trait mixed into the `<` companion |
| 10, 72 | `trait Implicits:` / `end Implicits` | a | same |
| 12-26 | `lift` scaladoc | - | main's text verbatim |
| 27-32 | `lift` body: `erasedValue` match instead of `${ LiftMacro.liftMacro[A, S]('v) }`; main's `liftAnyVal` and `liftUnit` are folded into the match and so absent | a | no `LiftMacro.liftMacro` |
| 34 | `abortCastUnit` splicing `LiftMacro.abortCastUnitImpl` instead of a companion-private `abortCastUnitImpl` | c | the impl cannot live in a trait, so it moved to `LiftMacro` |
| 36-70 | `liftPureFunction1..6` with their scaladoc | - | main's text verbatim, including the `using inline flat: CanLift[B]` parameter names |

Changed in this pass: `import kyo.kernel.<` restored to `import kyo.<`, which is the form all three
of main's `internal/` files use; and the `abortCastUnitImpl` rename above.

---

## Honest notes: things left in the diff that are not design divergences

1. **`object LiftMacro` sits in `CanLift.scala`.** Main has it in its own
   `kyo-kernel/shared/src/main/scala/kyo/kernel/internal/LiftMacro.scala`, which this branch deletes
   (that file's own diff is 0 / 41). Recreating `LiftMacro.scala` with the branch's
   `abortCastUnitImpl` would keep main's file correspondence, cut ~17 added lines from
   `CanLift.scala`, and turn the 41-line deletion into roughly a 13 / 33 hunk: about 12 diff lines
   net. I did not do it because `LiftMacro.scala` is not one of my assigned files. Recommended for
   whoever owns that path.

2. **`Implicits.scala` exists at all.** The file split itself (moving the lift members out of
   `object <` in `Pending.scala` into a mixed-in trait) is a placement change rather than one of the
   listed design items. It is labeled, and the 72 added lines here are offset by removals in
   `Pending.scala`, which is not mine.

3. **`Context` is `private[kernel]`, main's is `private[kyo]`.** Restoring `private[kyo]` on the
   companion alone does not compile: `val empty: Context` would leak a `private[kernel]` class out of
   a `private[kyo]` object. Restoring it on both would widen visibility with no caller needing it
   (`Context` is referenced only from `internal/Eval.scala`). Kept as `private[kernel]`; it saves at
   most one diff line and the declaration line diverges anyway.

4. **`trait Kyo` is public; main's is `sealed abstract private[kernel] class Kyo ... extends
   Serializable`.** Left public because the public, exported `Arrow` extends it. Not tightened and
   `Serializable` not re-added: both would change what the code does, and neither changes the diff
   line count.

5. **`package.scala` keeps main's "each platform sets it in `kyo.internal.Platform`" comment above a
   hardcoded 512.** That sentence is main's text and is now inaccurate on its own; the divergence
   comment directly beneath it says so. Kept because removing it would add a diff hunk.

## Renames skipped because they cross files

None were needed. `checkSingleton` was deliberately not renamed (it has no main counterpart); it is
named in a doc comment at
`kyo-kernel/.claude/skills/kernel/bench-harness/fixtures-expansion/BareSingleton.scala:7`, which is
unaffected. After the two renames applied here, `grep` finds no reference anywhere in the repo to
`checkImpl`, `abortCastUnitMacro`, or `CanLift.unsafe`/`.bypass` outside the comments that describe
main's version.

## Not verified

No build was run: sbt was out of scope for this pass. The edits are rename-consistent and
grep-checked, but not compiled.
