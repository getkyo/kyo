# Analysis of the seven in-tree TODOs

Committed in 7a98648f58, analyzed against the tree as of 345a4f5bf3 (burst landed).
No changes made; this is the review. Suggested execution order at the end.

## 1. Eval, Defer arm: "this isn't hot code, how about we extract to a separate method?"

The safepoint park block (build parked, debugger exits, `Kyo.Park(parked, stack.snapshot())`)
runs only when armed and stopped; it is cold and the extraction is semantically trivial.
The nested method captures `stack` from the apply scope; the call from the Defer arm is a
plain call, so the loop's `@tailrec` is unaffected.

The caution is the compilation cliff: this exact extraction was probed pre-burst (Q4) and
moved hot rows both ways (fuses -18 percent, emitting +28 percent) purely by reshaping
`loop`'s inline budget. That data is stale: with the burst, hot rows spend their time
inside per-site `answers` methods and the loop body executes orders of magnitude less
often, so the sensitivity is likely much lower now, but that is unverified. Do it for the
readability it buys, gated by one guide-row bench pass. It also produces the shared
Park-building helper that TODO 6/7 would want to call.

## 2. Eval, crossing continuation: "why pre-chain it?" (`val inner = kyo.cont.chain(contA.chain(contB))`)

The Step is a value that escapes into continuations and replays, so it must capture the
complete pending continuation. Pre-chaining is one way to capture it; it is not required.
The real answer to "why" is: only because the resume site currently takes a single
continuation slot. TODO 3 removes the reason.

## 3. Eval, crossing resume: "can't this be defer(v, kyo.cont, contA.chain(contB))?"

Yes. `Effect.defer(v, contA', contB')` evaluates value then contA' then contB', so
`defer(v, kyo.cont, merged)` composes identically to `defer(v, kyo.cont.chain(merged), Id)`.
(`andThen` does not exist on Arrow; `chain` is the spelling.) The win is skipping the outer
`chain`, which allocates a Chain node whenever `kyo.cont` is not Id (the fused `suspendWith`
shapes); for bare suspensions `kyo.cont` is Id and the current code already pays nothing.
Small, correct, and it answers TODO 2 by deleting `inner`. Gate with the crossing rows
(emitting) since this is the Park path.

## 4. Stack API: "all access is indexed now, refactor to stack.handler(idx) etc."

Almost true: the dispatch is fully indexed, but three sites still use the top-of-stack
forms (`stack.handler`, `stack.state`, `stack.cont` in the settled arm and in `recovered`),
plus `Debugger.onForeign`. Two options:

- Full unification: `handler(i)`, `state(i)`, `continuation(i)`, `setState(i, v)`, and the
  top sites spell `stack.depth - 1` explicitly. Honest but noisier at the settled arm.
- Indexed rename plus keep the three top accessors as one-line sugar.

Either is mechanical, private[kernel] only, suite-gated, no perf surface. StackTest
follows the same rename.

## 5. Eval: "explore if we can not have HandlerContOp so we reduce the size of the loop"

The load-bearing user is `ArrowEffect.Mask`: its clause needs the operation as a
computation (`X < E`) because Mask is generic over `E <: ArrowEffect[?, ?]` with unknown
I and O, so it cannot call the typed `suspend` to rebuild the operation itself. That is
the reason HandlerContOp exists with I and O erased from its signature.

It can still be removed: move the operation construction from the eval lane into the
`handleContOperation` glue, whose per-site HandlerCont builds the erased suspension the
same way the eval does today (`new Kyo.SuspendArrow[AnyK, AnyK, ...]` with the tag cast,
the `resuspend` precedent). The trade is one protocol class and one eval lane deleted
against two representation-assertion casts in one glue method. The loop shrinks by a lane,
which is cliff-relevant, and the per-dispatch allocation is unchanged (the suspension node
is built either way). Worth doing; ArrowEffectTest's handleContOperation coverage and the
Mask tests are the gate.

## 6 and 7. Isolate.Contextual: "Transform should have the snapshot of the stack" and "capture wraps the computation with Park"

This is the design item of the batch, and the direction is consistent with the laws
already pinned: Park entries restore at their carried state, and IsolateTest pins that a
crossed binding resumes at its captured value. Wrapping the captured computation as
`Kyo.Park(v, snapshot)` would make contextual regions survive a fiber boundary with
exactly those semantics, and `snapshot()` already copies the state slots, so post-capture
mutation on the origin side cannot leak in.

Three things block a direct implementation:

- Access: the stack is eval-local (borrowed in `Eval.apply`); `Isolate.Contextual` runs
  outside the machine and cannot reach it. Either the boundary APIs run `capture` inside
  the machine (an entry point that has the stack in hand, the way `Eval.partial` does), or
  the machine answers a capture request during evaluation. A new kernel node kind or
  concrete effect for this was already ruled out once; the entry-point shape avoids it.
- Scope: which entries belong in the capture? The full stack includes arrow handler
  regions whose continuations must not be duplicated across fibers; the contextual subset
  (HandlerContext entries) is what the TODO wants. A filtered snapshot (contextual entries
  only) matches the Park install path, which already handles context entries specially.
- Open rulings it depends on: the update lane (write-through versus threaded decides what
  a post-capture state write means for the fork), and fork/join on HandlerContext (the
  dormant `fork`/`join` methods are exactly the hooks a capture/restore cycle would call;
  the parked Binding-trait idea is the same design space). Settling those first prevents
  building capture semantics twice.

Recommendation: treat 6 and 7 as one design campaign with the update-lane and fork/join
rulings as prerequisites; sketch the entry-point shape before writing code.

## Suggested order

1. TODO 3 (and 2 with it): small, correct, measurable.
2. TODO 4: mechanical rename, suite-gated.
3. TODO 5: protocol shrink, one glue rewrite, tests gate.
4. TODO 1: extraction, one bench pass to confirm the cliff stopped caring.
5. TODO 6 and 7: design campaign after the update-lane and fork/join rulings.
