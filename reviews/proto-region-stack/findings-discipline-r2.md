# kernel-discipline, round 2

verdict: BLOCKED
missing rows: 1
weak verdicts: 11

Re-derived with `flags.sh 31a7b4bde9 -- kyo-kernel/shared/src/main/scala/kyo/proto` in
`.claude/worktrees/kyo-root-impl` at `1f47d6f590` (clean tree). The script emits **88 rows**, which
matches the table's count, and every id F1 to F88 is named somewhere in the table. Completeness by
id therefore passes.

Completeness by *construct* does not. Eleven ids are adjudicated against a line other than the one
the id names, so the construct the id actually carries was never defended. That is the same defect
the table's closing section says the earlier version had ("two entries adjudicated a different line
than their id named"); it recurs at larger scale. Separately, the catalog entry for the eval's
budget adjudicates a construct that is not in the change while the construct that is has no row.

The `moved` group was the caller's named focus. Its provenance is now real (the range is relocated
baseline code) but its verification sentence is again stronger than what the diff supports: it
asserts an exhaustive list of five differences, and there are more.

---

## D1 the budget entry adjudicates a `finally restore` that the change does not contain, and the construct that is there has no row

site: `kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:42`
quote: `        val saved = Safepoint.save(slot)`

`flags.md:105` reads:

> `ffc1819ecc` adds `Safepoint.get`, `save` and a `finally restore`; `1f47d6f590` adds
> `Safepoint.reset(slot)` in the guard.

`ffc1819ecc` did add `try run(v, Context.empty) / finally Safepoint.restore(slot, saved)`.
`1f47d6f590` **removed it** when it replaced `run` with the loop:

```
-        try run(v, Context.empty)
-        finally Safepoint.restore(slot, saved)
```

At the adjudicated tip, `grep -n 'Safepoint\.' Eval.scala` returns exactly three lines: 41 (`get`),
42 (`save`), 313 (`reset`). There is no `restore` anywhere in the file, and `saved` is read nowhere
(`grep -n 'saved'` returns only line 42, its own definition).

So the row adjudicates one construct that is absent and leaves the construct that is present
unadjudicated: an eval that installs a fresh budget and never puts the caller's back. A nested eval
now returns the thread to the enclosing eval with the nested one's budget, which is precisely the
property `reviews/proto-eval-budget/derivation.md` says the `finally` exists to provide ("The
`finally` is what makes a nested eval leave the enclosing one's budget as it found it"). The
verdict on that row is `justified: measurement`, and the cited measurement (200 to 12800
recoveries) is about `reset`, not about `save`, so nothing in the table covers the dead `saved`
binding or its consequence.

This is the missing row. It is also the one finding whose subject is a live behavioural gap rather
than a bookkeeping error.

## D2 F13 and F42 name lines they do not contain, so neither boundary assertion is adjudicated under its own id

site: `flags.md:72`
quote: ``**F13** (`if stack.isEmpty then susp.asInstanceOf[A < S]`), **F42** (`res.asInstanceOf[A < S]`).``

The re-derived rows are:

| id | actual line | actual text |
|----|----|----|
| F13 | Eval.scala:118 | `case p: Pending[VX, S3] @unchecked => Effect.defer(p, this, c2)` |
| F42 | Eval.scala:229 | `val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` |
| F14 | Eval.scala:122 | `if stack.isEmpty then susp.asInstanceOf[A < S]` |
| F46 | Eval.scala:260 | `if stack.isEmpty then res.asInstanceOf[A < S]` |

The section's `representation assertion` verdict is written for F14 and F46 and filed under F13 and
F42. F13 falls inside the `moved` range and keeps a correct verdict by accident; F42 does not, and
receives a verdict describing a cast it is not (see D4). F14 and F46 receive only whatever their
containing group says: F14 gets `moved`, F46 gets the storage-boundary read verdict of D3. Neither
is `representation assertion` anywhere.

Same section, one further inaccuracy: "Both replace the baseline's `asInstanceOf[C < S]` at the same
two sites." True for F46 (baseline `Eval.scala:229`, `res.asInstanceOf[C < S]`, same arm, one added
`stack.isEmpty` guard). Not true for F14: the baseline cast it replaces is
`if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then kyo.asInstanceOf[C < S]`
(baseline `Eval.scala:45`), the identity-registers fast return. The new cast sits after the whole
register-absorb block under a different guard on a different subject. Same arm, not the same site.

## D3 F44, F45, F52 and F53 are adjudicated as the storage-boundary read; all four are relocated typed patterns

site: `flags.md:46`
quote: `**F44, F45** (Eval.scala 242, 248), **F46 to F53** (260 to 273), **F63 to F66** (318 to 320).`

The section quotes a three-line block (`stack.handler`, `stack.state`, `stack.cont`) and gives it
`justified: erasure-forced`. Four of the ids it claims do not carry that construct:

