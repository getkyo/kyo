# kernel2 design

The fourth kernel prototype: proto3's substrate (Arrow, AndThen, Offset,
typed Step, sync drive, depth-guarded stack safety) plus the ratified
handler system (two effect kinds, four handler formats as chain delimiters,
park-time reification). Correctness, safety, and API canonicality first;
performance work follows in a later round against the proto3 ledger.

## Principles

1. The public API is fully typed; internal dispatch uses casts justified by
   tag evidence, each confined to the drive and documented.
2. Handling is installation. Every handle API appends its delimiter arrow
   and returns; one drive at the boundary executes everything. Parking is
   automatic: a drive that finds no matching delimiter returns the pending
   computation as a value.
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
abstract class ControlEffect[I[_], O[_]] extends Effect   // invariant: see deltas
abstract class ContextEffect[+V]        extends Effect
```

Kinds are declarations of what the handler provides (operations vs a value);
handler formats are per-site choices over ControlEffect, per the ruling that
cardinality is a property of the handling, not the effect. Stop-shaped
effects use `Const[Nothing]` outputs; no separate kind.

## Suspension surface

```scala
object ControlEffect:
    inline def suspend[A](using Frame)[I[_], O[_], E <: ControlEffect[I, O]](
        tag: Tag[E], input: I[A]): O[A] < E
    inline def suspendWith[A](using Frame)[I[_], O[_], E <: ControlEffect[I, O], B, S](
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
abstract class Suspend[I[_], O[_], E <: ControlEffect[I, O], A] extends Suspension[O[A], E]
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
object ControlEffect:
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
`v.eval` (total, S = Any) and `v.eval(preempt, period)` (preemptible)
remain, and ControlEffect.handlePartial is the boundary drive for a last
effect. No multi-effect overloads.

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

1. Kinds and constructors: rename Effect to ControlEffect, add Effect parent,
   ContextEffect, suspend APIs, Effect.defer, Effect.bracket; port tests
   and benches off hand-rolled nodes; suite green.
2. Delimiters, drive dispatch, handle, handleResume, handleStop,
   handleFirst; remove the loop-argument eval protocols; port tests and
   benches; new ControlEffectTest covering deep semantics, nesting,
   cross-effect parking, multi-shot, abort by drop.
3. handleLoop with Outcome and done.
4. ContextEffect: ContextRead, Continue generalization, handle, resolution,
   shadowing and default tests.
5. Polish: visibility tightening (drive is non-inline, so node internals
   return to private[kyo]), Arrow scaladoc as user-facing API, JS/Native
   compile gates, bench snapshot recorded as the correctness-first baseline.

## Implementation deltas (recorded as built)

Decisions made or corrected during the build, now the source of truth:

1. Handling evaluates eagerly (ruled): control handles drive their region
   at the handle site, like the current kernel and like every other strict
   position (map on values). The result of handle is the region's value or
   the computation parked on an effect the handler does not cover, with the
   handler traveling in it; work before a park runs once and is shared by
   later evaluations. Context handles install without driving, and context
   reads and defaults resolve only at boundary drives (eval, eval(preempt,
   period), handlePartial), parking in intermediate handle drives: this is
   what keeps late-installed bindings composing (ifDefined sees the outer
   binding), matching the current kernel's late context resolution.
   Preemption of handle-site drives is deliberately out of scope for now;
   the preemption mechanism will be iterated separately. handlePartial is
   the runtime boundary: it drives with the Maybe protocol (Present
   continues, Absent parks after the clause captured the continuation) and
   the preemption budget.
2. ControlEffect's parameters are invariant. Variant parameters make inference
   solve the operation constructors to their extremes at tag-driven sites;
   subtype dispatch across effect families is future work that must bring
   its own inference design. Clause lambdas follow the current kernel's
   convention: no parameter ascriptions, the expected type flows in.
3. Delimiters split into a sealed Operation layer (Cont, Resume, Stop,
   First, Loop) and the Context binding; arrow dispatch searches only
   Operation delimiters.
4. The loop outcome transform sits in the chain before the suffix, so
   effects raised while an outcome is computed dispatch to outer handlers;
   the replacement delimiter re-appends by arrow application, which also
   runs done when the continued computation is already a value. Outcome is
   covariant with explicit case extensions so the result type infers from
   done.
5. Defer re-evaluates per drive; bracket re-acquires per drive (multi-shot
   coherence). Mapping over a bracket composes inside the resource scope
   (release last), the inherited proto3 semantics, kept.
6. discard is public: it is the abandonment operation of the bracket
   lifecycle.
7. Fixed en passant: pass-through positions re-lift raw values (a value
   that is itself a computation must cross the chain as data), and Defer
   construction sites store the lifted value; both were latent Nested
   hazards inherited from proto3.

## Correctness-first baseline (informational)

The kernel2 drive is deliberately unoptimized (non-inline, chain search per
dispatch, context resolution per read); the proto3 ledger defines the
recovery targets for the performance round. JS and Native compile clean.

| row | proto3 | kernel2 |
|---|---|---|
| eager5 / eager10 alloc | 0.0 / 0.0 | 0.0 / 0.0 |
| suspension / suspensionStep alloc | 152 | 304 |
| stateCont10 / stateStep10 alloc | 904 | 3552 |
| narrowIter alloc | 4664 | 9104 |
| resumeFused10 alloc | 0.0 | 0.0 |
| state / stateStep time | 33-45ms | 69 / 65ms |
| narrowBindMap time | 123-142ms | 185ms |
| stateMap 10k / 100k / 1M | 3 / 9 / 62ms | 3 / 42 / 457ms |

One algorithmic issue was found and fixed in this round rather than
deferred: dispatch descended into handler-free nested chains, making
stateMap quadratic (100k at 43 seconds). Chain nodes now carry a
hasHandler bit computed at construction; dispatch and context resolution
skip handler-free subtrees as opaque prefix elements and bail immediately
on delimiter-free chains, restoring linear parking.

### JMH baseline (official)

The table above was an ad-hoc harness snapshot; the official kernel2
baseline is the in-module JMH suite (`kyo-kernel2/jvm/src/jmh`, run with
`sbt 'kyo-kernel2JVM/Jmh/run -prof gc .*KernelBench.*'`, avgt, fork 1,
5x1s warmup and measurement, compact object headers). Numbers from the
first full run:

| benchmark | time (ns/op) | alloc (B/op) | workload unit |
|---|---|---|---|
| eagerMap5 | 3.71 ± 0.06 | 0 | 5 eager maps + eval |
| deepBind10k | 71942 ± 1744 | 160336 | 10k recursive binds (7.2 ns, 16 B per bind) |
| suspension | 485 ± 12 | 2120 | 6 suspensions + 10 maps, handle + eval |
| suspensionStep | 491 ± 49 | 2120 | same via cont.step resume |
| state10 | 828 ± 38 | 4560 | 21 suspensions (11 reads, 10 sets) |
| stateMap10k | 2294035 ± 45291 | 7832023 | 20k suspensions + maps (115 ns, 392 B per suspension) |
| narrowIter | 2596 ± 67 | 12024 | 12 suspensions, 10 maps each |
| resumeFused | 18.8 ± 0.6 | 0 | resume a parked 10-map continuation |

Floors confirmed under JMH: the eager map path and resuming an
already-fused parked continuation are allocation-free. Suspension-heavy
rows carry the expected unoptimized dispatch cost (roughly 350-400 B and
80-115 ns per suspension including the handler search); those are the
rows the performance round measures against the proto3 ledger.

After the Safepoint preemption work (instance-based Safepoint with the
slot wrapper swap, see kernel2-preemption-analysis.md), the suite was
re-run: every row holds at this baseline within noise except eagerMap5
at 5.78 ns/op (was 3.71), the measured price of the instance layout and
identity check on the purest eager chain; allocations are unchanged
everywhere, with eagerMap5 and resumeFused still allocation-free. The
per-variant measurement matrix behind that trade is recorded in the
preemption analysis.

## Ruled follow-ups

- Port kyo's Loop into kernel2 and revisit handleLoop's Outcome against
  Loop.Outcome2 (ruled: fix other issues first).
- Variance restored on ControlEffect with the kernel's S2 split across the
  handle family; handleFirst and handleLoop take (handle, done) in one
  argument list, the current kernel's shape.
- Handling semantics ruled: eager at the handle site for control handlers,
  install-only for context handlers, boundary-gated context resolution.

## Known deferred concerns

Performance of dispatch (chain search per suspension, context resolution
per read, non-inline drive) is deliberately unoptimized; the proto3 ledger
defines the recovery targets. The fork-time environment snapshot for
Isolate arrives with the environment threading work in a later round.

Platform support beyond the JVM is parked by user ruling (JVM-only for
now). Recorded state for the platform round: kyo-kernel2 JS does not link.
Safepoint's Thread.threadId calls were replaced with the deprecated getId
(the old kernel's pattern, identical on the JVM) after
kyo-kernel2JS/Test/fastLinkJS reproduced the link failure; the remaining
linker error is Thread.isAlive in Safepoint.dead (slot reclamation),
absent from the Scala.js javalib. Candidate fix: guard with kyo-data's
per-platform inline Platform constants so the call folds away on JS and
Wasm at compile time (precedent: Platform.isJS at Tag.scala:319). The
frozen proto2 and proto3 prototypes in kyo-kernel use isAlive in shared
code as well and carry the same latent JS-link issue.

## Visibility policy

Public is exactly the user surface plus what inline expansion forces:

- User surface: the two effect kinds and their companions (suspend and
  handle families, Outcome), Effect.defer and Effect.bracket, the `<`
  operations (lift, map, eval, eval(preempt, period), discard, observe),
  Arrow with apply, map, step, Step, and Transform as the SPI.
- Inline-forced: node classes instantiated by inline constructors (Suspend,
  ContextRead, Defer, Bracket), Kyo and Kyo.unwrap, Arrow.of, Offset's type
  and accessors, liftSlow. These are public but undocumented surface;
  tightening them requires de-inlining the constructors first.
- Everything else is private[kyo] or private: the node hierarchy's map and
  prepend, Continue, Nested, Suspension's operations, Handler and its
  delimiters, Safepoint, Finalize, Observe, optimize, stepSlow, the drive
  and dispatch internals, KyoException.

## Naming

ControlEffect, renamed from ArrowEffect after Arrow became a first-class
user-facing type: the kind's unifying property is that the handler receives
control at each operation (resume once, never, or many times), which also
covers the stop-shaped members that never continue. Pairs with
ContextEffect: control effects provide operations, context effects provide
a value.
