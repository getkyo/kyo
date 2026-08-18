# Handler as an Arrow in the kyo.proto evaluator

Status: proposal on the owner's `Eval` rewrite (2026-08-18, uncommitted in
`kyo-kernel2/shared/src/main/scala/kyo/proto/{Eval,Arrow,Kyo,ArrowEffect,Pending}.scala`).
Ruled by the owner: **option 2 below** (state stays in the stack's `states` array; `dump` keeps
one special case for `HandleLoopState`). This document is the record of the reasoning and the
list of what has to hold, for a held-out review before the change is written.

## 1. The evaluator as it stands

`Eval.apply[A, S](v: A < S): A < S` borrows a `Stack` from a per-thread pool (each evaluation has
its own; no `base`), runs `loop`, releases the stack empty.

`Stack`: one array `entries: Array[Arrow[?, ?, ?] | Kyo.Handler[?, ?, ?, ?]]`, in the order they
apply, innermost on top, and `states: Array[Maybe[Any]]` beside it (a handler's state, `Absent` for
the stateless ones). `push(arrow)` flattens an `Arrow.Chain` (`b` under `a`) and does not store
`Arrow.Id`. `push(handler)` stores the handler with `Present(initialState)` for `HandleLoopState`.
`find(tag)` scans down for the innermost handler answering the tag. `dump(pos)` folds the entries
above `pos` into one right-deep arrow (`f.chain(acc)` walking up) and cuts the stack to `pos + 1`,
leaving the handler at `pos` in place; a handler inside the dumped range is `???` today.

`Arrow[-A, +B, -S] extends (A => B < S)`: `apply(v: A): B < S` (strict on a settled value) and
`apply(v: A < S2, next)`; `Arrow(f)` is the one factory (the strict arm runs under the safepoint
budget and defers past it); `Chain.apply(v, next) = Kyo.Defer(v, this, next)`.

