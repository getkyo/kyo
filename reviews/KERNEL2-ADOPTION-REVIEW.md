# Adoption review: three candidates from the exploration rounds

State: nothing merged. Each candidate is a branch in an agent worktree, its full report under
`reviews/exploration/`, its source diff as a patch beside this file. The adoption gate: your review of
diff + report + flags, then one candidate at a time into the main tree, full both-kernel boards at
`-f 3` on movers between steps.

Goal restated: every row at or under 1.05x of kyo-kernel.

---

## Candidate 1 — F1: the stateful answer step in the generated handler class

**Diff**: `reviews/F1-DIFF.patch` (775 insertions: ArrowEffect wiring, Handler templates + Out + hooks,
Eval fast dispatches, Stack +5, 115 lines of new pins). **Report**: `exploration/EXPLORATION-F1.md`.
**Branch**: `exp-f1-answer-in-class`, final commit `376cf5403c`.

What changes: the clause call and outcome destructuring move from the shared eval (virtual `h.run`,
outcome allocated per answer) into per-call-site generated methods with the clause statically bound;
consecutive settled answers loop with the state in a local; state and branch cross back through `Out`,
a per-stack cell only the eval and the kernel-authored template touch. After the layering ruling, the
template text lives once in `object Handler` as inline members; `ArrowEffect`'s four expansions are
thin wiring.

| row | base | F1 | kyo-kernel | F1/k1 |
|---|---:|---:|---:|---:|
| statefulAnswersPaySuccessor | 597.77 | **90.81 ± 1.12** | 145.28 | **0.63x** |
| statefulTwiceMono / Bi | 1,187 / 636 | **181 / 183** | 292 / 259 | **0.62x / 0.71x** |
| handleLoopAnswersInPlace | 154.34 | **86.93** | 129.86 | **0.67x** |
| handleLoopFusesContinuation (Proto) | 172.31 | **84.65** | n/a | |
| emittingClausesPayRegionRebuild (Proto) | 147.11 | 125.98 | n/a | |

Bi == mono to 1% (morphism-independence by construction, the E4 objection resolved); bands from
bimodal ±160-316 to ±1-2; compile fixtures flat (HandleSites 324→328, overlapping); 981 tests; the two
E2 slice pins, the trace pin, five Out-cell hostile-axis pins all green; 36-row screen zero suspects.
Two representation-contract kernel bugs found and root-fixed en route (done value unnested before
delivery; implicit lift nesting a bail's resuspension).

For sign-off: `Any`-typed answer currency (cell kind disambiguates); erasure-category casts
(inventoried in the report); new `private[kyo]` surface `Handler.Out` / `nextAnswer` / `resuspend`;
literal 128-answer cap. Open proposals, unimplemented pending your ruling: (1) collapse
`answer`/`answers` to one seam method per handler kind; (2) document the immutable sum `Out` encodes
(`Continue | Bail | Suspended | Done`) in its scaladoc. Disclosure: one flip-sweep incident, caught by
outside review, repaired with byte-identical verification, all measurement legs audited
(`b69b7293a5`).

## Candidate 2 — F2-L1: a fold applies by running its first step

**Diff**: `reviews/F2-DIFF.patch` (34 lines). **Report**: `exploration/EXPLORATION-F2.md`.
**Branch**: `worktree-agent-a205e32a91b7ad22f`, commits `f9c3550a39` (L1 + pins), `a2ca534b2d`.

What changes: `AndThen.apply(v)` was `Effect.defer(v, t, cont)` — a 24-byte Defer plus a full eval
round trip per clause resume. It becomes `t(v, cont)`: the eval's round trip for that defer ends in
exactly this call, so it is the same law without the node, budget-governed by the Step's own apply.
The counterpart is pinned as law: `Chain.apply` MUST keep deferring, because its tail may carry a
region whose entry must be installed before the head runs; `AndThen` is immune by type.

| row | base | L1 | vs base | k1 | L1/k1 |
|---|---:|---:|---:|---:|---:|
| trailingMapsStayLinear | 863.5 | **692.0** | **0.80x** | 449,013 | 0.0015x |
| fusionAfterSuspensionRunOnly | 0.99 | **0.81** | **0.82x** | 0.27 | 3.0x |
| fusionAfterSuspension | 219.6 | **197.8** | **0.90x** | 83.7 | 2.37x |
| suspensionFusesContinuation | 99.5 | 101.6 ± 3.5 (-f 3: drift) | ~1.00x | 68.8 | 1.48x |

34-row both-board screen: nothing above 1.04x, movers all wins (stateful 0.89x on this branch too).
976 tests. Also delivered: the cluster's attribution (composition Defers + dispatch round trips +
clause boxing, NOT the AndThen fold; foreign adds a Chain fold/walk per crossing ≈ 25% of that row),
and L2 (flat push) honestly dropped on measurement (compiled 2.4x larger than its target).

## Candidate 3 — F3: a settled eval never enters the interpreter

**Diff**: `reviews/F3-DIFF.patch` (77 lines incl. probe rows). **Report**:
`exploration/EXPLORATION-F3.md`. **Branch**: `f3-small-reds`, commits `0bbe821892`, `41d8ee110b`.

What changes: `.eval` binds its receiver once and matches: a `Kyo` node pays the interpreter, a
settled value unnests in the caller's compilation. Diagnosis was exact: 13.98 B/op = one result box
per settled eval x the fraction of seeds outside the Integer cache, escaping through the non-inline
`Eval.apply` boundary. Result: 12ns/13.98B to 2ns/~0B = **0.22x of kyo-kernel** on the batch probe;
controls flat; 976 green; outside-kyo expansion pinned.

Also delivered, named-not-fixed: `userTypes` 1.12x is `Safepoint.get`'s volatile
`AtomicReferenceArray` slot read, ~18% of every settled row; the lever (plain read on the ownership
check, VarHandle on stop/CAS paths) needs a `Stop`-visibility argument plus concurrency pins. Queued.

---

## Projected board if all three land (composition untested; adoption is sequential, re-measured)

| family | now vs k1 | after |
|---|---|---|
| stateful + handleLoop rows | 3.74x / 1.21x red | 0.63x / 0.67x green |
| evalFixedOverhead | red, unreadable | 0.22x green |
| trailingMapsStayLinear | 0.0016x green | better |
| fusionAfterSuspension / RunOnly | 2.59x / 3.40x red | 2.37x / 3.0x red, improved |
| suspension cluster | 1.36-1.48x red | ~unchanged red |
| foreignCrossings | 2.63x red | ~unchanged red |
| userTypes | 1.12x red | unchanged red |

## Remaining reds, mechanisms named, directions set (round three, not started)

1. Cont cluster + a large share of foreign: per-suspension dispatch round trips + clause-boundary
   boxing. Direction: F1's answer-in-class design applied to `HandlerCont` — both F1 and F2 point
   there independently.
2. `userTypes` and ~18% of every settled row: the Safepoint plain-read lever, specified in F3's
   report; needs the visibility argument and pins.
3. foreign's remainder: the per-crossing Chain fold/walk (a Region cannot ride an AndThen).
4. RunOnly: at the folded-continuation design floor (1,240 B/op vs CPS's 0) after L1. Likely a design
   ruling rather than an optimization.

## Proposed adoption sequence

F3 (trivial) -> F2-L1 (two lines + law pins) -> F1 (largest surface); your diff review before each;
full both-kernel boards, -f 3 on movers, tables shown, between steps.
