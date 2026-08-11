# kernel2 design: handlers

Status: capture of the maintainer's design for validation, with the
implementation plan. Nothing below is implemented except where marked DONE.
Prior art: kernel2-rotation-handlers-design.md (measurements, reference
programs), kernel2-jit-morphism-report.md (the megamorphic trampoline
measurement), HandlersProbe.scala (executable checks), and the old kernel's
ArrowEffect.handleLoop signatures, which the clause shapes mirror exactly.

## 1. The design

### 1.1 Three handler kinds

The hierarchy carries the semantically forced distinctions: does the
handler need the continuation reified, and does it carry state.

    sealed abstract class Handler[I[_], O[_], E <: ArrowEffect[I, O]](val tag: Tag[E])

    object Handler:

        abstract class Loop[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E])
            extends Handler[I, O, E](tag):
            def apply[X](input: I[X]): Loop.Outcome[O[X] < (E & S), A] < S

        abstract class LoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E])
            extends Handler[I, O, E](tag):
            def apply[X](input: I[X]): Loop.Outcome2[LoopState[I, O, E, A, S], O[X] < (E & S), A] < S

        abstract class Cont[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E])
            extends Handler[I, O, E](tag):
            def apply[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)

The clause signatures are the old kernel's handleLoop signatures with the
continuation removed and, for LoopState, the handler itself as the state:

    old: [C] => (I[C], cont)        => Loop.Outcome[A < (E & S), A] < S2
    old: [C] => (I[C], State, cont) => Loop.Outcome2[State, A < (E & S), A] < S2

- Loop answers at the operation: `Loop.continue(answer)` keeps executing,
  `Loop.done(result)` ends the handler's scope with that result. It never
  touches the continuation, by construction. Stop is not a kind: it is
  `Loop.done`. A stateless provider (Env-style) is `Loop.continue(value)`.
- LoopState is Loop plus the successor: `Loop.continue(next, answer)`
  advances the handler in the flowing collection, position preserved. State
  is not a kind of its own mechanism: it is the handler being replaced by
  the value it unfolds into. Only stateful handlers pay the successor slot;
  that is why the kinds are separate.
- Cont is the only kind for which building the continuation is the
  meaning, so it is the only place reification is ever owed.

Examples, complete:

    sealed trait Var[V] extends ArrowEffect[Const[V => V], Const[V]]

    final class VarHandler[V](tag: Tag[Var[V]], value: V)
        extends Handler.LoopState[Const[V => V], Const[V], Var[V], A???, Any](tag):
        def apply[X](f: V => V) =
            val v2 = f(value)
            Loop.continue(new VarHandler(tag, v2), v2)

    // fail after a budget: state and ending composed in one handler
    final class Budget(tag: Tag[Tick], remaining: Int)
        extends Handler.LoopState[Const[Unit], Const[Unit], Tick, Int, Any](tag):
        def apply[X](input: Unit) =
            if remaining > 0 then Loop.continue(new Budget(tag, remaining - 1), ())
            else Loop.done(-1)

    // plain abort: stateless, done-only
    final class Fail(tag: Tag[Abortish]) extends Handler.Loop[..., Result, Any](tag):
        def apply[X](input: ...) = Loop.done(failure(input))

The A parameter on Loop and LoopState is the scope's result type, carried
by `done`. An effectful outcome (`< S`) settles first, under the prefix
through the handler, preserving clause scope; this matches the old
handleLoopLoop's own discipline.

### 1.2 Halt is kernel-internal

Clauses never construct or see Halt. When the machinery (Eval's answering
arm, or a site under the wiring) receives `Loop.done(result)`, it mints the
one escape value:

    final private[kernel] class Halt[...](
        val owner: Handler[?, ?, ?],   // compared by eq
        val outcome: ...               // the done value
    ) extends Kyo[Nothing, Any]:
        def map[B, S2](f: Arrow[Nothing, B, S2]) = this

`map` returning itself makes every frame between the operation and the
owning boundary discard its pending work by construction: a done N frames
deep costs one object, zero attachments. The boundary consumes it by
identity and continues with the outcome. An outcome that needs to raise
the scope's effect again expresses that as `Loop.continue` with an
effectful answer instead, answered by the same handler (deep semantics).

### 1.3 What "not creating the continuation" means, and where it holds

