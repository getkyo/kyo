# Proto eval performance: the scalar replacement pathology and the probe campaign

All numbers are `ProtoBench`, JVM, `-wi 5 -i 5 -w 1 -r 1`, us/op, run alone on the machine.
`-f 3` where marked (15 iterations), `-f 1` otherwise. Baselines: `bench-boundary-fix` from
`reviews/proto-migration/bench-records.md` in the kyo-root-impl worktree (the old rotation
eval), and `screen-0818-f1-head-kernel.json` (the CPS kernel, `ProtoKernelBench`).

## The landed fix (commit a8cff0a3f8)

The settled HandlerLoop rows ran 2.2x slower than the old proto eval with byte-identical
allocation (480,122 vs 480,121 B/op). The mechanism is C2 scalar replacement of the
clause's `Continue2` inside the fully inlined dispatch lane. Validation chain:

1. Ruled out by single-variable measurement: allocation rate, handler lane order,
   delivery shape, loop method size, the per-op state store (skipping it entirely
   changed nothing), GC write barriers (gap survives ParallelGC), deopt churn
   (stable tiered compilation, zero deopts during measurement), the handleLoop
   Unit-state wrapper.
2. Flag flip: `-XX:-EliminateAllocations` alone took the slow row 361 to 151.
3. Escape store: `stack.scratch = outcome0` (one plain field store into the pooled
   Stack) reproduced the flag numbers with no flags, 358 to 155.
4. Disassembly (hsdis): the slow compilation of `loop$1` contains no `Continue2`
   allocation site; the fast one materializes it at `anon run@21`. Static shape is
   otherwise near identical (649 vs 705 loop-body instructions, same backedges,
   same fences): the eliminated object costs more than the 16 byte allocation it saves.

Fixed-tree results, `-f 3` where it matters:

| row | pre-fix | fixed | old proto | CPS kernel |
|---|---|---|---|---|
| handleLoopAnswersInPlace | 362.7 | 149.0 +-4.3 | 164.6 | 88.8 |
| handleLoopFusesContinuation | 359.0 | 174.5 +-5.6 | 175.4 | 85.6 |
| statefulAnswersPaySuccessor | 162.8 | 152.9 +-1.1 | 221.7 | 116.8 |
| emittingClausesPayRegionRebuild | 67.7 +-2.0 | 76.4 +-5.2 | 114.5 | 79.8 |

Known deviation, stated openly: the fix costs `emittingClauses` +13% (67.7 to 76.4,
both `-f 3`, tight errors). That row benefits from the elimination the store forfeits.
Restricting the store to the settled arm only (Q0 below) did not recover it. The trade
stands at +13% on one row against -59% and -51% on the two pathological rows, and the
emitting row remains 41% ahead of the old proto record. Every other row of the full
class is at or better than both the pre-fix run and the record.

## Probes explored (isolated worktree, one variable per measurement)

Each measured on top of the landed fix (B0). Verdicts:

| probe | change | result | verdict |
|---|---|---|---|
| Q0 | escape store only in the settled Continue2 arm | handleLoop rows hold, emitting 83 +-11 (no recovery) | rejected |
| Q1 | merged delivery `loop(answer, kyo.cont.chain(conts), Id)` | fuses -18%, but stateful +5%, emitting +26% (`-f 3`) | rejected |
| Q1b | Q1 only when `kyo.cont` is Id | no discrimination: affected rows all have Id cont | rejected |
| Q2 | skip `updateState` when state reference unchanged | Unit rows -8%, stateful +17% | rejected |
| Q3 | one-entry (tag, idx) cache in `Stack.find` | regresses everything: suspensionBaseline +21%, emitting +45% | rejected |
| Q4 | extract the cold safepoint park block out of `loop` (the TODO at Eval.scala:52) | `-f 3`: fuses 142.4 +-0.9 (-18%), answersInPlace 142.7 +-0.9 (-4%), stateful 157.9 +-0.9 (+3.3%), emitting 97.6 +-5.8 (+28%) | trade, user's call |

