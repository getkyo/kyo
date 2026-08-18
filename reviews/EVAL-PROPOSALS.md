# Eval rewrite: the proposals queued for the owner, in dependency order

Status: prepared 2026-08-18 12:55 while the held-out review of `reviews/HANDLER-AS-ARROW.md` runs.
The owner's order: no changes; each proposal is presented in chat, one at a time, and approved
individually. Safety and as much static typing as the erased stack allows are the bar. The list below
is what will be presented, each entry with the smallest snippet that states it; the review's verdicts
are merged in before anything is presented (an entry the review refutes is dropped or reshaped).

State the list starts from: `kyo-kernel2/shared/src/main/scala/kyo/proto/Eval.scala` compiles
(12:49, no warnings): erased positions named (`CX`, `AX`, `BX`, `StateX`), `loop[A, B, S](v: A < (EX &
S), stack): B < S`, `dump[A, B, S](pos): Arrow[A, B, S]`, every match typed. Uncommitted, the owner's.

## P1. The settled arm reads the state of the entry it pops (A3)

Independent of the review. `pop()` decrements `top` and nulls both slots, so `stack.state(stack.size -
1)` after it is the slot below the popped handler (and the popped slot is null). Read it before the pop:

```scala
done = v =>
    if stack.isEmpty then v.asInstanceOf[B < S]
    else
        val state = stack.state(stack.size - 1)
        stack.pop() match
            ...
            case h: HandleLoopState[IX, OX, EX, A, ?, S, StateX] @unchecked =>
                state.get match
                    case state: StateX @unchecked => loop(h.complete(state, v), stack)
```

One array read per settled step on every path. The alternative, `pop` not clearing `states`, keeps a
dead reference alive until the slot is overwritten; not proposed. Pinned by "done observes the final
state" ((12, 21)) and "state composes with done".

## P2. `Handler` is an `Arrow` (the reviewed proposal, option 2)

Shape, subject to the review's verdicts on A1, A2, A10, A11 and open question 4:

- `HandleCont` and `HandleLoop` extend `Arrow[A, B, S]`: `apply(v) = complete(v)`,
  `apply(v, next) = v.lower(pending = Kyo.Defer(_, this, next), done = a => next(complete(a), Arrow.id))`,
  `frame` from the region (the `handleCont`/`handleLoop` implicit `Frame`) or `Frame.internal`.
- `Stack.push(f: Arrow)` matches `Kyo.Handler` first and routes to `push(h: Handler)` (A1), so a
  flattened capture re-installs a region as a region, where it was.
- `dump`'s loop: `case f: Arrow => loop(i + 1, f.chain(acc))` covers the stateless handlers; the
  `HandleLoopState` case is P3.
- The settled arm: `case f: Arrow[A, ?, EX & S] => loop(f(v), stack)` covers the stateless handlers
  (`apply` is `complete`); the `HandleCont`/`HandleLoop` cases go; `HandleLoopState` stays (P1's read).

Whether `HandleLoopState` itself extends `Arrow` is the open point: under option 2 it has no state to
complete with, so either `Handler` stays a non-arrow and the two stateless subclasses extend `Arrow`, or
`HandleLoopState.apply` is `bug(...)`. The first is the type-honest one and is what will be proposed.

## P3. A captured stateful region carries its capture-time state (A2, dump's `HandleLoopState` case)

Under option 2 the state lives in `states(i)`; a dumped continuation leaves the stack, so the dump must
carry it. The entry that lands back on the stack must be a `Handler` (that is what `push` installs and
`find` sees), so the carrier is the same handler entered at the captured state:

```scala
case h: HandleLoopState[IX, OX, EX, ?, ?, ?, StateX] @unchecked =>
    states(i).get match
        case s: StateX @unchecked => loop(i + 1, HandleLoopState.resumed(h, s).chain(acc))
```

`resumed(h, s)` is the value the owner deleted from `Kyo.scala` (a `HandleLoopState` with
`initialState = s` delegating `run`/`complete`); one small object per capture that crosses a stateful
region, nothing on the answer path. Pinned by "each shot of a multi-shot capture resumes from
capture-time state" (101) and "a crossed stateful region resumes with its in-flight state" (1011).
Needs the review's answer to open question 1 (the same handler object on the stack twice).

## P4. `HandleLoopState` in the pending arm

The `HandleLoop` branch with `Continue2`: `h.run(state, kyo.input)` where `state` is
`stack.state(pos).get` matched as `StateX`; settled clause: `Continue2(s2, answer)` sets
`stack.setState(pos, s2)` then the answer exactly as `HandleLoop` (settled straight into `loop`,
pending parked behind `Arrow(k)`); `done` truncates to `pos`; pending clause: dump, pop, and the
outcome arrow rebuilds the region at the new state, `Kyo.Handle(Defer(c._2, Arrow(k)),
HandleLoopState.resumed(h, c._1), id)`. Same `resumed` as P3. Pinned by the seven `handleLoopState`
cases and "a stateful clause that suspends threads its state through the park".

## P5. The unhandled operation under `Eval.apply[A, S](v: A < S): A < S` (A8)

`stack.handler(-1)` is an array read at -1 today. Partial evaluation returns the suspension pending
with the whole stack folded into its continuation; the fold crosses handlers, so it needs P2/P3.
`find` before `push`, so the operation's own `cont` is not applied twice:

```scala
case kyo: Kyo.Suspend[IX, OX, EX, CX, A, S] @unchecked =>
    val pos = stack.find(kyo.tag)
    if pos < 0 then Kyo.Defer(kyo, stack.dump[A, B, S](-1))
    else
        stack.push(kyo.cont)
        ...
```

and `Pending.eval` (`S =:= Any`) must reject a pending result instead of casting it: `Eval(v).lower(pending
= _ => bug("unhandled suspension"), done = a => a)`. Tests change with it: "an unhandled operation is a
bug" becomes "comes back pending" at the `Eval` level and stays a bug at `.eval`; "a nested eval shares
the thread's stack" becomes "has its own stack" (A9). Needs the review's answer to open question 3.

## P6. The owner's `discard(stack.pop())` TODO

Under P2, `dump(pos)` still keeps the handler at `pos` (A4: two of its three callers need the region to
stay), and the pending-clause path pops it itself; the TODO's answer is "because the other two callers
need it there", and it is proposed to stay as written. If the review finds a shape where `dump` can
take the handler for all three callers, that replaces this entry.

## P7. Trailing maps under flatten-on-push (A6, open question 2)

Whatever the review recommends: keep a `Chain` as one entry on push (its `apply(v, next)` is a
`Defer`), or accept the re-expansion. Decided by the bench row `trailingMapsStayLinear` (2.16 MB/op
linear, 2.4 GB/op quadratic), measured through the harness once the tree is green.

## Then

Tests green (`kyo.proto.*`, 178 at `2da854f5aa`, the A8/A9 cases rewritten), then task #37: the
kernel-vs-proto bracket at the committed rewrite, on a quiet machine, both classes, json and log kept.
