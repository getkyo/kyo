# E2: composition-time inline drive for handleLoopState — REFUTED

Branch `exp-e2-inline-drive`, variant commit `f6722dcbef`, base `26f14ecdd4`.

## What was built

`ArrowEffect.handleLoopState` gained a per-call-site `@tailrec` drive expanded inline: each cycle
answers a suspension of the region's own tag (bare `Suspend`, or `Defer` whose payload is one) by
running the clause with the state in a local, then applying the continuation through `Arrow.apply`'s
own laws (total: a `Step` runs inline, a `Chain`/`AndThen` defers). Every other shape bails into the
unchanged general region (`shared`, seeded with the reached state); a clause that suspends is
dispatched outside the region by one arrow over its outcome that re-enters `shared` to resume (the
landed effectful-clause shape). The drive hands out no continuation, so nothing in it can be
captured or replayed.

The guard question the directive posed ("when is `kyo.cont` directly applicable?") dissolved:
`Arrow.apply` is total at the value level, so applicability never needed a guard. The refutation
came from somewhere else.

## The refutation: composition-time driving breaks the slice contract

976-test suite: **974 green, 2 red**, both in `EvalTest`'s partial-evaluation family:

- `a preemption stop reifies and resumes with handler state` — asserts `100 did not equal Absent`
- `catching guards a stateful region across a park`

The first test composes the region at a `val`, then runs it under `Eval.partial`, expecting the
slice to park mid-loop when the clause posts `Safepoint.stop` at n==10, preserving handler state
across the park. With the drive, the loop completes **at composition time**, before any eval
exists: `partial`'s stop function and the armed poll live in the eval, and a composition site can
neither poll an arbitrary stop function it has never seen nor consume the slot sentinel without
stealing it from the later slice.

Why the old kernel gets away with the same eagerness: its drive integrates preemption directly
(`Safepoint.handle` eval/continue/suspend hooks inside `handleLoop`), so an eager drive is still
sliceable. Kernel2 moved preemption into the eval; **laziness of region combinators is therefore
load-bearing**, and any composition-time answering of suspensions is observationally distinct under
`Eval.partial`. Fast paths specialize laws; this one replaced one.

Repairs considered and rejected:

- Poll the slot per cycle without consuming: handles slot-level stops only; `partial`'s arbitrary
  `stop()` function remains unpollable at composition, leaving a semantic hole exactly where the
  two tests pin.
- Bounded eager drive (k cycles then bail): any k > 0 still answers suspensions before the eval,
  same violation in miniature, and the win shrinks with k.
- Wrap the drive in a `Defer` so it runs under the eval: the payload runs to completion inside one
  node read; the eval's stop is polled between nodes, not inside them, so a mid-loop stop still
  cannot park.

The sound home for a per-answer local-state loop is inside the eval's own dispatch, where armed,
stop and the stack are in hand — that is Option A (register + spill), a different exploration.

## Measurements

Runtime: the refuted variant was measured anyway, labeled as the **unsound upper bound** — the
prize a sound design competes for, never a candidate number.

TBD-RUNTIME-TABLE

Compile time (HandleSites/SuspendSites): not measured. The variant failed correctness first; the
compile cost of a dead design has no decision value. If a sound in-eval variant revives per-site
expansion, measure it there.

## Verdict

**Option B refuted for kernel2.** The mechanism it targeted is real (per-answer state boxing, see
the AutoBoxCacheMax result: kernel2 612→235 µs, old kernel 144→136), but composition-time driving
is not a sound way to reach it in a kernel whose preemption lives in the eval. The upper-bound
numbers below say what a sound design (state in an eval-local register, spilled at every fast-path
exit) can aim for.
