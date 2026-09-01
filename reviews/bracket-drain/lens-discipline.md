# kernel-discipline: bracket-drain (3eb1c6991d..HEAD)

```
verdict: BLOCKED
missing rows: 0
weak verdicts: 4   (D1, D3, D6 on flags.md rows; D2 on the concession contract)
surface findings: 1 (D5)
claim findings:   3 (D1, D4, D6)
structure findings: 0
```

## Completeness

`kyo-kernel/.claude/skills/kernel/flags.sh 3eb1c6991d -- kyo-kernel/shared/src/main` emits 56
flags. `flags.md` carries 56 rows, F1..F56, and every row matches the script's site and class
one for one. Every verdict cell is filled. **Completeness passes; there is no missing row.**

Structure class: no comment on the diff asserts a tail call. The one new `@tailrec` (`fill`,
`Stack.scala:197`) is a genuine tail call, and the two pre-existing `@tailrec` methods the
change edits (`install`, `recovered`) keep their recursive calls in tail position after the
added statements. No finding.

---

## D1 The `net zero` allocation claim on F8 has no number, and the structural count contradicts it

site: `reviews/bracket-drain/flags.md:16`
quote: `| F8 | Effect.scala:50 | `val open = new Arrow.Bind[A, B, S1 & S2]:` | allocation | one per bracket call, replacing the map arrow the previous shape allocated at the same site: net zero |`
second site: `reviews/bracket-drain/review.md:112` (`| bracket call | Bind replaces the map arrow (net zero); Cell per shot | bracket rows (new) |`)

why: "net zero" is an allocation claim, and the closed vocabulary admits it only as a
measurement. None is cited, and the package states the benches are parked. Counting the nodes
at the site the verdict names gives the opposite answer. Old shape, `Sync.scala:57`
(`defer(acquire).map { a => ... }`): `deferInline` allocates one `Kyo.DeferWith`, and `map` is
`inline` (`Pending.scala:21-38`) so on a `Pending` receiver it allocates one more `Kyo.DeferWith`
and folds `f` into it, with no arrow object of its own. Two nodes. New shape,
`Effect.scala:50` and `Effect.scala:70` (`defer(acquire).chain(open)`): the `Arrow.Bind`
instance is allocated unconditionally, `deferInline` allocates a `Kyo.DeferWith`, and
`chain` drives `Bind.apply` into `Effect.defer(v, this, Arrow.id)`, which folds to the two-arg
overload and allocates a `Defer` (`Effect.scala:73-86`). Three nodes. The `map` this replaces
was never an arrow allocation, so there is nothing for the `Bind` to net against at that site.
If the intended claim is net-across-the-whole-bracket, counting the exit map that
`ContextEffect.handle`'s `done` edge deleted from the body, the verdict must say so, and it
still needs `gc.alloc.rate.norm` behind it rather than a structural assertion.

## D2 F42 and F44 are concession rows missing the fourth part: a pinning test named by file

site: `reviews/bracket-drain/flags.md:50`
quote: `| F42 | Stack.scala:19 | `private var owed = new Array[Chunk[Stack.Snapshot]](0)` | mutability | the fourth parallel array, same concession shape as handlers/states/continuations, protected by the reset-at-exit protocol (`pop` returns and clears, truncate and clear reset, grow fills) |`
second site: `reviews/bracket-drain/flags.md:52` (F44, `private var evalOwed: Chunk[Stack.Snapshot] = Chunk.empty`)

