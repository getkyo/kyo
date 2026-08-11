# kernel2 design: handlers

Status: capture of the maintainer's design for validation, with the
implementation plan. Nothing below is implemented except where marked DONE.
Prior art references: kernel2-rotation-handlers-design.md (measurements, the
11 reference programs), kernel2-jit-morphism-report.md (the megamorphic
trampoline measurement), HandlersProbe.scala (executable checks).

## 1. The design

### 1.1 Two handler kinds

The hierarchy carries the one distinction that is semantically forced:
whether the handler needs the continuation reified.

    sealed abstract class Handler[I[_], O[_], E <: ArrowEffect[I, O]](val tag: Tag[E])

    object Handler:

        abstract class Loop[I[_], O[_], E <: ArrowEffect[I, O], S](tag: Tag[E])
            extends Handler[I, O, E](tag):
            def apply[X](input: I[X]): O[X] < S
            def updated[X](input: I[X], answer: O[X]): Loop[I, O, E, S] = this

        abstract class Cont[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E])
            extends Handler[I, O, E](tag):
            def apply[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)

Loop is the answering kind, and the only one. Its clause answers at the
operation and execution continues; it never touches the continuation, by
construction. What the previous Resume and Stop kinds expressed are special
cases of what its clause already returns, composed as values:

- A stateless handler is Loop with the defaulted successor: `updated`
  returns `this`, nothing else exists.
- A stateful handler overrides `updated` to yield its successor, computed
  from the input and the settled answer (no recomputation of the
  transition). Example, the whole of Var:

      sealed trait Var[V] extends ArrowEffect[Const[V => V], Const[V]]

      final class VarHandler[V](tag: Tag[Var[V]], value: V)
          extends Handler.Loop[Const[V => V], Const[V], Var[V], Any](tag):
          def apply[X](f: V => V): V < Any = f(value)
          override def updated[X](f: V => V, answer: V) =
              if answer.asInstanceOf[AnyRef] eq value.asInstanceOf[AnyRef] then this
              else new VarHandler(tag, answer)

- A stopping handler answers with the escape: variance makes `Halt` a valid
  clause answer (`Halt <: Nothing < Any <: O[X] < S`). Halting is in-band,
  a value the clause returns like any other, and it composes with state in
  one handler, which the three-kind taxonomy could not express:

      final class Budget(tag: Tag[Tick], remaining: Int)
          extends Handler.Loop[Const[Unit], Const[Unit], Tick, Any](tag):
          def apply[X](input: Unit): Unit < Any =
              if remaining > 0 then () else new Kyo.Halt(this, ...)
          override def updated[X](input: Unit, answer: Unit) =
              if remaining > 1 then new Budget(tag, remaining - 1) else this

Cont is the only kind for which building the continuation is the meaning,
so it is the only place reification is ever owed.

### 1.2 Halt

    final class Halt[...](
        val owner: Handler[?, ?, ?],   // compared by eq
        val outcome: ...               // the handled scope's result
    ) extends Kyo[Nothing, Any]:
        def map[B, S2](f: Arrow[Nothing, B, S2]) = this

Changes from the implemented B2 shape: the owner widens from Handler.Stop
to Handler (any answering handler can address itself), and the payload
becomes the scope's outcome rather than the operation's input, because the
clause has already run at the operation. `map` returning itself makes every
frame between the operation and the owning boundary discard its pending
work by construction: a stop N frames deep costs one object, zero
attachments. The boundary (Eval's enter) consumes it by identity and
continues with the outcome, evaluated under the scope's own collection, so
an outcome that raises the scope's effect again stops again.

### 1.3 What "not creating the continuation" means, and where it holds

The continuation is data reifying the rest of the computation, created at
exactly one kind of place: the kyo-arm attach, the moment execution stops
and `kyo.map(arrow)` turns what-would-run-next into a stored object. For a
Loop-handled operation execution never needs to stop: with the handlers as
a parameter of execution itself, the walk answers and keeps walking, or
returns the one Halt. Reification happens only when it is owed: a Cont
handler, or a missing handler, where execution genuinely must stop and the
regular suspension sees its handler after the continuation is resumed.

Corollary: once anything holds a Suspend with its cont, the reification
already happened; no handler kind saves anything there. Eval is the
fallback and the registration mechanism, not where the property lives.

### 1.4 State semantics

The successor flows as the collection argument of the ongoing walk:
`handlers.updated(idx, next)`, position preserved. Because the walk is
continuation-passing (the rest is always handed down as `next`, never
resumed up), the new state dominates everything after the operation
structurally. Reads pass the same collection (the `eq` check); writes pay
one successor instance and one positional copy. State is scoped to its
handler's extent and dies at its boundary; updates to outer handlers made
inside an inner scope survive that scope's exit. Capture snapshots the
flowing collection, so replays fork from the state as of the capture.

The update-then-miss carry: when a later operation in the same scope
misses (Cont-handled or unhandled) and execution must reify, the
attachment minted at that point closes over the flowing collection, and
applying that continuation later resumes under it, not under the applier's.
Without this, resumption and capture would silently reset state.

## 2. Implementation plan

### Step 1 (next): Loop and its handling in Eval

Handler.scala: Loop redefined as in 1.1; Resume and Stop deleted.
Kyo.scala: Halt reshaped as in 1.2.
Eval.scala:
- One answering arm replaces the Resume and Stop arms: answer the clause;
  a pending answer settles under the prefix through the handler
  (`handlers.take(idx + 1)`); then `updated(input, settled)`, the `eq`
  check, and the walk continues under the updated collection. An answer
  that is a Halt flows through the existing value paths and climbs; no
  special casing at the answering arm.
- enter's owner arm continues with the halt's outcome under the scope's
  collection instead of applying a clause.
- evalLoop returns the collection alongside the value (one pair per
  invocation, not per step), so updates to outer handlers survive a
  scope's exit: the exit drops the scope's own entry (last position,
  entries are only updated in place) and the outer walk continues under
  the rest; a settled clause merges its possibly-updated prefix back.
Tests (EvalTest): the Var program (get, update, set, composition of
transitions); state persisting across an inner unrelated scope's exit;
reads do not advance the handler; Budget as the stateful in-band stop; the
existing stop programs rewritten in-band (region-ending, stops-again via
an outcome raising the effect, halt climbing past an inner scope leaving
remainder and exit unrun). HandlersTest adjusted to the two kinds. The
probe slims to Cont-only modeling.
Gate: suite green; A/B against the pre-change snapshot (snap-b2, already
taken) on the eval rows.

### Step 2: the wiring

`handlers` as an explicit parameter of arrow application (Arrow.apply,
Transform.apply, the Step walk, both inline mapLoops); Eval passes its
collection into every walk; construction-time entry points pass empty; the
kyo-arm consults at first-touch behind an `eq empty` check with the
non-empty path outlined. Loop answers in place (settled and prefix paths),
in-band halts climb, misses attach with the collection carried (1.4).
Gate: fused ladder at parity; the suspension rows collapse only after the
switch, when handler methods construct Handled values.

### Step 3: Cont in Eval, then the switch

Capture as the crossed-scope re-wrap on the way out, multi-shot, fork from
snapshots. Then handle/resume/stop/loop methods become sync-path
constructors over the two kinds, the five trampolines and wrap-rotation
arms are deleted, the eager pins are rewritten per ruling, and the JMH
board runs.

## 3. Open for instruction

1. The public method surface after the switch: do resume and stop survive
   as conveniences constructing Loop handlers, or does the surface trim to
   the kinds?
2. Halt's exact payload typing (the outcome's value and row).
3. Whether step 2 lands before Cont or after it.