The continuation is data reifying the rest of the computation, created at
exactly one kind of place: the kyo-arm attach, the moment execution stops
and `kyo.map(arrow)` turns what-would-run-next into a stored object. For a
Loop- or LoopState-handled operation execution never needs to stop: with
the handlers as a parameter of execution itself, the walk answers and
keeps walking, or climbs the one Halt. Reification happens only when it is
owed: a Cont handler, or a missing handler, where execution genuinely must
stop and the regular suspension sees its handler after the continuation is
resumed.

Corollary: once anything holds a Suspend with its cont, the reification
already happened; no handler kind saves anything there. Eval is the
fallback and the registration mechanism, not where the property lives.

### 1.4 State semantics

The successor flows as the collection argument of the ongoing walk:
`handlers.updated(idx, next)`, position preserved, with an `eq` check
(`Loop.continue(this, ...)` reads) skipping the copy. Because the walk is
continuation-passing, the new state dominates everything after the
operation structurally. State is scoped to its handler's extent and dies
at its boundary; updates to outer handlers made inside an inner scope
survive that scope's exit. Capture snapshots the flowing collection, so
replays fork from the state as of the capture.

The update-then-miss carry: when a later operation in the same scope
misses (Cont-handled or unhandled) and execution must reify, the
attachment minted at that point closes over the flowing collection, and
applying that continuation later resumes under it, not under the
applier's. Without this, resumption and capture would silently reset
state.

### 1.5 Cost ledger

Per answered operation: one Continue box (Loop) or one Continue2 box plus
the successor (LoopState) - the former is what the old kernel's
non-stateful handleLoop pays today, the latter what its stateful form
pays, so both are parity with the existing loop paths while the wiring
removes the attach, bubble, and dispatch costs around them. Done: one
Halt, zero attachments. Reads under LoopState: box only, no successor, no
collection copy.

## 2. Implementation plan

### Step 1 (next): Loop, LoopState, and their handling in Eval

- The Outcome union defined in kernel2 (same encoding as the old kernel's:
  done bare, continue boxed; location and naming flagged in section 3).
- Handler.scala: Loop and LoopState as in 1.1; Resume and Stop deleted.
- Kyo.scala: Halt reshaped as in 1.2.
- Eval.scala: the answering arms evaluate the clause outcome (settling an
  effectful one under the prefix), then: continue walks the answer under
  the same collection (Loop) or the positionally-updated one (LoopState,
  eq-checked); done mints the Halt, which climbs to its boundary; enter's
  owner arm continues with the outcome. evalLoop returns the collection
  alongside the value (one pair per invocation) so updates to outer
  handlers survive a scope's exit: the exit drops the scope's own entry
  and the outer walk continues under the rest; a settled clause merges its
  possibly-updated prefix back.
- Tests (EvalTest): the Var program (get, update, set, composition);
  state persisting across an inner unrelated scope's exit; reads do not
  copy the collection; Budget (state and done composed); done ending the
  scope without running the remainder; the halt climbing past an inner
  scope leaving remainder and exit unrun; done raised deep behind trailing
  transforms. HandlersTest adjusted to the three kinds. The probe slims to
  Cont-only modeling.
- Gate: suite green; A/B against the pre-change snapshot (snap-b2, taken)
  on the eval rows.

### Step 2: the wiring

`handlers` as an explicit parameter of arrow application; Eval passes its
collection into every walk; construction-time entry points pass empty; the
kyo-arm consults at first-touch behind an `eq empty` check with the
non-empty path outlined. Loop and LoopState answer in place, done halts
from the site, misses attach with the collection carried (1.4).
Gate: fused ladder at parity.

### Step 3: Cont in Eval, then the switch

Capture as the crossed-scope re-wrap on the way out, multi-shot, fork from
snapshots. Then the public handler methods become sync-path constructors
over the kinds, the trampolines and wrap-rotation arms are deleted, the
eager pins are rewritten per ruling, and the JMH board runs.

## 3. Open for instruction

1. Where the Outcome union lives in kernel2 and its name: the handler kind
   is named Loop, so `kyo.kernel.Loop` as a utility object collides;
   options include hosting it under Handler's companion or another name.
2. Var's A parameter (the scope result type a Var handler declares for
   done, which a Var handler never uses): Nothing, or the run method's
   result type; surfaced by the VarHandler example.
3. The public method surface after the switch (do resume and stop survive
   as conveniences constructing Loop handlers).
4. Whether step 2 lands before Cont or after it.
