# F1: the stateful answer step in the generated handler class

Branch `exp-f1-answer-in-class`, base `95b92ecc7f`, stage 1 `7632c1c41c`, stage 2 `dcbbe62463`.

## Design as implemented

**Stage 1.** `HandlerLoopState` gains `answer[X](state, input, out)`: the clause call and the
`Outcome2` destructuring, which the eval used to do in shared code across a virtual boundary. The
`handleLoopState` expansion overrides it with the clause statically bound, so the outcome is born
and consumed in one per-call-site compiled method; a generic body keeps bespoke handlers and the
companion re-wrap working, and the re-wrap delegates so a clause suspension's re-entry keeps the
specialized body. The answer crosses as the return value; the new state and the branch taken cross
through `Handler.Out`, a per-stack cell only the eval and the generated method touch. The cell's
constants are literals in the template because a nested-object selection does not resolve at
expansion sites outside `kyo`; the eval reads them by name.

**Stage 2.** `answers[X](state, input, k, out)`: while the clause's outcome is settled and the
region's next step is a same-tag suspension with an immediately applicable continuation, loop
locally with the state in a var, so the per-answer box dies young instead of escaping through the
state slot. The eval enters this path only when the handler is at the top with at most one plain
entry above it (a `Region` entry forces the general path, keeping it visible to the scans), and
hands that entry in as the continuation. Two kernel-side hooks carry the shapes the template cannot
name: `nextAnswer` (same-tag decompose; reports a deferral whose by-name payload it had to read, so
nothing runs twice) and `resuspend` (rebuilds the suspension a bailing loop still owes). Every bail
commits the state to the cell first; the capped bail returns through `Effect.defer` so the eval's
Defer arm still polls armed/stop, the same way fused map chains reach the poll. Cap: 128 answers
per entry, comparable slice granularity to the 512-step fusion budget.

## Slicing soundness (the E2 refutation, addressed)

E2 failed because composition-time driving answers suspensions where no eval exists. This loop runs
at eval time, from the eval's own dispatch, and never outruns the eval's governance: it cannot
park, but it is bounded, and both bail shapes (capped, composed) route the continuation back
through the eval where armed/stop are polled. The two pins E2 broke are green:

- `a preemption stop reifies and resumes with handler state`
- `catching guards a stateful region across a park`

Full suite: 976 green at both stages, clean batch builds, including the `outsidekyo` expansion pin
that exercises `handleLoopState` end to end from a foreign package (the accessibility mechanism for
`Out`, the hooks, and the template writes).

## Stage 1 three-way (`-f 2 -wi 5 -i 5 -prof gc`, same session, bracket via restore-flips)

| row | base µs (B/op) | stage1 µs (B/op) | old kernel µs (B/op) |
|---|---|---|---|
| `KernelBench.statefulAnswersPaySuccessor` | 531.94 ± 162.02 (798,124) | 622.82 ± 33.60 (798,124) | 146.56 ± 10.44 (1,040,140) |
| `ProtoKernelBench.statefulAnswersPaySuccessor` | 602.64 ± 5.21 (798,124) | 610.26 ± 3.30 (798,124) | n/a |
| `KernelBench.handleLoopAnswersInPlace` | 155.69 ± 2.19 (640,137) | 154.64 ± 0.76 (640,137) | 127.74 ± 2.14 (960,136) |
| `KernelBench.suspensionBaseline` | 179.12 ± 1.71 (640,137) | 180.00 ± 1.76 (640,137) | 122.22 ± 2.67 (560,081) |
| `KernelBench.fusionAfterSuspensionRunOnly` | 0.90 ± 0.01 (1,264) | 0.89 ± 0.01 (1,264) | 0.27 ± 0.00 (0) |
| `KernelBench.trailingMapsStayLinear` | 840.89 ± 26.46 (2.56M) | 845.33 ± 35.07 (2.56M) | 540,556 ± 126,887 (1.60G) |

