# E. kyo-kernel/shared/src/main/scala/kyo/kernel/Loop.scala

## Diff size against origin/main

| | added | removed |
|---|---|---|
| before | 362 | 218 |
| after | 322 | 176 |

`git --no-pager diff --numstat origin/main -- kyo-kernel/shared/src/main/scala/kyo/kernel/Loop.scala`

Hunk count at `-U0`: 78. Every one is listed below.

## What was reverted to main's text

- **Imports.** Back to main's block verbatim (`kyo.*`, `kyo.kernel.internal.*`, the three
  `scala.annotation` imports, `scala.util.NotGiven`), with one added line for
  `kyo.kernel.Arrow.Step`, which main has no counterpart for. The branch's explicit
  `kyo.Frame` / `kyo.Maybe` / four `kyo.kernel.internal.X` lines are gone, and so is
  `import kyo.kernel.internal.site`, which was unused (the wildcard covers it anyway).
- **`@nowarn("msg=anonymous")` placement.** Back to main's position, on the inner
  `loop` def, in the four `apply`s, `foreach`, `forever` and `whileTrue`. It is gone from
  the five `indexed`s and from `repeat`, which no longer allocate an anonymous class.
- **Type parameter name.** `continue[A, B, o]` (lowercase `o`) is main's name; the branch
  had renamed it to `O`. Restored. See "main's form kept although worse" below.
- **Scaladoc.** Restored verbatim on seven members whose meaning did not change and where
  the branch carried a wrong copy-pasted or missing doc: the three- and four-value
  `continue` (the latter had none at all), `done2` / `done3` / `done4` (the
  "for a two/three/four-state loop" wording), the four-value `apply`, and the three- and
  four-value `indexed`. No doc text was written by me; every doc line in the file is now
  main's.
- **Formatting.** Removed seven stray blank lines (after `=` in `continue`, `apply`,
  `indexed`, `foreach`, `forever`, `whileTrue`, and after `else` in `apply` and `repeat`);
  joined the four-value `continue` signature and the `foreach` fall-through case back to
  main's single-line shape.

Signatures whose using clause main split across lines only because of the second
(`safepoint: Safepoint`) parameter are written on one line, since one parameter fits inside
`maxColumn = 140`. Keeping main's line breaks there would save five diff lines across the
file at the cost of five four-line signatures for a single implicit parameter. The
three- and four-value `apply` / `indexed` keep main's multi-line layout unchanged.

## main's form kept although it looks worse

- `continue[A, B, o]`: main names the outcome type parameter lowercase `o` while every
  sibling overload uses `O`. Restored to `o`, per the rule that main's names win.
- `import scala.util.NotGiven`: unused in main's Loop.scala and still unused here. Kept so
  the import block is byte-identical to main. The build has no `-Wunused`, so it is silent
  under `-Werror`. Removing it would add a deletion hunk that is not a design divergence.

## Remaining hunks

Category key: (a) design divergence with a comment at its site, (b) doc changed because
the member's meaning changed, (c) forced by (a). There are no (b) hunks: every doc line in
the file is main's.

