# Rehearsal: bracket-drain (3eb1c6991d..HEAD)

verdict: FAIL
repeats: 1

Read in his order: review.md, flags.md, derivation.md, the full diff, the kernel skill,
rulings.md. The design walk itself holds: every pop site consumes what it returns, the
drain sites match the region-exit law, the pins are substantive and the counts check out
(28 in EffectBracketTest, 4 in the EvalTest owed block, 56 adjudicated flag rows, zero
REMOVE, tree clean, 9 commits in range). The stops below are where he puts the package
down, ordered repeat first, then by severity.

## R1 [REPEAT] Where is the sequence data? Step 8 does not even mention SyncTest.scala.
site: reviews/bracket-drain/review.md:57-80 (the "Edit sequence for the live review")
quote: "8. **Delete `Sync.scala`**, **add `EffectBracketTest.scala`**, **extend `EvalTest.scala`**"
ruling: "A described sequence is not a sequence: `sequence.json` holds the exact text pairs and `sequence.py --verify` proves they reproduce the tip. If applying ever needs improvisation, PACKAGE did not finish." (rulings.md, 2026-08-30)

The package directory holds derivation.md, flags.md, review.md and nothing else; the
precedent package (reviews/proto-region-stack/) carries sequence.json and sequence.py.
The prose sequence already proves the ruling's point: the diff deletes
kyo-kernel/shared/src/test/scala/kyo/proto/SyncTest.scala (158 lines), and no step says
so, so applying the walk as written leaves SyncTest referencing a deleted Sync and the
tree red mid-walk. Fix: produce sequence.json with the exact text pairs, verify it
reproduces the tip, and make the written sequence cover every file in the range.

## R2 The JS and Native rows are not evidence; they are a promise to go measure.
site: reviews/bracket-drain/review.md:95-96
quote: "kernel JS suite | 1489/1489 at 9a9a12ff71; rerun at the tip in flight at package time"

The tip is 2504-vs-2508 different from 9a9a12ff71 by the package's own bytecode table, so
the cross-platform suites have not run against the code under review. The skill is
explicit: "Bring the evidence with the diff... A review that turns into a request to go
measure something has already failed." Fix: finish the JS and Native runs at the tip and
put the numbers in the table before proposing.

## R3 Who calls `owedOf`? Nobody.
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:52
quote: "def owedOf(i: Int): Chunk[Stack.Snapshot] = owed(i)"

Zero call sites in the entire repository, main or test. The derivation's surface list
names it (derivation.md:129) but nothing in the derivation uses it, so the list does not
defend it either. The bar is not "is it harmless", it is "was it required by the
derivation". Fix: REMOVE.

## R4 "Parked by your standing instruction": which instruction? It is not recorded.
site: reviews/bracket-drain/review.md:101
quote: "**Benches: parked by your standing instruction, so the hot-path deltas are disclosed, not measured.**"

The change touches the evaluator, so the skill's own scaling table makes benchmarks
mandatory, and the package waives them by citing an instruction that appears nowhere:
derivation.md records eleven rulings verbatim and dated, and none parks the benches (the
nearest, "A main concern I have is the extra code in the eval loop degrading
performance", points the other way). The package's discipline is that rulings bind
because they are quoted; a waiver this load-bearing cannot be the one paraphrase. Fix:
record the instruction verbatim beside the others, or run the benches.

## R5 "Run strictly in the eval": the settled arm fires it right here, in `handle`.
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/ContextEffect.scala:113-114, contradicted at 153-155
quote: "* and run strictly in the eval; done and release may each fire more than once per"

Three lines below the scaladoc, the settled fast path runs `done(derive(Maybe.empty))`
inline in `handle`, outside any eval, at whatever call site the user wrote. The
observational equivalence is fine; the sentence is false as written, and Handler.scala's
"fires strictly in the eval at the region's settled exit" is accurate only for the
handler hook. Fix: scope the sentence (the hooks never run inside user continuations; the
settled fast path fires done at the handle site itself).

## R6 The derivation says 1535, the review says 1542. One of them is stale.
site: reviews/bracket-drain/derivation.md:156 vs reviews/bracket-drain/review.md:94
quote: "## Pins (all green, 1535/1535 JVM)"

Both files are in front of him and the totals disagree. This is exactly the class of
mechanical claim package-check exists to catch. Fix: update the stale count.

## R7 The Chunk ruling left residue: a "stale list" comment and locals named `l`.
site: kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Stack.scala:47 (also `val l` at 55 and 72)
quote: "// no stale list can survive into the slot's next occupant."

The owed collections became `Chunk` by his ruling ("can we use Chunk instead of List?",
derivation.md), and the comment plus the `l` locals still speak List. Cheap rename:
"stale chunk" (or "stale obligations"), and a name for the locals that is not the
abbreviation of the type that was removed.

## Lines read and accepted, for the record

- `Stack.dump` writing `owed(from - 1)` unguarded: the one call site passes `idx + 1`,
  and a dump is definitionally above an answering entry; the one-sentence defense exists.
- `truncate` resetting owed slots without draining: both call sites take `owed(idx)` the
  line before, and everything above idx is already in the fresh dump; the reset is the
  pool-hygiene protocol the other three arrays already follow (flags F42's verdict).
- The four open questions: each is presented with the chosen-until-ruled semantics
  applied and pinned, not as a menu; the Bind naming fork records the alternative.
- "extent" and "slice" are base-tree vocabulary (Isolate.scala:103, the deleted
  Sync.scala:52), not new terminology.
- The storage-boundary casts (F15, F24, F39, F53-F56) all sit inside the 2026-08-29
  ruling's sanctioned category; no carrier type appeared.
- The at-least-once double-fire, the swallowed discard-drain release failure, and the
  Spent pass-through are each disclosed and pinned, not hidden.
