# Rehearsal findings, round 2

verdict: BLOCKED
repeats: 0

Inputs read: `review.md`, `derivation.md`, `flags.md`, `evidence.md`, `reviews/proto-eval-budget/derivation.md`, the kernel SKILL.md and rulings.md, and `git -C kyo-root-impl diff 31a7b4bde9..1f47d6f590`. The flags skeleton was reproduced with `flags.sh 31a7b4bde9..1f47d6f590 -- kyo-kernel/shared/src/main/scala/kyo/proto` to check the table's ids; it emits the same 88 rows.

All six round-1 findings are fixed in the current state (verified, not assumed): the guard is a loop with a 10000-cycle pinning test, the second behavioural change is declared, `@tailrec` pins the tail calls, `pop`'s comment states the real retention and drops the cost claim, the drift band is in the benchmark header, and the walk sentence now says the relocation is not byte-identical. The blocks below are new.

## R1 Where is the restore? Step 7 walks a `finally` that is not in the tree.

site: kyo-root-impl kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:42
quote: `val saved = Safepoint.save(slot)`

The package's step 7 shows `try run(v, Context.empty) finally Safepoint.restore(slot, saved)`. That code existed at `ffc1819ecc` and `1f47d6f590` deleted it when it folded `run` into the while-loop driver: at the final commit `Safepoint.restore` is called nowhere (grep: `save` at line 42, `reset` at 313, no `restore`), so `saved` is a dead binding, and the comment above it (lines 39-40, "Restored below, so a nested eval leaves the enclosing one's budget as it found it and a throw does not leak this one's") describes code that does not exist. The eval-budget derivation names this line as load-bearing: "The `finally` is what makes a nested eval leave the enclosing one's budget as it found it, and what keeps a throw from leaking the eval's own", and its surface says "restore in a `finally`". Consequence in the shipped tree: every eval permanently replaces the thread's slot state; a nested eval discards the enclosing eval's part-spent depth, the enclosing eval's still-open strict applications then `exit` against a fresh `State.init` and push the counter past its baseline, and an `Armed` bit saved at entry is erased rather than put back, so a preemption armed before a nested eval is silently dropped. No test pins it, `1f47d6f590`'s message does not declare it, and the package quotes the code as present. This is both a defect and the package showing something the diff does not contain.

## R2 The adjudication table names lines its ids do not hold, again.

site: reviews/proto-region-stack/flags.md, groups "the eval's answer at the boundary" and "reading the innermost region"
quote: `**F13** (`if stack.isEmpty then susp.asInstanceOf[A < S]`), **F42** (`res.asInstanceOf[A < S]`).`

Reproducing the skeleton over the stated range: the two quoted casts are F14 (Eval.scala:122) and F46 (Eval.scala:260). Actual F13 is the `Pending` pattern at Eval.scala:118 and actual F42 is `val cont = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]` at Eval.scala:229. In the next group, "F44, F45 (Eval.scala 242, 248)" are adjudicated as storage-boundary reads of the innermost region, but F44 is `case kyo: Kyo.Handle[EX, AX, Y, T, S2, VX] @unchecked` and F45 the `HandlerContext` pattern: relocated baseline patterns that the table's own moved group already covers under difference (1). F52/F53 (the baseline's `Arrow.Chain` pattern at Eval.scala:273) are swept into the same new-cast group, and F19 (a comment line the script matched on the word "Nothing") is listed among the genuine carrier widenings. The earlier table was blocked for "two entries adjudicated a different line than their id named"; this table records that escape at its foot and repeats it in at least six rows. The verdicts themselves are plausible per site, but an id-addressed table whose ids point at other lines cannot be spot-checked, which is the one thing it is for.

## R3 The twenty-row table measured a tree this range does not ship.

site: reviews/proto-region-stack/evidence.md, Benchmarks header
quote: `Control `31a7b4bde9`, variant `ffc1819ecc`.`

The range under review ends at `1f47d6f590`, which rewrote the eval's driver (the entry path every row runs once per eval), replaced the recursive guard with the loop, and dropped the try/finally. The variant leg predates all of that. The skill: "A row that exercises changed code and was not measured is an unverified claim, never a safe omission." The recovery sweep does cover the final guard's throw path, but nothing covers the final commit against the twenty rows, and review.md's summary ("Benchmarks: the full class on both legs back to back, 20 of 20 rows. Nothing regressed") presents the run as evidence for the change set without saying the leg stops one commit short. Either rerun the legs at `1f47d6f590` (which R1's fix forces anyway) or the package must say which tree the numbers belong to.

## R4 The five differences are six: the own-tag answer resumes with the current context, not the install-time one.

site: kyo-root-impl kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala:216
quote: `loop(r, Arrow.id, Arrow.id, ctx)`

At the baseline the own-tag paths re-entered the interior with the region's install-time context: `region(st, r, Arrow.id, ctx)` (31a7b4bde9 Eval.scala:181) and `region(o._1, o._2, next, ctx)` (:187), where `ctx` is `region`'s parameter and constant per region instance, so context memoizations made inside the interior were dropped at every clause answer. The new code resumes with the loop's current `ctx` (Eval.scala:216 and :224), so they persist. Today this is unobservable because every `SuspendContext.update` in the tree is identity (`def update(v: A) = v`, ContextEffect.scala:47, 69, 95, 121), which is also why no test distinguishes the two; but the protocol point permits a non-identity update, the difference is semantic rather than a retyping, and step 5's claim that "`flags.md` names all five differences" is what makes the moved group auditable. Name it as the sixth, with the argument for why the current-context reading is the honest one, or the completeness claim is false.
