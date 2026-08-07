# proto4 design

The fourth kernel prototype: proto3's substrate (Arrow, AndThen, Offset,
typed Step, sync drive, depth-guarded stack safety) plus the ratified
handler system (two effect kinds, four handler formats as chain delimiters,
park-time reification). Correctness, safety, and API canonicality first;
performance work follows in a later round against the proto3 ledger.

## Principles

1. The public API is fully typed; internal dispatch uses casts justified by
   tag evidence, each confined to the drive and documented.
2. Handling is driving. Every handle API maps its delimiter arrow onto the
   computation and drives immediately. Parking is automatic: a drive that
   finds no matching delimiter returns the pending computation as a value.
3. Handlers live in the chain. A delimiter is an ordinary `Arrow.Transform`
   (pass-through on values, dispatch target for suspensions), so the
   existing prepend machinery carries installed handlers across parks with
   no re-wrap.
4. Deep handler semantics by structure: after a clause runs, its result
   re-enters the chain at the delimiter's suffix (delimiter included), so
   re-suspensions of the same effect dispatch to the same handler. The
   captured continuation excludes the delimiter.
5. Arrow is user-facing. Continuations handed to clauses are plain
   `Arrow[O[C], A, S]` values: composable, applicable, steppable,
   multi-shot safe by immutability.
6. No inline cloning of the drive in this round: the drive is a private
   method, which also lets node internals return to `private[kyo]`.

## Effect kinds

```scala
abstract class Effect
abstract class ArrowEffect[-I[_], +O[_]] extends Effect
abstract class ContextEffect[+V]        extends Effect
```

Kinds are declarations of what the handler provides (operations vs a value);
handler formats are per-site choices over ArrowEffect, per the ruling that
cardinality is a property of the handling, not the effect. Stop-shaped
effects use `Const[Nothing]` outputs; no separate kind.

## Suspension surface

```scala
object ArrowEffect:
    inline def suspend[A](using Frame)[I[_], O[_], E <: ArrowEffect[I, O]](
        tag: Tag[E], input: I[A]): O[A] < E
    inline def suspendWith[A](using Frame)[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        tag: Tag[E], input: I[A])(f: O[A] => B < S): B < (E & S)

object ContextEffect:
    inline def suspend[V, E <: ContextEffect[V]](tag: Tag[E]): V < E
    inline def suspendWith[V, E <: ContextEffect[V], B, S](tag: Tag[E])(f: V => B < S): B < (E & S)
    inline def suspend[V, E <: ContextEffect[V]](tag: Tag[E], default: => V): V < Any

object Effect:
    inline def defer[A, S](f: => A < S): A < S
    inline def bracket[R, A, S](acquire: => R < S)(release: R => Unit < S)(use: R => A < S): A < S
```

`suspend` returns the bare suspension node (a `Kyo`), with no Continue
wrapper. `defer` is the lazy thunk missing from proto3: encoded as
`Kyo.Defer((), transform-running-f)`, no new node. `bracket` is the public
constructor for the existing `Kyo.Bracket` node.

Node change: `Continue` generalizes from pairing a `Suspend` to pairing a
`Suspension` (sealed parent of `Suspend` and the new `ContextRead`), so both
kinds share the chain machinery:

```scala
sealed abstract class Suspension[+X, -E] extends Kyo[X, E]
abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A] extends Suspension[O[A], E]
abstract class ContextRead[V, E <: ContextEffect[V]] extends Suspension[V, E]   // tag, default: Maybe[() => V]
final class Continue[X, +B, -S](suspend: Suspension[X, ?], cont: Arrow[X, B, S])
```

## Delimiters

```scala
sealed abstract class Handler extends Arrow.Transform[Any, Any, Any]:
    def tag: Tag[?]

HandleCont(tag, clause)             // run = pass-through
HandleResume(tag, clause)           // run = pass-through
HandleStop(tag, clause)             // run = pass-through
HandleFirst(tag, clause, done)      // run = cont(done(v)); shallow
HandleLoop(tag, state, clause, done)// run = cont(done(state, v))
HandleContext(tag, transform)       // run = pass-through
```

Internally erased (`Transform[Any, Any, Any]`), constructed only by the
typed handle APIs. Loop state evolution never mutates a node: a state
update allocates a replacement delimiter in the suffix.

## Handling surface