why: both rows carry justification, minimal scope, and a protective measure, and neither names
a pinning test. The contract requires all four, and these two are the rows where the fourth
part is load-bearing rather than ceremonial: `Stack` is thread-local and pooled
(`Stack.scala:253-275`), so the reset protocol these verdicts lean on is a by-inspection
invariant spread over six sites (`pop`, `takeOwed`, `truncate`, `clear`, `snapshot`, `dump`,
plus `grow`'s fill). Its failure mode is an obligation surviving `Stack.release` into an
unrelated later eval on the same thread, which surfaces as a spurious release in a computation
that never bracketed. No pin listed in `derivation.md:158-164` or in the `review.md:114-126`
suite exercises stack reuse across evals with a non-empty owed slot; every listed pin observes
the drain from inside one eval. Either name the test that pins the protocol, or add it.

## D3 F1's load-bearing premise is false: not every `Step` consults the budget gate

site: `reviews/bracket-drain/flags.md:9`
quote: `| F1 | Arrow.scala:96 | ... | new-type | justified: the gate-skip is the class's whole contract and no existing arrow shape can carry it, since every Step and Transform application consults the budget gate; a flag on Step would be look-safe indirection; main's `BindingStep` is the precedent |`

why: `Arrow.Id` is a `Step` (`Arrow.scala:46`) whose `apply[C, S2]` (`Arrow.scala:49-53`)
contains no `Safepoint.enter`. It sits fifty lines above `Bind` in the same file, so a reviewer
reading top to bottom meets the counter-example before the claim. The conclusion may well
survive on the accurate premise (every `Step` that *applies a user function* gates: the
anonymous step in `Arrow.apply:71`, the seven in `Loop.scala`, `Pending.scala:279`,
`Effect.scala:126`), but the sentence as written is the justification for the change's one
genuinely new type, and it is wrong. The other two clauses check out: `Bind` is a real gate-skip
that no existing shape provides, and `BindingStep` exists in main at `kyo/Arrow.scala:161-169`
in the same shape, re-abstracted `apply` included.

## D4 The two artifacts report different JVM suite totals for the same tip

site: `reviews/bracket-drain/derivation.md:156`
quote: `## Pins (all green, 1535/1535 JVM)`
second site: `reviews/bracket-drain/review.md:94` (`| kernel JVM suite | 1542/1542 at the tip |`)

why: both sentences claim the JVM suite count at the change's tip and they disagree by seven.
One of them is stale, most likely the derivation's, written before `dc26bade37`. A number a
reader cannot reconcile between two files of the same package is a defect whichever way it
resolves; re-derive it and make both say the same thing.

## D5 Three changed sites are not declared in the derivation's Surface section

site: `kyo-kernel/shared/src/main/scala/kyo/proto/kernel/ContextEffect.scala:109`
quote: `        handle(effectTag)((outer: Maybe[A]) => outer.fold(ifUndefined)(ifDefined), fork, join, release = release)(v)`

why: `derivation.md:121-142` declares for `ContextEffect.scala` only the `done` parameter, the
settled arm's `done(derive(Absent))`, and the contract scaladoc. The body of the *other*
`handle` overload (the `ifUndefined`/`ifDefined` one) also changed, to name its `release`
argument so the new defaulted parameter cannot misbind. It is forced by the declared change and
`review.md:69-71` states it, but the Surface section is the declaration of record and does not.
Two further undeclared sites in the same class: the comment added inside `Eval.released`
(`Eval.scala:146`, a method the Surface list does not name) and the deletion of
`kyo-kernel/shared/src/test/scala/kyo/proto/SyncTest.scala` (the Surface declares the `Sync`
prototype's deletion and the new `EffectBracketTest.scala`, not the old test file's removal).
All three are harmless; the finding is that the Surface list is not the exact list, which is
what a later reader will check the diff against.

## D6 "cold rebound helper" is an unbacked characterization the package's own delta table contradicts

site: `reviews/bracket-drain/flags.md:28`
quote: `| F20 | Eval.scala:130 | `var c = ctx` | mutability | engine-room local in the cold rebound helper (per non-top crossing); never escapes |`

why: the same verdict text covers F21, F22 and F25. "cold" is doing work here: it is the reason
the mutability concession is minimal-scope rather than hot-path state. But `derivation.md:153-154`
and `review.md:111` both list non-top crossings as a hot-path delta with named benchmark gate
rows ("emitting, crossing"), which is the package saying the opposite about the same helper.
No profile or count is cited for either statement. The engine-room justification stands on its
own without the adjective; drop "cold", or cite the row that makes it cold.

---

## Considered and not raised as findings

- **The four "false positive" verdicts** (F4, F10, F40, F41) are outside the closed vocabulary
  but each is factually correct and trivially checkable: all four sites are comment or scaladoc
  lines that the script's regexes matched on the words `while`, `after`, and `null`. The script
  is documented as recall-tuned and false-positive tolerant, so declaring a matched comment line
  a false positive is a complete disposition, not a rationalisation. Noted so the gap is visible
  rather than silently accepted.
- **F6** ("companion of F5") inherits F5's `moved` provenance; `Cell`'s companion is byte-identical
  to `Sync.scala:36-44` in the deleted prototype. Discharged.
- **F50** ("same shape as the three sibling arrays beside it") brushes the banned "consistent with
  the existing code", but it leads with an allocation frequency ("grow-time") that is checkable and
  true, which is what carries the row.
- **Every `moved` verdict was re-derived against the diff, not accepted on assertion.** F18-F19,
  F26-F33, F34-F38, F45-F49, F52-F55 each name code that appears verbatim (or with only the stride
  or indentation change stated) on the removed side of the same hunk. F3, F5 and F7 are verbatim
  from the deleted `Sync.scala`. All check out.

## Routed, outside this lens

`derivation.md:80` states the bind needs "No eval change; no guard, no packer, no livelock
surface", and cites main's `BindingStep` as the precedent. Main pairs that class with an
eval-side guard: `parkable` at `kyo/kernel/internal/Eval.scala:490-500` refuses to park when the
receiver is an `Arrow.BindingStep`. The proto reaches the same goal by a different mechanism
(the gate is skipped, so the value's own gates all fire before it settles) and pins it
(`EffectBracketTest.scala:116`, "a stop landing as the acquire settles still installs the
region"). The difference is argued and tested, so it is not a discipline finding, but the
one-word "precedent" in F1 hides it and `kernel-conformance` should be the one to rule on
whether the proto's omission of main's guard is sound.
