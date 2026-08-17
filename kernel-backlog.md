# Proto kernel backlog

Queues: open defects, awaiting your ruling, next up, parked, and your source TODOs mirrored
with analysis. Each item carries its own context so it reads without the linked docs; the
docs carry the full designs. Done work is removed once acked. Last update: 2026-08-16.

Branch `worktree-effervescent-painting-backus`, HEAD `29fc30f883`, tree clean, proto suite
126/126, clean batch build green.

## Open defects

### continuationBodiesFuse regressed 4.3%

Context: the row measures a handler clause whose body is a fused map chain, so it exercises
delivery into a continuation that is itself a long strict chain. Confirmed at `-f 3` against
a same-session control: current 28.695 +/- 0.371 us/op against the old design's 27.522 +/-
0.483, error bars clear of each other. Every other row is flat or faster, so this is the one
red left from the SuspendWith arc.

Not yet diagnosed. It surfaced only because the sweep covers all 15 rows; it was never in the
four-row subset the arc was measured against, which is the mistake that produced the
whole-suite rule in the skill. Next step is the ladder, not argument: `-prof gc` first to
decide whether it allocates more, then allocation sites, then the inlining log, before any
change is proposed.

## Awaiting your ruling

### Loop.continue returning `Outcome[...] < Any`

Context: your TODO at `Loop.scala:229` says these methods should have no `< Any` and that
the hack keeps coming back. Directly above the constructors is a design comment recording
that three bare-return designs were built and gated before this shape settled. Plain bare
constructors leave every settled-answer clause site red, because the settled-into-pending
step is a conversion and a conversion blocks expected-type propagation into the type
parameter. Outcome-lifting conversions in the companion fix those sites but cycle dotc's
inference inside map-final clause bodies. Carrying the payload row on `Continue` (`_1: A < S`)
makes every site infer by variance with no conversion, but the field then erases to `Object`
where the current one specializes to a primitive, costing 16 bytes and 2.9x time per settled
iteration in Loop's driver. The same comment explains why `done` is bare while `continue` is
not, so the asymmetry is deliberate rather than sloppiness, and the kernel skill lists this
in its sanctioned-concessions table.

Question: re-litigate it, in which case the work is finding a fifth shape that keeps
inference solving the answer type from the expected type, with the 2.9x figure as the bar any
candidate must clear? Or is the narrower item below what you were pointing at?

### Vestigial type parameter on single-state `continue`

Context: `inline def continue[A, O, S](inline v: A)` declares `S` and never uses it. Call
sites pass it explicitly (`Loop.continue[Int, Int, Any](i + 1)` in LoopTest). Removable
independent of the `< Any` question. The same shape exists in the impl's `kyo/kernel/Loop.scala`.

Proposed: drop `S`, fix the call sites. Say go and it is a small change with a test run.

## Next up

Nothing is in flight. The sweep is complete and the tree is clean.

## Parked (your call to revive)

### The kernel swap

Context: replacing kyo-kernel2's impl with the proto, following kyo-kernel's file and
directory layout. Scoped in full earlier as 29 numbered divergences: verbatim-or-near files
(Safepoint, CanLift, Implicits, Loop), design replacements (Pending's representation, Stack,
EffectTrace, the evaluator, the node algebra), the genuine gaps, the test-suite adoption, and
the platform bits. Item 12 (partial eval) closed when `Eval.partial` was ported. `Arrow` stays
in the `kyo` package per your ruling.

Blocking risk sits in one item: porting `kyo/Kyo.scala`, which became the fifth suspended
compilation unit and broke the dotty clean batch build. Deleting the impl frees suspended
units, so it may fit now; the clean batch build is the gate.

### IOTask integration

Context: designed in full and re-verified (`iotask-kernel2-integration-r2.md`). Now unblocked
on the kernel side since `Eval.partial` is back with the armed gate. Its open rulings when
revived are recorded in that doc.

### Effect.catching, ContextEffect/Context, Isolate

Superseded in part by your source TODOs below, which state the direction you want for each.

## Your source TODOs, mirrored