```scala
object ArrowEffect:
    def handle      [I, O, E, A, S](tag, v: A < (E & S))(
        clause: [C] => (I[C], Arrow[O[C], A, E & S]) => A < (E & S)): A < S
    def handleResume[I, O, E, A, S](tag, v: A < (E & S))(
        clause: [C] => I[C] => O[C] < (E & S)): A < S
    def handleStop  [I, O, E, A, S](tag, v: A < (E & S))(
        clause: [C] => I[C] => A < (E & S)): A < S
    def handleFirst [I, O, E, A, B, S](tag, v: A < (E & S))(
        clause: [C] => (I[C], Arrow[O[C], A, E & S]) => B < S)(done: A => B < S): B < S
    def handleLoop  [I, O, E, State, A, B, S](tag, state: State, v: A < (E & S))(
        clause: [C] => (State, I[C], Arrow[O[C], A, E & S]) => Outcome[State, A < (E & S), B] < (E & S))(
        done: (State, A) => B < S): B < S

    enum Outcome[+State, +Next, +B]:
        case Continue(state: State, next: Next)
        case Done(result: B)

object ContextEffect:
    def handle[V, E <: ContextEffect[V], A, S](tag, value: V)(v: A < (E & S)): A < S
    def handle[V, E <: ContextEffect[V], A, S](tag, ifUndefined: => V, ifDefined: V => V)(v: A < (E & S)): A < S
```

Consolidation: the loop-argument protocols `<`.eval(tag)(handler) and
`<`.evalPartial are removed; their roles are covered by the formats
(handleFirst covers capture-and-park) and by parking being automatic.
`v.eval` (total, S = Any) and `v.eval(preempt, period)` (scheduler
protocol) remain. No multi-effect overloads.

## Dispatch semantics

The drive loop arms: Bracket (unchanged machinery), Defer (trampoline and
preemption, unchanged), Continue and bare Suspension (dispatch), value.

Dispatch on `Continue(suspension, chain)`:

1. Normalize `chain.optimize` and walk the Offset spine for the first
   `Handler` whose tag matches the suspension's tag (`handlerTag <:<
   suspensionTag`, the current kernel's direction). No match: return the
   Continue (parked).
2. Arrow suspension, at found node `found = Offset(h, rest)`:
   - Resume: `next = chain(clause(input))`. The whole chain reapplies; the
     delimiter passes values through; effects in the clause result dispatch
     deep. No capture, no allocation beyond the clause's own.
   - Stop: `next = found(clause(input))`. Prefix skipped structurally,
     delimiter kept for deep re-dispatch.
   - Cont: materialize `k` = the walked prefix re-linked into a fresh chain
     (excluding the delimiter), `next = found(clause(input, k))`. The only
     format that pays capture.
   - First: like Cont but `next = rest(clause(input, k))` (delimiter
     excluded: shallow), and a value reaching the delimiter runs `done`.
   - Loop: like Cont with state; `Outcome.Continue(s2, nxt)` replaces the
     delimiter in the suffix with state `s2` and continues with `nxt`;
     `Outcome.Done(b)` continues with `rest(b)`.
3. Context read: walk the chain collecting matching HandleContext
   delimiters; resolve outermost-first (`Absent` seed, each transform
   applied inward); the innermost result is the read value; `next =
   chain(value)`. No match: the node's default if present, else park.

## What stays from proto3, untouched this round

The `<` opaque type and lift; inline `map` minting; Arrow apply, map,
optimize, step and the Offset chain; the Depth guard, rescue, and
segmentBoundary; the Bracket drive machinery; KyoException. The step
protocol composes with the new system: clause continuations are arrows and
decompose with `step` like any chain.

## Phases

1. Kinds and constructors: rename Effect to ArrowEffect, add Effect parent,
   ContextEffect, suspend APIs, Effect.defer, Effect.bracket; port tests
   and benches off hand-rolled nodes; suite green.
2. Delimiters, drive dispatch, handle, handleResume, handleStop,
   handleFirst; remove the loop-argument eval protocols; port tests and
   benches; new ArrowEffectTest covering deep semantics, nesting,
   cross-effect parking, multi-shot, abort by drop.
3. handleLoop with Outcome and done.
4. ContextEffect: ContextRead, Continue generalization, handle, resolution,
   shadowing and default tests.
5. Polish: visibility tightening (drive is non-inline, so node internals
   return to private[kyo]), Arrow scaladoc as user-facing API, JS/Native
   compile gates, bench snapshot recorded as the correctness-first baseline.

## Known deferred concerns

Performance of dispatch (chain search per suspension, context resolution
per read, non-inline drive) is deliberately unoptimized; the proto3 ledger
defines the recovery targets. The fork-time environment snapshot for
Isolate arrives with the environment threading work in a later round.
