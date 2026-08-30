# `Eval.partial` for the proto

How partial evaluation lands in `kyo.proto.kernel`, checked against the reference's
`Eval.partial` (`kyo/kernel/internal/Eval.scala:112-123`) and the proto eval as it stands.

## The equation first

The eval's standing invariant is that at any loop point the remainder of the computation is

```
handle(... handle(defer(v, contA, contB), h_inner, s_inner).cont ..., h_outer, s_outer).cont
```

one `Handle` per open region, innermost closest to the value, each carrying the continuation
that was stored when the region was installed. Parking is transcribing that equation into the
value it already denotes: `Effect.defer(v, contA, contB)` for the current step, then one
`Handle` per stack entry, innermost first. Both pieces exist as public combinators
(`Effect.defer` with its free-slot laws, `Kyo.handle`'s node shape), so the park is the
operational reading of an equation, not a new instruction. No node kind.

## The surface

```scala
private[kyo] def partial[A](v: A < Any): A < Any
```

Same signature, same protocol as the reference:

1. `Safepoint.consumeStopped(slot)` before starting: a stop already delivered ends the slice
   before it begins, the input comes straight back, and the sentinel is taken so the next
   slice runs.
2. Otherwise run armed, and consume the stop at the boundary in the `finally`, so park,
   completion, and failure all satisfy it and no stale sentinel survives.

The row is `Any` for the same reason as the reference: every effect is already handled; a
slice cannot park on an unhandled operation and have it answered later.

## The eval changes

`Eval.apply` splits into the public `apply(v)` and a private `apply(v, armed: Boolean)`.
`armed` is a val of `apply`, closed over by `loop`, never a loop parameter: it does not
change during the eval, and the poll is gated on it so an unarmed eval pays one predictable
branch and nothing else (the reference makes the same trade at `Eval.scala:453`).

After `Safepoint.save(slot)`, which installs a fresh budget and clears the armed bit as it
reads: `if armed then Safepoint.arm(slot)`. The existing `restore` in the finally already
hands a nested eval's caller back its state, armed bit included, so a slice nested in another
eval leaves no trace, and a plain `Eval.apply` nested inside a slice runs unarmed and cannot
park. Both properties come from code that is already there.

The poll sits on the Defer arm, mirroring the reference's placement (`:524`):

```scala
case kyo: Kyo.Defer[AX, Y, T, S2] @unchecked =>
    if armed && Safepoint.stopped(slot) then park(kyo, contA, contB)
    else loop(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)), ctx)
```

One poll point suffices, for the same reason it does in the reference: the budget makes the
Defer arm inevitable. Every strict application gates on `Safepoint.enter`; a drained budget
turns applications into deferrals, and every explicit `flatMap`/by-name builds a Defer
outright, so a pending stop is observed within one budget period of pure strict work and
usually much sooner. The proto needs no answers-loop bail (the reference's `armed` threading
into `dispatchContFast`/`dispatchLoopFast`): the handleLoop continue lane routes through the
main match every iteration, so it passes the poll's arm on the same cadence as everything
else.

## The park

```scala
def park(curr: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2]): A < S =
    var acc: Any < Any = Effect.defer(curr, contA, contB).asInstanceOf[Any < Any]
    while !stack.isEmpty do
        val h  = stack.handler
        val st = stack.state
        val k  = stack.cont
        acc = new Kyo.Handle[Effect, Any, Any, Any, Any, Any]:
            def value   = acc'   // the acc read before this wrap
            def handler = h.asInstanceOf[...]
            def state   = st
            def cont    = k.asInstanceOf[...]
        stack.pop()
    acc.asInstanceOf[A < S]
```

(Shape, not final code: the builder reads through the same erasure-forced casts the eval's
own stack reads use, and the acc capture is a local, not a recursive reference.)

Points that make this smaller than the reference's park (`:472-484`):

- **Empty stack, no wrap**: identity conts and an empty stack return the value alone, the
  reference's own fast path.
- **No `parkable` guard** (`:490-500`). The reference refuses to park in front of a `Binding`
  or a `BindingStep` receiver because a resource would exist that no drain can find. The
  proto has no `Binding` node and no finalizer registry; releases are structural
  (`Eval.release` walks the value, and every rebuilt `Handle` releases through its own
  `release`), so a parked value carries its releases with it and there is nothing a park can
  orphan. The reference's second poll at `:661` (park before entering a release-owning
  region) vanishes for the same reason.
- **No snapshot arrays, no `stack.restore`**. Resuming a parked value is evaluating it: the
  Handle arm re-installs each region, and no evaluator arm knows parks exist. The reference
  needed seven sites taught about `Park` (constructor, resume arm, `finalizeResources`,
  `dispatchFirst`, `EffectTrace`, `render`, `Isolate`); the proto needs zero, because every
  walker that has a `Handle` arm already sees through a park.
- **The final cast is the reference's own row-widening** (`:481-483`): the parked value's
  effects are answered by the regions rebuilt around it, and those travel with it. One cast,
  representation-assertion category, justified at the site.

The entry `ctx` the stack stores is dropped, deliberately: a re-installed region derives its
binding again from wherever it stands (the Handle arm's documented semantics, `:256-258`).
The caveat is the one already on file for the foreign-crossing path: a non-identity context
update does not survive re-installation. Latent today because every public suspend's `update`
is identity; the one ruling on context-update scoping covers crossing, park, and B1 together.

## What resumption means

The parked value is an ordinary computation: resumable by any `Eval.apply` or a later
`partial`, on any thread, because it is immutable data referencing immutable data (the state
values are copied into node fields at park time; the mutable stack is left clean for the
pool). Cross-thread resume needs no handoff protocol beyond ordinary safe publication of the
value itself.

## Work items

1. `Eval.partial` plus the `armed` split and the Defer-arm poll, as above.
2. The park builder. Stack needs no new surface: the innermost accessors plus `pop()` walk
   the entries in the order the wrap wants them.
3. Tests, reproduction-first, in `EvalTest`:
   - park mid-computation, resume, same answer as the unparked run;
   - a `handleLoop` with real state parked mid-loop resumes at the parked state, not the
     initial one;
   - stop pending before the slice returns the input and consumes the sentinel;
   - a settled value with an empty stack parks to itself;
   - a nested unarmed eval inside a slice does not park;
   - a throw during a slice still consumes the stop at the boundary;
   - resume on a different thread (jvm).
   Open item: the trigger differs per platform (`stop(thread)` on jvm, `deadline` on
   js-wasm), so the shared tests need a platform-neutral way to make a stop pending; read
   how the reference's partial tests do it before inventing one.
4. Benchmarks after: the whole `ProtoBench` class on both variants, per the measurement
   rules; the poll touches the Defer arm, which every row crosses.

## What this deliberately does not include

No caller. The reference's `partial` is driven by the scheduler in kyo-core; wiring that up
is that layer's migration, not the kernel's. The kernel deliverable is the capability, its
tests, and the measurement.