### Arrow.scala:18 - Identity as a class, measure the type test

Your note: made `Identity` a sealed class so `chain` can test `isInstanceOf`; check if it
helps perf, convert other uses, measure.

Analysis: `eq` compiles to a reference compare against a `getstatic`; `isInstanceOf` on a
sealed one-instance class to a class-pointer load and compare. On paper a wash. It can win
where the JIT statically knows the receiver and folds the type test while the reference
compare still loads the field, and lose on the megamorphic composition sites where nothing
folds. Single-variable A/B, all 15 rows because `chain` is on every path. Queued behind the
regression above.

### Arrow.scala:96 - rename Arrow.Eval to Park

Analysis: mechanical, and it removes a real collision between `Arrow.Eval` the node and `Eval`
the evaluator. Surface it touches: `reify` in the drive builds it, `Eval.partial` returns it,
`EffectTrace`'s drain walk matches it, `Stack.pushAll` consumes its spans. Renaming `reify` to
`park` completes the theme.

### Arrow.scala:104 and :141 - type members instead of type parameters

Your note: too much noise in Eval from `I[_], O[_], E, A, X` on the suspensions and
`E, A, B` on Handle and the Handler subclasses.

Analysis: feasible, with precedent in the same file (`Step` already carries `type X`, and
`Transform` fixes it). The noise is real and measurable: 17 sites in Eval spend 765 characters
purely on these type arguments. Suspensions can keep `B` and `S` as parameters and move
`I, O, E, A` to members; the contravariant row means a construction site's narrower `cont`
still conforms. `Handle` keeps `+C` and `-S` (its only variant parameters) and moves `E, A, B`
to members, which is legal because `E & S` appears in member positions rather than in the
extends clause. The cost lands at roughly 12 construction sites in ArrowEffect, plus the
question of whether `handler` needs refinement typing to stay aligned with its `Handle`, or
whether the existing evaluator casts already absorb it. Verification must include the clean
batch build, since anonymous classes with type members expand at the same inline sites that
govern the suspension equilibrium.

### Eval.scala:6 and Safepoint.scala:8 - narrow to private[kernel]

Your note: the external surface is `<.eval` / `evalNow` and `ArrowEffect.*`.

Not yet analyzed. Straightforward in principle; the question is which test files reach into
`Eval` and `Safepoint` directly today and whether they move to the public surface or become
`private[kernel]` themselves.

### Eval.scala:126 and :138 - encapsulate stack access

Your note: the handler lookup should be a higher-level Stack API, and the k-fold looks like a
stack operation too.

Analysis: both are the same shape. The lookup is `find` plus an indexed read plus a cast to
`Handle`; a `Stack.handlerAt(tag, base)` returning the handle and its index removes the cast
from Eval entirely. The fold walks entries from `i + 1` to the top chaining them, which is a
`Stack.foldFrom(i)` and appears three times in the file with small variations. Both reduce the
evaluator's line count and move the storage casts to the storage boundary, which matches how
`Stack.apply` and `pop` were already treated.

### Eval.scala:188 - while loops to @tailrec, avoid vars

Not yet analyzed. The loop carries `cur`, `running`, and the armed poll; a tailrec form takes
them as parameters. The open question is whether the JIT treats the tailrec form identically,
since this is the hottest method in the kernel and it already fails to inline at ~1500 bytes.
Needs the full-suite bracket.

### Pending.scala:13 - move Nested to Nested.scala

Not yet analyzed. Mechanical, but it interacts with the opaque-transparency rule: `Nested`
currently sits inside the file where `<` is defined, and the skill records that the alias is
transparent in its companion and not in sibling objects.

### Pending.scala:35 - why isn't this in Implicits?

Not yet analyzed.

### ArrowEffect.scala:10 - ContextEffect as an ArrowEffect carrying a map

Your note: considering making it an ArrowEffect that keeps a map with all the contextual
values. Not yet analyzed. This supersedes the parked ContextEffect item and interacts with the
threaded-context designs already on disk.

### EffectTrace.scala:19 - make it KyoException