`loop`:
- `Defer`: push `contB`, push `contA`, loop on the value.
- `Suspend`: push the operation's own `cont`, `find` the handler, then per kind:
  `HandleCont` dumps the interior (which now has `cont` as its innermost entry) and passes it to
  the clause as the continuation (an `Arrow` is a function now, so directly);
  `HandleLoop`: a clause that suspends before its outcome runs outside the region (dump the
  interior, pop the handler, `Defer(clause, Arrow { Continue => the region rebuilt as a `Handle`
  around `Defer(answer, Arrow(k))` with `cont = id`, or `k(o)` when the answer is settled;
  done => the payload })`); a clause that settles: a settled answer goes straight back into `loop`
  (the operation's `cont` is on top, the interior stays where it is), a pending answer runs under
  this handler with the interior parked (`dump`, then `Defer(answer, Arrow(k))`: `Arrow(k)` is one
  entry, `k` unfolds only when the answer has settled), `Loop.done` truncates to `pos` and the
  payload flows into what was below the region;
  `HandleLoopState`: `???` (the same with `Continue2`, `setState(pos, c._1)`, `complete(state, v)`).
- `Handle`: push the region's `cont`, push its handler, loop on the body.
- settled value: empty stack returns it; else pop, apply an arrow, or complete the region on top
  (`complete(v)`, or `complete(state, v)` for `HandleLoopState`).

## 2. The proposal: `Handler` is an `Arrow`

`sealed abstract class Handler[E, A, +B, -S] extends Arrow[A, B, S]` (Handler's `A` is invariant,
`Arrow`'s contravariant; extending is legal), with, for `HandleCont` and `HandleLoop`:

```scala
def apply(v: A): B < S = complete(v)
def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]): C < (S & S2) =
    v.lower(pending = Kyo.Defer(_, this, next), done = a => next(complete(a), Arrow.id))
def frame: Frame   // the handler's frame, for traces
```

What that gives, in the evaluator:

1. **`dump` has no handler case for the stateless handlers**: `loop(i + 1, f.chain(acc))` for every
   entry. When a dumped continuation is resumed, `push` flattens the chain and a handler entry lands
   back on the stack **as a handler** (`push(f: Arrow)` must send a `Kyo.Handler` to the handler
   push, so `find` sees it): the region is re-installed above `pos` by the flatten itself, exactly
   where it was, with no `Handle` node and no copy of anything. Multi-shot follows: every shot
   re-pushes the same handler.
2. **The settled arm is uniform**: `stack.pop()` is an arrow either way; `loop(f(v), stack)`
   completes the region when the top is a handler. The three handler cases there disappear.
3. `HandleCont`'s `Handle.cont`, `find`, the pending-answer park (`Arrow(k)`) and `Loop.done` are
   unchanged.

What it does not give: `HandleLoopState`'s in-flight state. `apply(v)` cannot see the `states`
slot, so a re-pushed stateful handler would come back at `initialState`, and the settled arm still
needs the slot for `complete(state, v)`.

## 3. The two ways to carry the state, and the ruling

1. State in the handler: `HandleLoopState` carries `state`, `apply(v) = complete(state, v)`, an
   answer replaces the entry with the same handler at the new state. `states` disappears, one array
   of arrows. Cost: one small object per stateful answer, on a path that allocates nothing today
   (`statefulAnswersPaySuccessor`, 798 KB/op at 10k answers; this adds about 24 B × 10k, ~30%).
2. State in the array (**ruled**): `dump` keeps one case, `HandleLoopState` with `states(i)`, and the
   settled arm keeps the `state` read for it. Everything else uniform, the stateful path stays
   allocation-free.

## 4. What has to hold (the review's checklist)

Each item names the test that pins it (`kyo/proto/EvalTest.scala`, `ArrowEffectTest.scala`,
`PendingTest.scala`, all green on the previous evaluator).

- **A1. Flatten re-installs a handler as a handler.** `push(f: Arrow)` matches `Kyo.Handler` first
  and routes to the handler push (state `Absent`, or the carried state for `HandleLoopState` under
  option 2). Otherwise a resumed capture holds a region `find` cannot see, and its operations reach an
  outer handler or nobody. Tests: "a capture crossing an inner region resumes it without re-running
  its body", "a clause raising a foreign effect is answered by the outer handler across the region".
- **A2. Capture-time state per shot.** Under option 2 the dump of a stateful region carries
  `states(i)` at capture, and re-installing pushes that state, not `initialState`. Test: "each shot of
  a multi-shot capture resumes from capture-time state" (expects 101), "a crossed stateful region
  resumes with its in-flight state" (1011).
- **A3. The settled arm reads the state of the entry it pops.** In the file today the
  `HandleLoopState` case reads `stack.state(stack.size - 1)` **after** `pop()`, which is the slot
  below the popped one (pop already decremented `top` and nulled the popped slot); it has to be read
  before the pop, or `pop` has to return it. Test: "done observes the final state" ((12, 21)),
  "state composes with done".
- **A4. `dump` keeps the handler at `pos`; the pending-clause path pops it itself.** Two of the
  three callers need the region to stay (`HandleCont`: the region stays for the clause's result;
  pending answer: the answer runs under this handler); only the pending-clause path takes it off,
  which is why the `discard(stack.pop())` sits there and not in `dump`. Tests: "a clause that suspends
  before its outcome runs outside its region" (log `inner, outer`), "an effectful answer runs under
  this handler with the interior parked" (`outer, inner` with the fused remainder variants).
- **A5. The park stays parked.** `Kyo.Defer(a, Arrow(k))`, not `Kyo.Defer(a, k)`: `push` flattens a
  chain, so `k` pushed directly would put the interior (handlers included, now) back on the stack
  before the answer runs. Tests: the four "fused remainder inside the interior region" cases and
  "an effectful answer's fused remainder raises the interior's effect with no outer handler for it".
- **A6. Trailing maps stay linear.** A dumped chain of k entries, once resumed, is flattened back
  into k entries; the next capture above the same region dumps k again. On
  `trailingMapsStayLinear` (10k nested trailing maps) that is quadratic: 519 us against 778 ms the
  day it happened. With handlers now inside dumps the same shape applies to regions. Either the
  flatten does not expand a `Chain` (keep it one entry and let its `apply` defer it) or the row is
  quadratic; the bench row is the test, and the JMH `-prof gc` B/op on it (2.16 MB linear, 2.4 GB
  quadratic) is the number to look at.
- **A7. Right-deep dump.** `f.chain(acc)` walking up: applying `k(o)` runs the innermost strictly and
  defers the rest as one node; the other associativity recurses through the chain on the Java stack.
  Test: "a long map tower on a pending suspension handles in bounded stack" (1M maps).
- **A8. Partial evaluation.** `Eval.apply` returns `A < S`; an operation with no handler
  (`find` = -1) must come back pending with the whole stack folded into its continuation, not
  `handler(-1)`. Tests today say "unhandled is a bug" (`Eval(ask.asInstanceOf[Int < Any])` throws);
  the new signature turns that into "comes back pending", and the test changes with it.
- **A9. Nested evaluations are isolated by the pool**, not by `base`: "a nested eval shares the
  thread's stack and sees none of the outer regions" now reads "a nested eval has its own stack".
- **A10. Region completion order.** With handlers as arrows, a region's completion is
  `apply(v) = complete(v)`; the region's real continuation was pushed below the handler (or not at
  all when it is `Id`) and applies next. `Loop.done` bypasses `complete` by truncating to `pos`.
  Tests: "Loop.done stops the region and bypasses done", "the innermost done wins under nested
  same-tag handlers".
- **A11. Variance and frames.** `Handler[E, A, +B, -S] extends Arrow[A, B, S]` typechecks
  (invariant `A` under contravariant `A`); every handler needs a `frame` (the region's, from
  `handleCont`/`handleLoop`/`handleLoopState`'s implicit `Frame`) or `Frame.internal`.

## 5. Open questions for the reviewer

1. Is there a case where re-installing a handler by flatten (A1) differs from the old `Handle`
   re-wrap in observable behaviour, beyond the state (A2)? In particular the ordering of a stateful
   handler's `setState` on the answer path when the same handler object is on the stack twice
   (multi-shot with the second shot started before the first completes cannot happen sequentially;
   is there an effect-driven interleaving that makes it happen?).
2. A6: is there a cheaper fix than "do not flatten a `Chain` on push"? Flattening was introduced so
   `pop` returns one arrow to apply; keeping chains as entries means `apply` on a chain must not
   recurse (it does not: `Chain.apply(v, next) = Defer(v, this, next)`, and the one-argument
   `Chain.apply(v) = b(a(v), Arrow.id)` recurses once per nesting level, which is bounded only for
   right-deep chains).
3. A8: what is the folded continuation of an unhandled suspension when the stack holds handlers of
   other tags? (`dump(-1)`-shaped: everything, handlers included, as one arrow; the returned value is
   `Defer(suspension, dumped)`.)
4. Does anything in `ArrowEffect.scala` (the fused `Suspend with Arrow` node, the `*With` fused
   `Handle with Arrow` nodes) collide with `Handler` also being an `Arrow` (a `Handle` node's
   `handler` and its `cont` are both arrows now; nothing pushes a handler as `cont`, but the types
   allow it)?