| # | lines (current file) | what it is | cat |
|---|---|---|---|
| 1 | 4 | `import kyo.kernel.Arrow.Step`, needed for the `Step` nodes the loops build | c |
| 2 | 23-26 | file-wide divergence comment: Pending representation, no Safepoint evidence, Arrow-node deferral | a |
| 3 | 29 | divergence comment for the `Continue` classes | a |
| 4 | 36 | `Debugger.onAlloc(this)` in `Continue` | a |
| 5 | 38-39 | `toString` on `Continue` and the `end Continue` marker the extra line forces | a, c |
| 6 | 49 | `Debugger.onAlloc(this)` in `Continue2` | a |
| 7 | 52-53 | `toString` on `Continue2` and its `end Continue2` marker | a, c |
| 8 | 65 | `Debugger.onAlloc(this)` in `Continue3` | a |
| 9 | 69 | `toString` on `Continue3` | a |
| 10 | 84 | `Debugger.onAlloc(this)` in `Continue4` | a |
| 11 | 89 | `toString` on `Continue4` | a |
| 12 | 92 | divergence comment for the `Outcome` variance | a |
| 13 | 100 | `Outcome[A, +O]` covariant | a |
| 14 | 111 | `Outcome2[A, B, +O]` covariant | a |
| 15 | 124 | `Outcome3[A, B, C, +O]` covariant | a |
| 16 | 139-148 | `Outcome4[A, B, C, D, +O]` covariant, plus the `Done` wrapper, `unnest` and their comment | a |
| 17 | 154 | divergence comment for `continue` answering as a computation | a |
| 18 | 163 | `continue[A]` answers `Outcome[Unit, A] < Any` | a |
| 19 | 171-176 | `continue[A, O, S]`: `< S` answer, the cast, the `v0` hoist (so `toString` cannot re-run the argument), `end continue` | a, c |
| 20 | 186-193 | same for the two-value `continue` | a, c |
| 21 | 205-214 | same for the three-value `continue` | a, c |
| 22 | 228-242 | same for the four-value `continue`, plus the `done` divergence comment | a, c |
| 23 | 245 | `done[A]` answers `< Any` through a cast | a |
| 24 | 253-256 | `done[A, O]` wraps a `Continue` answer in `Done`, nests any other | a |
| 25 | 264-267 | same for `done2` | a |
| 26 | 275-278 | same for `done3` | a |
| 27 | 286-289 | same for `done4` | a |
| 28 | 303 | one-value `apply` signature: no `Safepoint ?=>` body, no safepoint evidence | a |
| 29 | 305 | its `loop` takes the cached `step` and no `Safepoint` | a |
| 30 | 308-328 | its body: `Pending` case, `Step` node, `Effect.defer`, `Done` case | a |
| 31 | 330-331 | `asInstanceOf[O < S]` and `loop(Maybe.empty, ...)` | c |
| 32 | 348 | two-value `apply` signature | a |
| 33 | 350 | its `loop` signature | a |
| 34 | 353-373 | its body | a |
| 35 | 375-376 | its tail cast and seeded call | c |
| 36 | 396-397 | three-value `apply` signature (run type, using clause) | a |
| 37 | 399 | its `loop` signature | a |
| 38 | 402-422 | its body | a |
| 39 | 424-425 | its tail cast and seeded call | c |
| 40 | 447-448 | four-value `apply` signature | a |
| 41 | 450 | its `loop` signature | a |
| 42 | 453-473 | its body | a |
| 43 | 475-476 | its tail cast and seeded call | c |
| 44 | 479-480 | divergence comment for `indexed` | a |
| 45 | 492-495 | no-state `indexed` signature, the `suspended` helper, `loop` without Safepoint; main's `@nowarn` dropped with the anonymous class | a, c |
| 46 | 499-502 | its body: `Pending` test and `Done` case | a |
| 47 | 504 | its tail cast | c |
| 48 | 520-523 | one-state `indexed`: same | a, c |
| 49 | 527-530 | its body | a |
| 50 | 532 | its tail cast | c |
| 51 | 551-555 | two-state `indexed`: same | a, c |
| 52 | 559-562 | its body | a |
| 53 | 564 | its tail cast | c |
| 54 | 585-589 | three-state `indexed`: same | a, c |
| 55 | 593-596 | its body | a |
| 56 | 598 | its tail cast | c |
| 57 | 621-625 | four-state `indexed`: same | a, c |
| 58 | 629-632 | its body | a |
| 59 | 634 | its tail cast | c |
| 60 | 648 | `foreach` signature | a |
| 61 | 650 | its `loop` signature | a |
| 62 | 653-675 | its body, `Done` case and seeded call | a |
| 63 | 678-679 | divergence comment for `repeat` | a |
| 64 | 692-696 | `repeat` signature, `suspended` helper, `i >= n` bound, `@nowarn` dropped | a, c |
| 65 | 698 | `val v: Any < S = run`, the body evaluated once per iteration | a |
| 66 | 700-701 | its `Pending` test | a |
| 67 | 703-704 | `loop(i + 1)` and the `end match` marker | a, c |
| 68 | 707 | `loop(0)` | c |
| 69 | 720 | `forever` signature | a |
| 70 | 722 | its `loop` signature | a |
| 71 | 724-741 | its body | a |
| 72 | 743 | `loop(step, run)` | c |
| 73 | 745 | `loop(Maybe.empty, ())` | c |
| 74 | 757 | `whileTrue` signature | a |
| 75 | 759 | its `loop` signature | a |
| 76 | 763-780 | its body | a |
| 77 | 782 | `loop(step, run)` | c |
| 78 | 786 | `loop(Maybe.empty, ())` | c |

## Comment placement

The four combinator families that suspend (`apply`, `foreach`, `forever`, `whileTrue`)
share one divergence: they loop over `Pending` and defer through an `Arrow` node instead of
rebuilding a suspension, and they take no Safepoint evidence. That is stated once in the
comment above `object Loop` rather than repeated at eleven signatures. Divergences that are
local to one member (`Continue` debugging, `Outcome` variance, `Done` / `unnest`,
`continue`, `done`, `indexed`, `repeat`) each carry their own comment at their site.

## Not verified

- **Nothing was compiled or formatted** (per the brief: no sbt). Two changes are the ones
  to check first if the module does not compile:
  1. The wildcard imports. I checked by hand that no identifier the file uses is bound by
     two wildcards at once and that nothing shadows `Effect`, `Arrow`, `Step`, `Pending`,
     `Safepoint`, `Debugger`, `Nested`, `Frame` or `Maybe`. `kyo/kernel.scala` does define
     `val Loop = kernel.Loop` in package `kyo`; the enclosing `object Loop` outranks it
     (same compilation unit, precedence 1, against a wildcard import at precedence 3), so
     `Loop.continue(...)` still names the object. Every other kernel source in this branch
     uses explicit imports, so this is the one file with main's wildcard style.
  2. Moving `@nowarn("msg=anonymous")` from the method to the inner `loop` def. The build
     runs `-Werror` on Scala 3, so a warning that escapes suppression is a hard failure.
     Main compiles under the same flags with the annotation in that position and the
     anonymous class inside `loop`; here the anonymous class sits one level deeper, inside
     the `step.getOrElse { ... }` lambda, still within the annotated def's span.
- No rename was skipped for crossing a file boundary. The only public names that changed
  are the `continue[A, B, o]` type parameter (restored to main's) and nothing else;
  `Done`, `unnest`, `Outcome*`, `Continue*` keep the branch's names, and `unnest` is called
  from other kernel sources.