Your note: plan to make this KyoException and update the codebase; launch an opus agent to
consider the swap and its implications by surveying existing KyoException use. Not yet run.

### Effect.scala:21 - explore Effect.catching

Your note, recorded verbatim because it sets the terms: explore the design, do not
overengineer, think in terms of composition, see how the old kernel does this, exploration
rather than a quick solution. Not yet run.

## Reference numbers

**Full sweep, all 15 rows.** Isolated bench worktree, `-f 1`, 5 warmup + 5 measurement
iterations of 1s, JVM `-Xms3G -Xmx4G`. Control `36b41336fb` (pre-SuspendWith), current
`d85ee6821f`. Design markers verified on both legs: current `SuspendWith=4 dispatchInline=4
foldedFix=1 partial=2`, control all zero. Drift band on this machine is 3-4%, so only rows
outside that are results.

| | row | mode | cnt | old (36b41336fb) | current (d85ee6821f) | delta |
|---|---|---|---|---|---|---|
| green | `trailingMapsStayLinear` | avgt | 5 | 504.70 +/- 23.60 | 295.25 +/- 47.52 | -41.5% |
| green | `suspensionBaseline` | avgt | 5 | 97.40 +/- 3.41 | 88.79 +/- 1.75 | -8.8% |
| green | `statefulAnswersPaySuccessor` | avgt | 5 | 126.25 +/- 12.10 | 118.16 +/- 11.92 | -6.4% |
| flat | `uncachedValuesPayBoxingOnly` | avgt | 5 | 35.01 +/- 4.63 | 34.05 +/- 0.44 | -2.7% |
| flat | `suspensionFusesContinuation` | avgt | 5 | 41.59 +/- 8.55 | 40.52 +/- 1.10 | -2.6% |
| flat | `idleHandlerAddsNothing` | avgt | 5 | 34.53 +/- 0.68 | 34.18 +/- 1.05 | -1.0% |
| flat | `nestedPayloadsUnwrapInMaps` | avgt | 5 | 5.94 +/- 0.29 | 5.95 +/- 0.32 | +0.2% |
| flat | `emittingClausesPayRegionRebuild` | avgt | 5 | 81.80 +/- 15.97 | 82.09 +/- 4.66 | +0.4% |
| flat | `fusionPastBudgetPaysRescuesOnly` | avgt | 5 | 34.51 +/- 0.65 | 34.69 +/- 0.76 | +0.5% |
| flat | `deepRecursionPaysRescuesOnly` | avgt | 5 | 51.01 +/- 1.10 | 51.42 +/- 3.13 | +0.8% |
| flat | `handleLoopAnswersInPlace` | avgt | 5 | 87.75 +/- 1.04 | 88.60 +/- 6.49 | +1.0% |
| flat | `fusionAllocatesNothing` | avgt | 5 | 0.55 +/- 0.01 | 0.56 +/- 0.04 | +1.6% |
| flat | `handleLoopFusesContinuation` | avgt | 5 | 84.59 +/- 1.79 | 86.24 +/- 6.32 | +2.0% |
| RED | `continuationBodiesFuse` | avgt | 5 | 27.15 +/- 0.67 | 28.59 +/- 1.48 | +5.3% |
| artifact | `evalFixedOverhead` | avgt | 5 | 0.005 +/- 0.001 | 0.006 +/- 0.001 | below resolution |

Follow-up on the two flagged rows, confirmed at `-f 3` (15 iterations, same bracket):

| row | old | current | delta | verdict |
|---|---|---|---|---|
| `continuationBodiesFuse` | 27.522 +/- 0.483 | 28.695 +/- 0.371 | +4.3% | real, error bars clear; open defect above |
| `evalFixedOverhead` | 0.006 +/- 0.001 | 0.006 +/- 0.001 | 0.0% | artifact confirmed, no change |

Parked experiment branches: `parked/merge-hoisted-currency` (Suspend-as-Transform with
currency hoisted out of the protocol apply), `parked/partial-eval-bare-stop` and
`parked/partial-eval-armed` (the two poll variants).
