# Isolate in the current kernel

What `kyo.kernel.Isolate` is, how it executes, and what it demands from a
kernel redesign. Sources: `kyo-kernel/.../Isolate.scala`, instances in
`kyo-prelude` (`Var.isolate`, `Emit.isolate`), consumers in `kyo-core`
(`Fiber`, `Async`).

## What it is

Isolate is the protocol for carrying effect state across fork boundaries
(fibers, parallel operations, detached computations). It is a library-level
protocol, not a kernel node: `Isolate[Remove, Keep, Restore]` with two type
members and three phases.

- `Remove`: effects the isolation discharges on the forking side.
- `Keep`: effects that stay available during isolated execution (for
  `Fiber.init` that is `Sync`; for `Async.foreach` it is
  `Abort[E] & Async`).
- `Restore`: effects that reappear after isolation, not necessarily equal to
  `Remove`.
- `type State`: the captured snapshot. `type Transform[_]`: the shape the
  isolated run produces (state paired with the result, typically).
- `capture[A, S](f: State => A < S): A < (Remove & Keep & S)` reads the
  current state on the forking side.
- `isolate[A, S](state, v: A < (S & Remove)): Transform[A] < (Keep & S)`
  runs the forked computation under a local handler seeded with the
  snapshot, producing the state-carrying wrapper.
- `restore[A, S](v: Transform[A] < S): A < (Restore & S)` unwraps and
  re-suspends effects to apply the transformed state on the outer side.

`run` composes the three; `nest` tunnels instead, returning
`A < Restore < (Remove & Keep & S)` so the caller decides when the restore
layer applies.

## The two state categories

The design splits exactly along the kernel's two effect kinds:

1. `ContextEffect` state is copied wholesale and never needs an instance.
   The derivation macro explicitly filters `ContextEffect` subtypes out of
   `Remove` before summoning instances. The copying happens in the kernel
   hook `Isolate.internal.runDetached`: a `KyoDefer` whose application
   receives the ambient threaded `Context` and hands the fork
   `context.inherit` (dropping `Noninheritable` entries) plus
   `safepoint.saveTrace()` for diagnostics continuity. The fork point reads
   the environment exactly the way a context read does: by being applied
   with it.
2. Stateful arrow effects (`Var`, `Emit`, `Memo`, `Check`) need explicit
   instances, each implemented with ordinary public handlers. `Var.isolate`'s
   base: `capture = Var.use(f)`, `isolate = Var.runTuple(state)(v)` with
   `Transform[A] = (V, A)`; the `restore` policy is the strategy surface:
   `update` re-suspends `Var.setWith` with the final value, `merge` combines
   with the outer value through a user function, `discard` drops the state.

## How restore actually reaches the other side

In `Fiber.initUnscoped`:

```scala
Isolate.internal.runDetached((trace, context) =>
    isolate.capture { state =>
        val io = isolate.isolate(state, v).map(r => isolate.restore(r))
        IOTask(io, trace, context).asInstanceOf[Fiber[A, reduce.SReduced & S2]]
    }
)
```

`restore` is mapped inside the fiber's own computation, but its effects
(`Restore`, the `S2` in `Fiber[A, reduce.SReduced & S2]`) are not handled in
the fiber: they stay pending in the fiber's result type and execute on
whoever joins. The fiber tunnels the effects: the isolated run happens under
the fork's local handler, and the final `Var.setWith` (say) suspends until
the joiner's handler receives it. That is why `Remove` and `Restore` are
separate parameters: the fork discharges the full effect but only the
restore policy's residue crosses back.

## Composition and derivation

- `andThen` pairs states, nests `Transform`s, and sequences the phases, with
  an `Identity` fast path on both sides. Ordering is user-visible (capture
  and restore order), which is why explicit composition exists at all.
- The `derive` macro flattens an intersection `Remove`, drops anything in
  `Keep`, drops `ContextEffect`s, summons an instance per remaining
  component, and folds them with `andThen`. Missing instances produce the
  long pedagogical error listing the four escalation options.
- Deliberate non-instances: `Abort` and `Choice`. Short-circuiting effects
  get no isolation because derivation order would change results depending
  on which handler runs first. Handling them before the fork is the
  supported route.

## What Isolate costs in the current kernel

- One `KyoDefer` per fork point (`runDetached`), applied with the ambient
  context: the fork inherits context by riding the same threaded-environment
  machinery as context reads.
- The isolate phases are ordinary handler runs: `isolate` pays a full
  `handleLoop` over the forked computation (with the per-hop re-wrap
  economics analyzed in kernel-handler-sync-path-analysis.md), `restore`
  pays a suspension that crosses the fiber boundary as data.
- `andThen` composition allocates one tuple per capture and nests the
  `Transform` wrappers; `Identity` elides itself.

## What this demands from the kernel redesign

Isolate needs no kernel nodes of its own, but it consumes three kernel
capabilities that the new design must preserve:

1. An ambient-environment read at an arbitrary program point, with
   inheritance filtering. This is the hard constraint on the HandleContext
   design: a forked computation `v` does not carry the enclosing context
   delimiters (they wrap the fork expression; they are not inside `v`), so
   fork points must be able to materialize the current environment from the
   drive, not merely search the parked chain per read. Chain-resident
   delimiters alone cannot answer `runDetached`; the drive needs an
   environment view it can snapshot, plus the `Noninheritable` filter.
2. Handler-into-value with completion: `isolate` is exactly the
   `HandleLoop` shape (state threaded, `done(state, value)` producing
   `Transform[A]`). The taxonomy's HandleLoop is the primitive Isolate
   instances are written against.
3. Re-suspension as data: `restore`'s pending effects must survive being
   stored in a fiber result and resumed under a different drive on a
   different thread. In proto3 terms, a parked chain shipped across threads,
   which the immutability rules already guarantee, plus whatever handler
   re-installation the joiner side implies.

Plus the diagnostics hook: `saveTrace` at detach. The proto line's
equivalent is carrying frame provenance across the fork.

One simplification opportunity worth noting for the discussion: in the
chain-delimiter model, `restore`'s tunneling already has a structural home
(the restore suspension is just part of the shipped chain), and `Keep` is
simply the effect row the fork's drive serves. The genuinely new obligation
is only item 1, the snapshotable environment at fork points.