| id | site | added line | what it actually is |
|----|----|----|----|
| F44 | Eval.scala:242 | `case kyo: Kyo.Handle[EX, AX, Y, T, S2, VX] @unchecked =>` | typed pattern on the `Handle` node |
| F45 | Eval.scala:248 | `case h: Handler.HandlerContext[VX, CX, AX, Y, S2] @unchecked =>` | typed pattern on the handler |
| F52 | Eval.scala:273 | `case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>` | typed pattern on the chain arm |
| F53 | Eval.scala:273 | `case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>` | the `Any` in that pattern's type |

All four are also *relocated*, not new. Their baseline counterparts are `Kyo.Handle[EX, AX, Y, A, S,
VX]`, `Handler.HandlerContext[VX, CX, AX, Y, S]` and `Arrow.Chain[A, Any, B, S]` at baseline
`Eval.scala` 174-ish, 207 and 232; the only change is the declared type-parameter rename. The
section heading calls them "New", and none of them touches `Stack`.

Under the cast ladder these are step 2 (typed pattern with `@unchecked`), not step 4
(erasure-forced). Neither the right category nor the right provenance reaches these rows.

F63 to F66 in the same list are correct: they are the reads at Eval.scala 318-320 inside the unwind.
Note that F66's cast is `asInstanceOf[Arrow[Y, A, S]]`, not the `Arrow[Y, Any, Any]` the block
quotes, so the section's "asserting them at `Any` is the subtype end ... the row is not claimed, it
is widened out of the way" argument does not describe F66. Its bare `erasure-forced` category still
stands.

## D4 the real storage-boundary casts sit inside F1 to F43 and carry only `moved`

site: `kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:124`
quote: `                        val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]`

The lines the D3 section means are F15, F16, F17 (Eval.scala 124-125), F33, F34 (202) and F42, F43
(229). All seven are inside the `F1 to F43` range, so their verdict is the group's `moved`.