**Stage 1 alone is neutral-to-slightly-negative on the monomorphic row** (Proto +1.3% outside
bands; KernelBench inside its bimodal band), and allocation is byte-identical — because in a
monomorphic benchmark the JIT already elides the `Continue2` (E4's mono column showed the same
798,124 B/op). Stage 1's target is the bi/megamorphic case, measured in the stage-2 bracket via the
`statefulTwiceMono`/`statefulTwiceBi` rows pulled in from E4.

## Stage 2 three-way (`-f 2 -wi 5 -i 5 -prof gc`, same session)

| row | base µs (B/op) | stage2 µs (B/op) | old kernel µs (B/op) |
|---|---|---|---|
| `KernelBench.statefulAnswersPaySuccessor` | 596.38 ± 4.47 (798,124) | **101.46 ± 0.51 (643,273)** | 143.41 ± 5.57 (1,040,140) |
| `ProtoKernelBench.statefulAnswersPaySuccessor` | 530.24 ± 129.20 (798,124) | **94.41 ± 0.64 (643,273)** | n/a |
| `KernelBench.statefulTwiceMono` | 1,008.12 ± 315.86 (1,596,247) | **203.59 ± 0.84 (1,286,545)** | 291.04 ± 2.44 (2,080,274) |
| `KernelBench.statefulTwiceBi` | 640.29 ± 176.74 (2,076,292) | **203.51 ± 1.32 (1,286,545)** | 259.35 ± 2.71 (2,396,242) |
| `KernelBench.handleLoopAnswersInPlace` | 154.73 ± 2.00 (640,137) | 154.58 ± 0.65 (640,137) | 127.40 ± 2.72 (960,136) |
| `KernelBench.suspensionBaseline` | 182.02 ± 7.07 (640,137) | 179.38 ± 0.91 (640,137) | 122.16 ± 2.85 (560,081) |
| `KernelBench.fusionAfterSuspensionRunOnly` | 0.90 ± 0.05 (1,264) | 0.90 ± 0.02 (1,264) | 0.27 ± 0.00 (0) |
| `KernelBench.trailingMapsStayLinear` | 844.69 ± 14.91 (2.56M) | 834.58 ± 11.89 (2.56M) | 532,424 ± 132,960 (1.60G) |

The stateful row lands at **0.70x the old kernel** (101 vs 143), `statefulTwiceBi == statefulTwiceMono`
to 0.1% with byte-identical allocation: the morphism dependence is structurally gone, and the error
bands collapse from the base's bimodal ±129-316 to ±0.5-1.3. Allocation drops 154,851 B/op = ~15.5 B
per answer: the state box dying young.

## Stage 3 and the two template-coverage fixes (final variant `95c0e368a5`)

Stage 3 gave `HandlerLoop` the same machinery; two follow-up defects were found by pins and fixed:
`handleLoopWith`/`handleLoopStateWith` lacked the template overrides, so their rows paid the fast-path
toll with the generic fallback (`handleLoopFusesContinuation` +50% until fixed), and a throw inside
the applied continuation escaped without the continuation recorded, losing the body's frames from the
trace (caught by "a fused region names the body, then the region"). Final three-way, base and k1 legs
from the same session's bracket 3:

| row | base µs (B/op) | final µs (B/op) | old kernel µs (B/op) | final/k1 |
|---|---|---|---|---|
| `KernelBench.statefulAnswersPaySuccessor` | 597.77 ± 6.07 (798,124) | **101.43 ± 1.21 (643,273)** | 145.28 ± 4.95 (1,040,140) | **0.70x** |
| `ProtoKernelBench.statefulAnswersPaySuccessor` | 563.93 ± 93.39 (798,124) | **96.93 ± 0.71 (643,273)** | n/a | |
| `KernelBench.statefulTwiceMono` | 1,187.19 ± 7.46 (1,596,248) | **202.94 ± 0.54 (1,286,545)** | 292.03 ± 5.44 (2,080,274) | **0.69x** |
| `KernelBench.statefulTwiceBi` | 635.51 ± 168.99 (2,076,292) | **203.30 ± 2.21 (1,286,545)** | 259.44 ± 1.43 (2,396,242) | **0.78x** |
| `KernelBench.handleLoopAnswersInPlace` | 154.34 ± 0.24 (640,137) | **87.36 ± 0.39 (642,009)** | 129.86 ± 4.53 (960,135) | **0.67x** |
| `ProtoKernelBench.handleLoopFusesContinuation` | 172.31 ± 2.80 (640,153) | **83.94 ± 0.68 (642,025)** | n/a | |
| `ProtoKernelBench.emittingClausesPayRegionRebuild` | 147.11 ± 1.26 (184,313) | 125.98 ± 3.31 (168,297) | n/a | |
| `KernelBench.suspensionBaseline` | 179.25 ± 0.78 (640,137) | 184.07 ± 5.42 (640,137) | 121.82 ± 3.05 (560,081) | 1.51x |
| `KernelBench.foreignCrossingsPayRotation` | 830.92 ± 17.84 (1.76M) | 857.69 ± 11.31 (1.76M) | 312.11 ± 4.09 (1.68M) | 2.75x |
| `KernelBench.fusionAfterSuspensionRunOnly` | 0.89 ± 0.01 (1,264) | 0.90 ± 0.01 (1,264) | 0.27 ± 0.00 (0) | 3.36x |
| `KernelBench.trailingMapsStayLinear` | 832.17 ± 10.15 (2.56M) | 865.62 ± 12.09 (2.56M) | 534,976 (1.60G) | 0.0016x |

## Full-board screen

Both boards at `-f 1 -wi 5 -i 5 -prof gc` on the final variant, all 36 shared rows compared against
the campaign's same-day `-f 2` boards of the identical base code (cross-session, flagged as such; the
same-session base legs re-ran and confirm): **zero suspects**. Every row inside the drift band except
the movers, all of which are wins; `foreignCrossingsPayRotation` at 1.04x is inside noise and never
takes the fast path (its regions are `handleCont`).

## Compile time

`CompileBench.newKernel` (fixture compile via dotc against the live kernel2 classes), base vs final,
same session, mutex-serialized:

| fixture | base | final |
|---|---:|---:|
| HandleSites | 349.98 ± 13.46 ms | 330.82 ± 43.60 ms |
| SuspendSites | 258.04 ± 16.21 ms | 264.97 ± 48.32 ms |

No measurable cost: both deltas are inside overlapping intervals. The per-site template growth
(answer plus the answers loop, both handler kinds, four expansion sites) does not move these
fixtures outside noise.

## Escape analysis of `Out`

The cell is interpreter mutability, so the discipline demands each write/read pair carry its ordering
guarantee, not a construction argument. Every pair, exhaustively:

| write | writer | read | ordering guarantee |
|---|---|---|---|
| `kind` (+ `state`) on a single answer | `answer` template/generic, before its return | dispatcher, same frame, immediately after the virtual call returns | consumed before the dispatcher computes the value the eval loops on; `kind` is written exactly once on every path before any return |
| `kind`, `state`, `cont` on every loop bail (poll, clause-suspended, answer-is-Kyo, cap, decompose-fail, done) | `answers`, the statements immediately before each `return` | dispatcher, same frame | same as above; `cont` is pushed back (or Id-dropped) before any dump or attach can walk the stack |
| `kind`, `state`, `cont` on a throw | the template `catch`, before the rethrow (both the clause try and the continuation-application try) | the dispatcher's `catch`, before `attachThrow` | the slot and the entry are made current before the trace walks; pinned by "a throw after settled answers recovers with every commit already made" and the fused-region trace pin |
| `input`, `cont` from `nextAnswer` (case 1) | the kernel hook, during the template's iteration | the template's next two statements, same iteration | cell values never survive an iteration: consumed into the loop's locals immediately |
| `input` from `nextAnswer` (case 2: rebuilt deferral) | the hook | the template's `return out.input`, same iteration | the by-name payload was read once and the rebuilt node is the single carrier of it from then on |

Global properties, each with its pin:

- **Consume-before-reentry.** The eval never re-enters its loop between a cell write and the read:
  writes happen during the dispatcher's call into the handler; reads are the dispatcher's next
  statements; `loop()` resumes only after the dispatcher's value exists, at which point every field is
  dead until the next dispatch rewrites `kind` first.
- **Per-stack isolation.** The cell lives on the `Stack`; a nested or concurrent eval borrows a
  different stack and therefore a different cell. Pin: "a clause can run a full eval of its own
  mid-loop".
- **Multi-shot.** Captures and parks carry entries and state slots, never the cell; by the time a
  capture is applied, the dispatch that used the cell has fully consumed it, and the applying eval's
  own dispatches write before reading. Pins: "a capture across the fast path is multi-shot, including
  across threads"; "a park taken mid answer loop resumes in a fresh full eval".
- **User code cannot reach it.** The clause receives its declared `(state, input)` arguments only; the
  cell is passed exclusively through kernel-authored template code. Pin: "a clause keeps only the
  values it was given" (stored arguments are plain values, undisturbed by later evals).
- **Never the only copy.** `state` in the cell is en route to the slot or to region end; `cont` and
  `input` hold references also reachable from the value being returned or the entries.
- **The stale-slot window.** While `answers` loops, the slot is stale by design and the local is
  truth; every exit path, including the two exceptional ones, commits before the eval proceeds. The
  five pins above are exactly the observers of that window.

All five review pins pass (981 total, zero failures); no finding to report from the hostile axes.

## The restructure: the templates move to the eval layer

Post-review ruling: the answer bodies were eval scope leaking into the combinator surface, duplicated
four ways. They are now inline `private[kyo]` templates on `object Handler` (`answerStep`,
`answersLoop`, `answerStepState`, `answersLoopState`), beside the cell and hooks whose protocol they
speak; the four expansions are thin wiring passing the clause as an inline parameter. Each call site
still emits its own statically bound copy: the mechanism is untouched, the text has one home. The
loops became flag-driven because an inline body cannot return early. Clean batch build green, so
`Handler` joining the inlined-from set holds the CanLift suspension equilibrium.

Equivalence bracket, pre-restructure (`b69b7293a5`) vs restructured (`4a99a0ead4`), same session:

| row | pre µs | post µs | ratio | B/op |
|---|---:|---:|---:|---|
| `KernelBench.statefulAnswersPaySuccessor` | 102.23 ± 0.91 | **90.81 ± 1.12** | 0.888x | identical |
| `ProtoKernelBench.statefulAnswersPaySuccessor` | 96.38 ± 0.68 | **87.85 ± 1.03** | 0.911x | identical |
| `KernelBench.statefulTwiceMono` | 202.88 ± 1.99 | **181.38 ± 1.28** | 0.894x | identical |
| `KernelBench.statefulTwiceBi` | 202.27 ± 0.80 | **183.23 ± 2.39** | 0.906x | identical |
| `KernelBench.handleLoopAnswersInPlace` | 87.20 ± 0.67 | 86.93 ± 1.77 | 0.997x | identical |
| `ProtoKernelBench.handleLoopFusesContinuation` | 83.87 ± 0.81 | 84.65 ± 1.13 | 1.009x | identical |

The restructure is measurably better on the stateful rows, not merely equivalent: the flag-driven
loop compiles to a tighter body than the early-return form. Primary row lands at 90.8 µs against the
old kernel's ~145: **0.63x**. Compile fixtures re-run in the same bracket: HandleSites 324.19 ± 49.93
pre vs 327.59 ± 32.74 post, SuspendSites 253.62 ± 29.86 vs 255.84 ± 36.30; fully overlapping, no cost.

## Incident during finalization: a mid-flight commit swept the variant