Q4 is the interesting one: a semantically identical 15-line extraction moves four rows
by -18% to +28%. It is not the free cleanup the TODO hoped for. If the fuses and
answers-in-place profiles matter more than stateful and emitting, it is a win; as a
dominance improvement it fails. The Q4 diff is staged in the probe worktree
(scratchpad/proto-probe, `git diff`) if wanted.

## What the campaign established structurally

The dominant phenomenon in this loop is no longer any single hot-path cost: it is
that `loop$1` sits on a JIT compilation cliff. Semantically neutral reshaping
(moving cold code, reordering lanes, merging continuations) moves individual rows
by 5-30% in both directions, per row, reproducibly. Nibbling at the lane trades
rows against each other. The levers that would move the whole surface rather than
redistribute it:

1. REJECTED after review: a HandlerLoop protocol change replacing the `Continue2`
   carrier with write-through state. The state must travel inside the captured
   continuation value: multi-shot replay (the choice shape), Park entries restoring
   at their carried state, and the pending lane rebuilding the region as a fresh
   `Handle` all depend on each captured copy owning its state. A mutable cell
   aliases every copy to one location and breaks replay independence, park/resume,
   and isolation. Two values leave the clause per operation and any value-semantics
   encoding of that is a carrier, so `Continue2` is the semantics, not packaging;
   the correct work was making it cheap (the escape store), not removing it. The
   CPS kernel pays the same carrier, so the remaining 149 vs 89 gap on the
   handleLoop rows has no named mechanism yet; closing it starts from a fresh
   profile, not from this idea.
2. Threading the Safepoint slot through delivery instead of re-fetching per
   `Step.apply`. Small ceiling (a few loads per op), requires an `Arrow.apply`
   protocol change.

## Megamorphic dispatch: the 5x cliff both evals share

ProtoBench has no multi-handler row, so the entire recorded corpus measures the
monomorphic best case. A probe bench (`ScratchPolyBench`, staged in both scratch
worktrees) runs the same 10000 operations through one handler class versus four
distinct effects with four distinct handle sites:

| row | old eval | new eval |
|---|---|---|
| monoContHandler | 94.7 | 80.3 |
| monoLoopHandler | 158.0 | 153.1 |
| polyContHandlers | 453.9 | 406.1 |
| polyLoopHandlers | 780.1 | 764.0 |

Both evals pay the same ~5x once handler and node populations go polymorphic; the
rewrite is slightly ahead on every row. The old design's per-site generated answer
classes did not protect it.

The profile decomposition of the poly row: visible vtable and itable stubs (4.6%),
`Stack.find` inflated to 13% because its `handlers(i).tag` call goes megamorphic,
and the bulk smeared across the loop's shared apply and clause sites as general
inlining loss. Probes against the retail components:

- Per-site dispatch classes: disproven by construction. The HandlerCont lane
  already keeps the clause and continuation application in per-site generated
  code and degrades identically, so moving more code per-site cannot close the
  cliff; the cost is at the loop's shared sites.
- Tag array in the Stack (store `handler.tag` at push, reference-compare in find):
  rejected. Poly rows moved within noise; the monomorphic rows paid for it
  (monoCont +17%, suspensionBaseline +7%).

Standing conclusion: the cliff is the price of one shared interpreter loop
servicing polymorphic node and handler populations, identical in kind for the old
eval. Anything that changes it is a specialization architecture (per-effect-row
drive entry points, profile splitting), not a lane tweak. Two corpus notes for
the record: ProtoBench should gain a permanent shared-dispatch row so this axis
stays measured (KernelBench has sharedHandlerPaysDispatch; ProtoBench does not),
and all recorded numbers, including the old records, are monomorphic best cases.

## State

- Fix committed on the branch as a8cff0a3f8, suite green (1497), `Debugger.enabled`
  left `false` for benching.
- The TODO at Eval.scala:52 is measured (Q4 above), not applied.
- Probe worktree with the Q4 candidate diff: the session scratchpad, `proto-probe`.
- These tables supersede `bench-boundary-fix` as the best recorded numbers for
  every row except emitting, where 67.7 (pre-fix) remains the row's best.