`moved` is the wrong verdict for a cast with no baseline counterpart. The baseline read
`kyo.handler` and `st` off `region`'s closure with no cast at all; there was no `asInstanceOf` at
any of these positions to be moved. `flags.md:26-27` acknowledges the read source changed
("the rebuild reads `handler` and `state` from the stack entry where it read `kyo.handler` and `st`
from `region`'s closure") but names only `handler` and `state`, never `cont`, and supplies no cast
category for any of them.

The carrier-class ids among them (F16, F34, F43) do pick up the widening argument via the
reconciliation list at `flags.md:67`. The cast-class ids F15, F17, F33, F42 pick up nothing but
`moved`. The result is inverted: `erasure-forced` is spent on four rows that are typed patterns
(D3), and withheld from the four rows that are the actual array re-typing the cast ladder names
`Stack` for.

## D5 the `moved` group's "five differences" list is not exhaustive

site: `flags.md:19`
quote: `` `moved`: this is the baseline's code for these arms. It is **not byte-identical**, and the five ``

I reproduced the comparison by extracting baseline `Eval.scala` 29-206 and tip 47-242, applying the
declared renames (`S2` to `S3`, `S` to `S2`, `A` to `T`) to the baseline and stripping indentation.
Beyond the five listed differences the normalized diff shows at least these, each a behavioural
change inside the F1 to F43 range:

- `Eval.scala:216` , `loop(r, Arrow.id, Arrow.id, ctx)` where the baseline had
  `region(st, r, Arrow.id, ctx)`. The recursive call into `region` becoming a tail call into `loop`
  is the substance of the change, and it is inside the range verdicted `moved`.
- `Eval.scala:223` , `stack.state = o._1`, replacing `region(o._1, o._2, next, ctx)`. This is a
  write into the mutable column whose `var` (F74) the previous round found unadjudicated; the write
  site itself is now inside a `moved` group and named nowhere.
- the region-completion arm is deleted from this range entirely (baseline
  `kyo.handler.done(st, Nested.unnest[AX](res))` inside `region`) and reappears at
  `Eval.scala:265`, outside the range.
- `Eval.scala:65` , `val susp: Kyo.Suspend[?, ?, ?] =`, a new binding that turns the absorb block
  from a returned expression into a value, together with the loss of the baseline's
  identity-registers `asInstanceOf[C < S]` return at that position (see D2).
- three new `Debugger.onRegionExit` call sites (Eval.scala 200, 228, 266). `Debugger` itself is
  untouched, so this is not a surface finding, but it is not one of the five either.

Difference (5) covers only the foreign-crossing arm's entry and its `val rebuilt`. The sentence
"the five differences are" is a closed enumeration and the enumeration is open. This is the same
failure shape as the round-1 finding it replaces: the correction named more differences but still
asserts completeness it did not establish.

## D6 F19 is a comment adjudicated as a type widening

site: `kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:134`
quote: `                                // instead of re-entering it. Nothing inside the try is evaluated, so a`

`flags.md:67-68` puts F19 in the list of rows that "are the same widening inside the relocated
range". F19 is a comment line, flagged by the script only because it contains the word "Nothing".
It is a false positive, and the table has a section for those (`flags.md:145-149`, listing F67, F68
and F69). It belongs there, not in the widening list, where its verdict asserts something about the
line that is not true of it.

## D7 "No value in this change is typed `Any`" is false, and the table's own Stack section contradicts it

site: `flags.md:65`
quote: ``**No value in this change is typed `Any`, `Any < Nothing`, or `Null`.**``

`Stack.scala` adds, on lines the table itself adjudicates as F73, F81, F82, F83 and F85:

```
    private var states   = new Array[Any](8)
    def push(handler: Handler[?, ?, ?, ?, ?], state: Any, ctx: Context, cont: Arrow[?, ?, ?]): Unit =
    def state: Any                      = states(size - 1)
    def state_=(v: Any): Unit           = states(size - 1) = v
```

`flags.md:141-143` gives those rows `justified: erasure-forced, the storage boundary itself. This is
the column whose type genuinely cannot be written`, which is a defensible verdict for a value typed
`Any`. The blanket sentence at line 65 says no such value exists. One of the two is wrong, and the
sentence is the wrong one. Because line 65 is bolded and unqualified, it reads as a discharge of the
whole carrier class rather than of the four ids the paragraph is about.

## D8 the header's "no id in two groups" is contradicted by the table's body

site: `flags.md:4`
quote: `rows**. Every id from F1 to F88 appears in exactly one group below, and the groups' ids union to all`

`flags.md:67-68` says of eleven ids that they are "counted in F1 to F43 above and repeated here",
`flags.md:100` says F63 to F66 are "adjudicated with that group above" while also appearing in the
guard section, and `flags.md:72` names F13 and F42, both inside F1 to F43. Three of the table's own
sentences deny the header's claim. The union is complete, which is what matters; the disjointness
claim is simply untrue and should not be asserted.

## D9 "no confirmed regression on any of 20 rows" is not measured at the adjudicated tip

site: `flags.md:136`
quote: `on both legs shows no confirmed regression on any of 20 rows, the region-heavy ones included; the one`

`evidence.md:35` states the legs: `Control 31a7b4bde9, variant ffc1819ecc`. The diff under
adjudication is against `1f47d6f590`, which rewrote the eval's guard from a recursion into a
`while` loop with an inner unwind loop, added `Safepoint.reset`, and removed the `finally restore`
(D1). That is the eval's own `apply`, which the standard names as shared machinery every row runs
through.

For the rows the sentence is cited to justify (F72, F75, F77, F79, F84, F86, F87, F88, the `Stack`
array allocations) the measurement is still on point: `Stack.scala` is byte-identical between
`ffc1819ecc` and `1f47d6f590`. What is not supported is the unqualified form of the claim in
`flags.md`. `evidence.md` names its shas honestly; `flags.md` drops them, and the sentence as
written asserts no regression for a tree that was never run. Either qualify it to the leg that was
measured or re-run the class on `1f47d6f590`.

---

## What passes

Recorded so the next round does not re-litigate it.

- **Row coverage by id.** 88 script flags, ids F1 to F88, every one named. No id is absent.
- **False positives.** F67, F68 (`end while`) and F69 (`Handler.scala:22`, "while" inside a
  scaladoc sentence) are correctly identified and given a verdict rather than dropped.
- **Surface.** The diff touches `Eval.scala`, `Handler.scala`, `Stack.scala` (new) and
  `EvalTest.scala`, and nothing else. `Handler.scala`'s change is `recover`'s scaladoc only, exactly
  as `derivation.md` declares. `Debugger`, `KyoInternal`, `ArrowEffect`, `ContextEffect`, `Pending`,
  `Loop`, `Arrow`, `Effect`, `Eval.release` and `answerLoop` are untouched, as declared. The three
  file-level imports are the three declared. `Debugger.onRegionExit`, `onRegionEnter` and
  `onForeign` already existed at the baseline, so the new call sites add no undeclared surface. No
  surface finding.
- **Structure.** `@tailrec def loop` is present at `Eval.scala:47` and the tree compiles and tests
  (`evidence.md`: 35 suites, 1399 passing with all three commits in), so the tail-call property is
  checked by the compiler rather than claimed in a comment. No tail call is asserted in a comment
  that is not one. No structure finding.
- **Concession contracts.** Both concession groups carry all four parts, and every pinning test
  named by file exists: `EvalTest.scala:608` "regions that fail and recover in sequence cost no
  stack", `EvalTest.scala:586` "a nested eval shares the thread's stack and sees none of the outer
  regions", `EvalTest.scala:120` and `:168` (the multi-shot cases),
  `ArrowEffectTest.scala:285` "handles nested per recursion step in bounded stack".
- **`Stack`'s new-type argument** now names a reason rather than an asker, and the four
  `measurement pending` verdicts are gone: the allocation rows cite `evidence.md`'s full class with
  the `-f 3` confirmation (49.346 +/- 0.558 against 48.985 +/- 0.416).
- **Banned vocabulary and placeholders.** The script emits no `terminology` and no `placeholder`
  row; the baseline's `// TODO let's move to a separate method, not nested` and its "driven" wording
  are both gone. The "Nothing removed" section's claim holds on this axis.
