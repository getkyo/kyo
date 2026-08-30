verdict: BLOCKED
repeats: 0

Round 4. Judged against the package as it stands now: seven commits, tip `b58e2fbc4e`, which is
what review.md's header names and what the worktree holds. Round 3's findings that are verified
fixed: the tip and commit count are right; flags.md is internally consistent (90 rows, F1-F90 each
exactly once); the derivation says six locals where the deleted code had six; the edit sequence now
covers the Handler scaladoc and the tests; the r3 REPEAT (release's scaladoc outside the declared
surface) is resolved by declaring it, and the declaration is honest. The open eval-boundary failures
are honestly presented: named precisely, marked red in both tables, both dead ends recorded, no
"pre-existing" defence, and flags.md now calls the red nested-eval pin red instead of counting it.
The guard rewrite at the tip is, on its own, the better code: no vars, no `Any` carrier, semantics
line-for-line equivalent to the loop it replaces, and the one new allocation is declared.

None of that survives the first stop. The package's evidence describes a tree two commits behind
the one it asks me to review, and its own recorded rule says what to do with such numbers.

## R1 Every number in this package was measured on a tree that is not the one under review

site: review.md:160, against review.md:6; evidence.md:35, evidence.md:39-41
quote: "Full detail in `evidence.md`, all of it measured on the shipped tip `d197133298`."
quote: "Worktree `kyo-root-impl`, seven commits off `31a7b4bde9`, tip `b58e2fbc4e`."
quote: "An earlier pair measured `ffc1819ecc`, which was not what shipped: the tip rewrote the guard every row enters. Those numbers are discarded rather than carried forward"

`d197133298` is not the tip. `b58e2fbc4e` rewrote the guard, the code every benchmark row, every
suite, the 12800-cycle scaling probe, and the demo enter. Your own sentence at evidence.md:39-41,
written to discard the `ffc1819ecc` pair for exactly this reason, discards this entire evidence
section: the benchmarks, "No row regressed", 88 of 88, 204 of 207, 1400 passing, the demo's 28
values, the 0.89 us per recovery, and the clean batch build are all dated to a superseded guard.
The new guard also changes what those numbers measure: it allocates a pair per recovery, so the
recovery-scaling table describes code that no longer exists. b58e2fbc4e's commit message claims a
suite re-run; the package records none of it, and no bracket ran at the tip at all. This is the
same stop as round 2 (benchmarks at `ffc1819ecc`) and round 3 (suite numbers two commits behind),
third time, after escapes.md declared `package-check.sh` the mechanical repair for exactly "tip
sha, ... suite totals, benchmark leg shas". Either it did not run or it does not check what
escapes.md says it checks. evidence.md's header still reads "Change: `a10624dfa4`".

## R2 The adjudication table describes the dirty working tree, not the tip

site: flags.md:4, flags.md:30-31
quote: "against the tip `b58e2fbc4e` emits **90 rows**. Every row appears below exactly once, with the line its id actually carries"
quote: "| F1 | kernel/Effect.scala:52 | `// TODO is there a reason for this? it'll create a pointer in deferInline` | placeholder |"

Effect.scala:52 does not exist at `b58e2fbc4e`. That TODO is an uncommitted working-tree edit, and
F2's line is 66 at the tip, cited as 67 because the uncommitted insertion shifts it. "Filing
verdicts against lines their ids did not name" is the failure this file's own preamble says blocked
two earlier versions. And the second TODO being uncommitted is the exact hazard the skill's rule
names ("working-tree comments do not survive the A/B brackets"), one commit after ef640d1f55's
message described committing the first one on sight.

## R3 The derivation argues against the design that shipped

site: derivation.md:101-103, derivation.md:142-143
quote: "It is a loop rather than a recursion, because a recovery that resumed by evaluating the rest of the eval from inside its own catch would cost a frame per recovered region"
quote: "**The guard's loop.** Justified because the alternative costs a frame per recovered region. Scoped to six locals in `apply` (`curr`, `ctx`, `out`, `settled`, `ex`, `unwinding`)"

The shipped guard is a recursion that recovers from inside its own catch, and it has zero locals.
The derivation's design rationale names the shipped shape as the rejected one, and its concession
contract inventories six vars that are not in the tree. review.md's edit 6 argues the opposite and
wins on the evidence; the derivation beside it was never re-derived. derivation.md:137 also still
counts "a nested eval shares the thread's stack" as a live pin where flags.md honestly calls it
red. The package's two core documents disagree about the change's central mechanism.