The flip-sweep accident the skill documents, committed twice: the report commit ran its add while
the backgrounded compile bracket held the tree at base (sweeping the variant sources), and the
review-gate commit ran during the screen bracket's base flip (recording the pins over a base HEAD).
Caught by outside review of `git show HEAD`; repaired in `b69b7293a5` by `git restore` from the
variant commit, verified byte-identical, full suite plus pins re-run green against the restored
tree. Measurement audit: every bench leg in this report predates the sweep or was flipped by its own
script, so all labels are accurate; the only casualty was the same-session base screen, which had
already failed on the no-match retry and produced no data. Standing rule adopted: never commit while
a bracket script is in flight, and verify variant markers in the tree before any commit that follows
a flip.

## Open items for the review (not implemented, awaiting a ruling)

1. **One seam method per handler kind.** `answer` and `answers` could collapse into a single
   `answers`-shaped seam (the single-answer form is the loop with `n = 1` and no continuation), which
   would halve the abstract surface on `HandlerLoop`/`HandlerLoopState` and remove the generic
   fallback's split-brain (today a bespoke handler must be taught two methods). The cost is that the
   general dispatch path (`pos > 1`, region-adjacent) would carry loop machinery it never uses, and
   the two protocols genuinely differ in who owns the continuation. Proposal only; measured neither
   way.

2. **Document the equation `Out` encodes.** The cell is the mutable encoding of an immutable sum the
   answer step conceptually returns: `Answered = Continue(state, value) | Bail(state, cont, next) |
   Suspended(state, cont, clause) | Done(value)`. Writing that sum in `Out`'s scaladoc would make the
   kind/field correspondence checkable by reading (each kind names its constructor, each field its
   argument, `null` its absence) and give any future variant a specification to diff against. Doc
   change only; no code.

## Flagged for sign-off

- `answer`/`answers` return type is `Any` (the eval's currency; the three branches only the cell's
  kind disambiguates). Alternative was three virtual methods per answer.
- Casts, all erasure-forced at the storage/erasure boundary: `out.state.asInstanceOf[StateX]` and
  the `ran.asInstanceOf` re-typings in the eval's dispatch (same category the dispatch already
  carried); `Tag` erasure at the hook boundary (`effectTag.erased`); the `AnyK`/`Nothing` erased
  `Suspend` shapes inside the two hooks; `stack.pop().asInstanceOf[Arrow[Any, Any, Any]]`.
- New private[kyo] surface on `object Handler`: `Out` (4-field cell), `nextAnswer`, `resuspend`.
  All reached from expansions via the established accessor mechanism, pinned by `outsidekyo`.
- The stage-2 iteration cap (128) is a literal in the template.

## Risks

- The fast path requires `pos <= 1`; deeper pending runs (accumulated `AndThen` entries above the
  handler at dispatch) take the general path. The hot pattern in the rows measured is pos 1.
- `handleLoopStateWith` carries stage 1 only (generic `answers` fallback); extending it is
  mechanical if adopted.
- The bimodal JIT behavior of the base (E4) means single-fork comparisons on this row are
  unreliable; all claims here are `-f 2` with both forks reported, and the bi rows are the
  stability check.

## Verdict

**Adopt.** The per-call-site answer machinery takes every targeted red row past the old kernel:
`statefulAnswersPaySuccessor` 3.7x red to **0.63x green** (90.8 vs ~145 µs), `handleLoopAnswersInPlace`
1.21x to **0.67x**, `handleLoopFusesContinuation` to **~84 µs** (half of base), morphism-independence
proven by `statefulTwiceBi == statefulTwiceMono` at byte-identical allocation, error bands collapsed
from bimodal ±160-315 to ±1-2. Controls flat; the full-board screen over 36 rows shows zero suspects;
compile time unmoved; the slice contract, the trace contract, and the five hostile-axis pins for the
cell all hold, 981 tests green. The two bugs found on the way (early unnest of the done value, the
lift nesting a bail's resuspension) were both representation-contract violations caught by existing
pins, fixed at root. Remaining reds on the board (`suspensionBaseline` ~1.5x, `foreignCrossings`
~2.75x, `fusionAfterSuspensionRunOnly` 3.36x) belong to the `handleCont` dispatch family, which this
exploration deliberately did not touch: same design direction, separate campaign item.
