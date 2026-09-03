# F: ArrowEffect.scala diff minimization vs origin/main

File: `kyo-kernel/shared/src/main/scala/kyo/kernel/ArrowEffect.scala`
Reference: `origin/main` (bd7f20b146)

## Diff line counts

`git --no-pager diff --numstat origin/main -- kyo-kernel/shared/src/main/scala/kyo/kernel/ArrowEffect.scala`

| | added | removed |
|---|---|---|
| before | 583 | 450 |
| after | 567 | 434 |

File length: 795 lines before, 795 after (main: 662).

The raw counts move little because they are dominated by two irreducible blocks: main's four
multi-effect `handle` overloads, `handleCatching` and `handlePartial` (about 300 removed lines,
all dropped by ruling) and the branch's added overloads and `With` family (about 400 added
lines). What changed is the shape of the hunks that touch members main also has: those now show
only the design change.

## What was restored to main's form

| item | before | after |
|---|---|---|
| imports | 16 explicit single-symbol imports | `import kyo.*` / `import kyo.kernel.internal.*` as on main, plus `scala.annotation.tailrec` |
| unused imports | `kyo.kernel.internal.Eval`, `kyo.kernel.Arrow.Transform` imported, never used | removed |
| `ContHandler` / `ContOpHandler` / `LoopHandler` | imported by name | written `Handler.ContHandler` etc. at the 7 use sites, matching the existing `Handler.answersLoopState` call, so no import line beyond main's two wildcards is needed |
| class type parameters | `ArrowEffect[-I[_], +O[_]]` | `ArrowEffect[-Input[_], +Output[_]]` (main's names; the scaladoc's `@tparam Input` / `@tparam Output` had been left pointing at nothing) |
| `suspend` / `suspendWith` first type parameter | `[C]` | `[A]` |
| `suspend` / `suspendWith` input parameter | `effectInput` | `funcionInput`, main's misspelling, adopted per the rule that fixing main is a separate change; the scaladoc `@param funcionInput` on both members already used the misspelled name, so this also removes a doc/code mismatch |
| `suspendWith` return type | `B < (E & S)` | `B < (S & E)` (same type; main's spelling) |
| `handleFirst` parameter list | `effectTag` marked `inline`, three-line first clause | `(effectTag: Tag[E], v: A < (E & S))(` on one line, `effectTag` not `inline`, exactly main's layout and modifiers |
| member order | `handleCont`, `handleContOperation`, `handleFirst`, `dispatchFirst`, `handleLoop`, `handleLoopState`, `With` family, `Mask` | `suspend`, `suspendWith`, `handleCont`, `handleFirst`, `dispatchFirst`, `handleLoop`, `handleLoopState`, then the members main does not have (`handleContOperation`, the `With` family, `Mask`) |
| overload order within each family | `done`, `recover`, short | short, `done`, `recover`, so the short overload lands where main's single `handle` / non-stateful `handleLoop` / stateful `handleLoop` sits |
| scaladoc | main's stateful-`handleLoop` doc sat on the short `handleLoopState`, main's non-stateful text on the short `handleLoop` | unchanged where already verbatim; main's `handle` paragraph is now on the short `handleCont` with only the false "no S2 type parameter" sentence rewritten |
| stray blank lines | 7 (after `=` in `suspend` and `suspendWith`, before `try` in both recover overloads, inside `handleFirst` twice, inside `dispatchFirst`, inside `Mask.apply`) | removed |

## Remaining hunks

Categories: (a) design divergence with a `// Diverges from main:` or `// Not on main:` comment at
its site; (b) doc text changed because the member's meaning changed; (c) change forced by (a).

| lines (current file) | what it is | category |
|---|---|---|
| 6 | `import scala.annotation.tailrec` added | c, `dispatchFirst` walks with a `@tailrec` loop |
| 48-50 | object-level `// Diverges from main:` naming the node/`Eval` representation | a, node representation |
| 70-74 | `suspend` builds `Pending.SuspendArrow` with `cont = Arrow.id` instead of `KyoSuspend` with an `apply` | a, node representation (comment at 48) |
| 95 | `suspendWith` clause loses `Safepoint ?=>` | a, ruled removal of `Safepoint` context functions |
| 97-105 | `suspendWith` builds `Pending.SuspendArrowWith` and fuses `f` into the node's `apply` | a, node representation (comment at 48) |
| 107-110 | `// Diverges from main:` for the `handleCont` family | a, handler family |
| 114-116 | `handle` doc paragraph rewritten: the clause takes an `Arrow` continuation and S2 is allowed, so main's "does not allow introducing new effects (no S2 type parameter)" is false | b |
| 127 | `handle` renamed `handleCont` | a, handler family |
| 131-133 | continuation is `Arrow[O[C], A, E & S & S2]`, `safepoint: Safepoint` gone, body delegates to the `done` overload | a + c |
| 135-231 | added `handleCont` overloads with `done` and with `done` + `recover`, building `Handler.ContHandler` and `Pending.HandleArrow` | a, D2 recover arm and node representation |
| 137-195 (deleted side) | main's two, three and four effect `handle` overloads removed | a, ruled removal (named in the comment at 107) |
| 232-239 | `FirstSuspended` helper and its `// Not on main:` comment | a, `handleFirst` |
| 256-275 | `handleFirst` gains `@nowarn`, becomes `private[kyo]`, takes an `Arrow` continuation, returns through `B < (S & S2)`, loses `safepoint`, and is now a `handleCont` whose `done` arm answers the carried suspension | a + c |
| 277-278 | `// Diverges from main:` for `dispatchFirst` | a |
| 287-302 | `dispatchFirst` loses `inline` on the method and on `effectTag`/`f`, and walks `Handle`/`Park`/`Defer` nodes down to the head suspension with a `@tailrec` loop | a + c |
| 304-305 | `// Diverges from main:` for the `handleLoop` family | a |
| 325-331 | short `handleLoop` clause is `[C] => I[C] => Loop.Outcome2[...]`, no continuation parameter, no `safepoint` | a + c |
| 333-394 | added `handleLoop` overloads with `done` and with `done` + `recover` | a, D2 |
| 396-397 | `// Diverges from main:` for the `handleLoopState` family | a |
| 420-427 | main's stateful `handleLoop` renamed `handleLoopState`; clause is `(State, I[C]) => Loop.Outcome2[...]`; no `safepoint` | a + c |
| 453-500 | `handleLoopState` with `done`: `@nowarn`, `Handler.LoopHandler` with `answers` override, `Pending.HandleArrow` | a, node representation |
| 502-575 | added `handleLoopState` with `recover` | a, D2 |
| 577-641 | `handleContOperation`, two overloads, and its `// Not on main:` comment | a |
| 643-770 | `handleContWith`, `handleLoopWith`, `handleLoopStateWith` and their `// Not on main:` comment | a |
| 654-770 (deleted side) | main's `handleCatching` and `handlePartial` removed | a, ruled removal (named in the comment at 107) |
| 772-793 | `Mask` and its companion, with its `// Not on main:` comment | a, D5 |

No hunk remains that is not on this list, and none of them is whitespace, import style, member
order, or a name that main also has.

## Notes and open items

- `funcionInput` is main's misspelling. Adopted verbatim per the brief; fixing it is a separate
  change that has to touch main.
- The class doc still says "handle: Basic handler that doesn't allow introducing new effects
  during handling", and the short `handleLoop` doc still says "over basic handle". Both are main's
  text on members whose meaning did not change, so they were left verbatim; they now name a method
  called `handleCont`. Correcting them is a doc change to make deliberately, not a side effect of
  this pass.
- `handleFirst` stays `private[kyo]`, unlike main. This is not cosmetic: its inline body mentions
  `FirstSuspended`, which is `private[kyo]` and carries no `@publicInBinary`, so a public
  `handleFirst` would need `FirstSuspended` widened too. Recorded as design, with a comment at the
  site.
- `dispatchFirst` stays non-`inline`. The body is a `@tailrec` walk, not main's single match.
- The trailing `(using inline _frame: Frame)` clause is written on one line rather than main's
  four-line `)( / using / inline _frame: Frame, / safepoint: Safepoint / )` block. Main's block is
  four lines only because it had a second using parameter; with `safepoint` removed by ruling, the
  one-line form is what main itself writes for a single-parameter using clause elsewhere in the
  module (`ContextEffect.scala` lines 102 and 127, `Effect.scala` line 68 on main).
- Left alone, and not a diff item because main has no counterpart: `handleLoopStateWith`'s state
  parameter is named `state0` while `handleLoopState`'s is `state`. Renaming it would change a
  public parameter name on a member the diff does not otherwise touch, so it is a separate call.
- No rename was skipped for crossing files. The only candidates that would have crossed
  (`handleFirst`'s visibility, the `Handler.*` names) are handled above.
- Not run: no compile, no test. The brief forbids sbt. The changes are renames, reordering,
  import-scope changes and comment moves; the two with any residual compile risk are named here.
  `import kyo.*` plus `import kyo.kernel.internal.*` is exactly main's import set for this file,
  and every name this file uses under them (`Frame`, `Tag`, `Maybe`, `Id`, `Pending`, `Nested`,
  `Handler`, `Safepoint`, `Arrow`, `Loop`, `Effect`) resolves from exactly one of the two packages
  or from `kyo.kernel` itself. `handleFirst` now passes a non-`inline` `effectTag` into
  `handleCont`'s `inline effectTag`, which binds it as a val at expansion.
