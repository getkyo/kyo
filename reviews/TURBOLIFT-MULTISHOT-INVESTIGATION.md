# Turbolift multi-shot investigation

Question (user): turbolift in theory has multi-shot, but is it broken? Proper multi-shot needs no
mutability between transformations of a computation, and values that are self-contained and
self-described.

Verdict: **not broken on any axis tested.** Six hostile probes against turbolift-core 0.126.0, all
passed, including the two axes kernel2's own multi-shot pins guard: double resumption with state
captured inside the continuation, and an escaped continuation resumed in fresh runs after its
original run died. The engine's mutability is real but disciplined: clone-before-mutate
everywhere a captured structure could be aliased. The discipline is enforced by convention, not by
types, and the surface carries genuine incompleteness markers, so the caveats section matters.

## How the engine stays sound (source reading, 0.126.0 sources jar)

- A captured continuation (`ContImpl`) holds `stack: Stack`, `store: Store`, `step: Step` by
  reference (`internals/engine/ContImpl.scala`).
- **Capture** (`Engine.intrinsicCapture`) splits the current stack/store at the prompt via
  `OpSplit.split`. The fast path hands the continuation shallow segment copies
  (`copyWithTail` = `clone()` of the segment object); the slow path builds fresh segments
  (`Stack.blank`, `Store.blankClone`, cloned `Frame` chains via `Frame.split`, which clones every
  frame it moves and mutates only the clones with `reverseInPlace`).
- **Resume** (`Engine.intrinsicResume` -> `OpSplit.merge` -> `reverse`/`reverseLoop`) re-clones
  every captured segment (`copyWithTail` per segment) before merging onto the current stack, so
  the `ContImpl`'s own segments are never written after construction. This re-clone is also the
  price turbolift pays for multi-shot at each resume: O(captured segments) copying.
- **State cells**: pure ops clone (`Store.seti` = array clone; `deepPut`/`deepModify` build a new
  spine); the in-place fast path (`deepUpdateInPlace`, used for `LocalUpdate`) is always preceded
  by `store.deepClone(segmentDepth)` in `Engine.innerLoop`, so in-place writes only ever hit
  fresh arrays.
- `Pile` is immutable; `Stack` piles arrays are updated persistently (`:+`, `updated`, `init`);
  `Frame` has mutable `nextVar`/`packedVar` but is only mutated on clones during split.
- Tail-resumptive clauses (the Reader's `Local.get`) never capture at all: they answer through
  the CEK registers. That, not any multi-shot shortcut, is why the suspension rows are fast; the
  benchmark rows never exercise capture.

## Probes (all passed; turbolift-core 0.126.0, runST, JDK 25.0.3)

| probe | result |
|---|---|
| T1 two flips under a capture-twice handler | `Vector((true,true),(true,false),(false,true),(false,false))` |
| T2 State handled inside the continuation, resumed twice | `Vector(((true,1),1),((false,1),1))`: both branches count 0 to 1 independently, no bleed |
| T3 State handled outside | `(Vector((true,1),(false,2)),2)`: one cell threads across branches, correct either-semantics |
| T4 continuation stashed in an outer var, original run dead, resumed in fresh runs | `k(5)`=Vector(50), `k(7)`=Vector(70), `k(5)`=Vector(50): self-contained and repeatable |
| T5 100 repetitions of T2 | zero corrupted runs |
| T6 1024 paths (2^10 flips), State inside | every path counts exactly its own 10 increments, 1024 distinct values |

The probe's multi-shot handler, for reproduction (a stateless interpreter whose operation resumes
its continuation twice):

```scala
import turbolift.{!!, Effect, Signature, Handler}
import turbolift.Extensions.*
import turbolift.effects.StateEffect
import turbolift.interpreter.Continuation

trait AmbSig extends Signature:
    def flip: Boolean !! ThisEffect

case object Amb extends Effect[AmbSig] with AmbSig:
    override def flip: Boolean !! this.type = perform(_.flip)
    def handlerAll: Handler[Identity, Vector, Amb.type, Any] =
        new impl.Stateless[Identity, Vector, Any] with impl.Sequential with AmbSig:
            override def onReturn(a: Unknown): Vector[Unknown] !! Any = !!.pure(Vector(a))
            override def flip: Boolean !! ThisEffect =
                Control.capture(k => k(true).flatMap(v1 => k(false).map(v2 => v1 ++ v2)))
        .toHandler

case object Cnt extends StateEffect[Int]

// T2: Amb.flip.flatMap(b => Cnt.modifyGet(_ + 1).map(c => (b, c)))
//       .handleWith(Cnt.handler(0)).handleWith(Amb.handlerAll).runST
// T4: a handler whose clause stashes k in an outer var and returns Vector();
//     k(5).runST invoked repeatedly afterwards.
```

## Caveats: what was NOT covered, and the incompleteness that is real

- Single-threaded, sequential handlers only. Parallel interpreters (`onFork`/`onJoin`, `onZip`
  under real parallelism), IO, finalizers interacting with capture, and concurrency races were
  not probed.
- `Continuation.abort` is literally `??? //@#@TODO` (`interpreter/Continuation.scala`): resuming
  zero times through that entry crashes. `Control.abort` (the interpreter-side unwind) exists.
- `resumePut` on a truncated continuation is undefined by design (guarded by a comment and
  `Location.Deep.invalid`, not by types).
- `intrinsicReinterpret` carries `//@#@TODO shadow map still experimental`.
- The soundness rests on a clone-before-mutate convention across `OpSplit`/`Store`/`Frame`; a
  future in-place optimization (`//@#@OPTY reuse oldStore if number of elements stays the same`
  is already sketched in `OpSplit.splitSegment`) would break it silently if taken without an
  ownership check. Nothing in the types prevents that.

## Relevance to the kernel2 comparison

- Turbolift's suspension-row speed is not bought by broken multi-shot: those rows are
  tail-resumptive and never capture. Its answer-in-place execution against kernel2's
  rebuild-per-answer is the real mechanism (see the suspensionBaseline allocation ledger in
  CROSSLIB-BENCH-RESULTS.md), which is the same conclusion the round-three HandlerCont
  answer-in-class item encodes.
- kernel2 gets the same guarantee structurally (complete immutable values escape the eval loop;
  hostile pins enforce it) where turbolift gets it by engine-internal discipline plus a re-clone
  at every resume.