## R4 "flags.md names all five differences" is still not something flags.md does

site: review.md:79-80
quote: "The rebuild block and `reenter` are the baseline's code, relocated, but not byte-identical, and `flags.md` names all five differences."

flags.md contains no enumeration of five differences; the word "five" does not appear, and
`reenter` appears nowhere in it, because a signature change produces no flag row. The difference
review.md itself calls the one worth my attention is precisely the one flags.md cannot carry.
Round 3 stopped on this sentence; it is unchanged.

## R5 "The one row outside the drift band" is false on the package's own table

site: review.md:176, against evidence.md:65 and evidence.md:70
quote: "The one row outside the drift band on `-f 1`, `trailingMapsStayLinear` at +4.9%, was confirmed at `-f 3`"
quote: "| deepRecursionPaysRescuesOnly | 52.117 ± 0.436 | 49.556 ± 3.734 | -4.9% | error exceeds delta |"

The band is stated as 3 to 4%. -4.9% is outside it exactly as much as +4.9% was, got no `-f 3`
confirmation, and evidence.md:70 calls the two faster rows "at or inside the band", which -4.9%
is not. The skill's rule is `-f 3` on any row outside the band; direction is not an exemption,
and the variant leg's ±3.734 on that row is 7.5% relative error with no warmup guard run over it.

## R6 The range changes Effect.scala; the sequence never presents it and the derivation forbids it

site: derivation.md:117-118, against commit ef640d1f55
quote: "Does **not** change: `KyoInternal` node classes, the `Handler` protocol's signatures, `ArrowEffect`, `ContextEffect`, `Pending`, `Loop`, `Arrow`, `Effect`, `Eval.release`, `answerLoop`."

`31a7b4bde9..b58e2fbc4e` changes Effect.scala (the committed TODO). It is my own note and
committing it was right, but the walk has ten edits and the range has eleven changes: an
undeclared edit in a file the derivation lists under does-not-change. One declaring sentence
fixes it; its absence means the sequence and the range disagree.

## R7 Four locals or six?

site: review.md:97-98, against derivation.md:143
quote: "the second over-corrected into four locals and two `while` loops"

The deleted guard had six (`curr`, `ctx`, `out`, `settled`, `ex`, `unwinding`), which is the count
round 3 already corrected once and the derivation still carries. The two files now disagree with
each other about code neither of them ships.

## R8 A recover that fails itself: stated as contract, pinned nowhere

site: Handler.scala:19-20 and Eval.scala:305 at the tip
quote: "A recover that fails itself is the failure those enclosing regions then see."
quote: "catch case ex2 if NonFatal(ex2) => return recovered(ex2)"

In the baseline this behavior came free from nested tries. At the tip it is hand-written control
flow that has now been rewritten twice (var swap, then return-in-catch), and no test throws from a
`recover`: ArrowEffectTest's "evaluation recovers after a thrown handler" throws from a clause,
not from `recover`. A scaladoc contract plus twice-rewritten machinery with no pin is the
combination this skill's concession rule exists to forbid.

## R9 The budget-leak fix does not cover the reenter path

site: Eval.scala:142-147 at the tip
quote: "val r = handler.recover(state, ex).getOrElse(throw ex)"

`Safepoint.reset` runs only in the guard, where depth is known zero. `reenter`'s catch also
recovers, and a throw from the resumed application (`k0.head(x, k0.tail)` runs user code under
paired `enter`/`exit`) leaks every entry between the try and the throw site, with no reset
possible there because depth is not zero. An extent that recovers repeatedly across a foreign
crossing drains the eval's budget toward the same fixed point; the 10000-cycle pin never crosses
a foreign region. If the claim "a throw stops leaking the budget" is meant to be scoped to the
guard path, say so and pin the foreign path's behavior; if it is not, the fix is incomplete.

## R10 Two mechanical nits in evidence.md

site: evidence.md:10, evidence.md:60
quote: "**Proto suites**, each in its own JVM (see the blocked section for why not together):"
quote: "| uncachedValuesPayBoxingOnly | 47.227 ± 4.520 | 47.659 ± 0.436 | +0.9% | flat |"

No section named "blocked" exists in the file. And the table is declared sorted by delta but +0.9%
sits between -0.8% and -1.2%, which reads as a hand-inserted row in a table claimed generated.
Small, but this package's history is exactly these.
