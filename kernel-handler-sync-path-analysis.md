# The sync path: current kernel handlers vs the Handle* node design

Analysis of how `ArrowEffect` and `ContextEffect` execute in the current
kernel, focused on the property the kernel2 draft lost: when a handler is in
scope and a suspension matches, the current kernel executes the handler
clause synchronously, on the spot, with no node built for the handled step.
The kernel2 `Handle*` classes instead reify handling as a `Pending` node and
defer all interpretation to a future eval loop, so even a discharg eable
match suspends. File references are to the current kernel sources.

## The current kernel's execution model

Three load-bearing facts:

1. Construction eagerly evaluates. `map` on a value that is not suspended
   applies `f` immediately, guarded by the Safepoint depth protocol
   (`Pending.scala`, `mapLoop`: the pure arm runs
   `safepoint.enter` / `f(value)` / `exit`, and only reifies an
   `Effect.defer` when the depth guard trips). A `KyoContinue` is allocated
   only when the source is genuinely suspended.
2. Handling is interpretation, not construction. `ArrowEffect.handle` and
   `ContextEffect.handle` are `inline def`s whose bodies are drive loops.
   Calling handle starts executing the computation right there, at the call
   site, in bytecode owned by that site.
3. `eval` is nearly trivial (`Pending.scala:406`): it only drives `Defer`
   suspensions and rejects anything else. By the time eval runs, every real
   effect has already been discharged inline by the handler loops. Eval is a
   trampoline drain, not an interpreter.

## ArrowEffect: the sync path on a match

`ArrowEffect.handle`'s loop (`ArrowEffect.scala:118`) has exactly three arms:

| arm | condition | what happens | allocation |
|---|---|---|---|
| match | `effectTag <:< kyo.tag` | `Safepoint.handle(kyo.input)(eval = handle[Any](kyo.input, kyo(_, context)), ...)`: the handler clause runs now, on the current stack | none for the handled step |
| foreign | other suspension | one `KyoContinue` wrapping it, whose `apply` re-enters `handleLoop` at resume | one node per foreign hop |
| pure | anything else | `kyo.unsafeGet` | none |

Details that make the match arm fully synchronous:

- The continuation handed to the clause is `kyo(_, context)`: the
  suspension's own stored continuation, invoked directly. When the clause
  calls `cont(x)`, that application also executes immediately
  (`KyoSuspend.apply` runs the continuation body, which drives nested
  `mapLoop`s eagerly), and `handleLoop(_, context)` continues on the result.
  Handling one operation and resuming it is a plain nested call sequence.
- The clause is an `inline` parameter. Its bytecode fuses into the per-site
  loop copy, so clause dispatch is not a virtual call and the loop plus
  clause plus continuation applications form one compiled unit per handle
  site. This is the same per-site-profile structure the proto3 inline
  trampoline restored, and it is why the census measured the kernel
  suspension row at 16 B/op: construction and interpretation are adjacent in
  one unit, so escape analysis scalar-replaces the chain nodes that proto3
  pays for (152 B/op).
- The only path from the match arm to a reified suspension is the depth
  guard: `Safepoint.handle` returns `Effect.defer(suspend)` when `enter`
  fails, deferring re-entry of the same loop.

So a program whose effects are all handled by in-scope handlers executes to
completion during the handle calls themselves. Nothing waits for eval except
depth-guard Defers.

## ContextEffect: the match is a map lookup, not a handler at all

`ContextEffect` goes further: at read time there is no handler dispatch of
any kind.

- `suspendWith` (`ContextEffect.scala`) allocates one `KyoDefer` whose
  `apply(v, context)` reads `context.getOrElse(effectTag, default)` and runs
  the continuation on it, again under `Safepoint.handle` (the two-argument
  form, whose overflow arm re-defers `this`: the same node, zero
  re-park allocation).
- `handle` does not interpret reads. It installs a binding: its loop wraps
  each suspension crossing the scope in a `KyoContinue` whose `apply(v,
  context)` extends the context (`context.set(tag, ...)`) and delegates
  inward with the updated context. The pure arm is `unsafeGet`: handling an
  already-computed value costs nothing.
- Context flows outside-in as an argument of every `apply` call: `eval`
  passes `Context.empty`, each context handler's wrapper adds its binding,
  and the innermost read Defer sees the accumulated environment.

So the "match" for a context effect is an O(1) lookup in a threaded
environment at the moment the drive reaches the read. The handler
contributes no per-read code; its cost is scope maintenance: one wrapper
node per suspension hop crossing the scope, which is also what makes
bindings survive parks (the wrapper re-installs them on every resume).

## What the sync path costs

The current kernel pays for this model in three places, all of which the
proto line was designed to attack:

1. Safepoint on every eager step: `enter`/`exit` bracket each map and each
   handled operation, with trace machinery behind them, and overflow reifies
   Defers. Eager execution is what makes the depth guard mandatory.
2. Inline drive loops replicated at every handle and map site: compile time
   and code size (proto goal 2 in pending-kernel-design.md).
3. One `KyoContinue` per handler crossed per park: a suspension crossing N
   installed handlers re-wraps N times on every traversal.

## The kernel2 Handle* contrast: handling as construction

The kernel2 draft's handle was:

```scala
v match
    case v: Kyo.Pending[A, E & S] @unchecked =>
        new Kyo.HandleCont[I, O, E, A, S & S2]:
            def prev = v
            def apply[C](input: I[C], cont: O[C] => A < (E & S & S2)) = handle(input, cont)
    case _ =>
        v.unsafeGet
```

The only synchronous arm is the already-pure one. A pending computation,
even one whose next suspension matches the tag and could be discharged
immediately, gets a `HandleCont` node wrapped around it and returns to the
caller unexecuted. Consequences:

- Allocation per handle call over any pending computation, match or not.
- The handler clause no longer executes in caller-owned bytecode. All
  interpretation concentrates in a central eval loop, which is exactly the
  pooled-dispatch structure the profile-pooling law says forfeits
  speculative devirtualization and fusion (proto2 measured this; the proto3
  step protocol and inline trampoline exist to undo it).
- The escape-analysis win disappears: construction and interpretation are
  separated by a node graph, so chain nodes materialize instead of
  scalar-replacing. The census's 16 vs 152 B/op gap on the suspension row is
  the same separation measured from the other side.
- Clause side effects move in time: nothing a handler does happens at handle
  time, and `eval` becomes mandatory even for fully handled synchronous
  programs.
- The one genuine advantage: a partially handled computation is a value that
  carries its installed handlers. Parking across a handler boundary is free
  (the handlers are already data), where the current kernel pays the per-hop
  re-wrap and proto3's evalPartial park drops the handler entirely (the
  outer driver owns re-installation). Handlers-as-nodes also permit
  introspection and first-class manipulation of installed handler stacks.

## Where proto3 stands

proto3 kept the current kernel's model for handling: `eval`/`evalPartial`
are inline per-site drive loops that execute now; handling is not a node.
What it changed is underneath: continuations are first-class data
(Arrow/AndThen/Offset), the depth guard is slot-based with structural
cadence instead of per-step enter/exit, and parking is a return from the
loop. The census shows the trade it accepted: chains materialize per park
(the 152), in exchange for stack safety by structure and continuation
identity.

## Implications for the handler-kind taxonomy

The taxonomy from the kernel2 session should land on the sync-path side, as
modes of the drive, not as constructed nodes:

- `HandleResume` and `HandleStop` are precisely the modes where the sync
  path is strongest: resume is clause-now plus continue-in-place (no
  decomposition, no park), stop is clause-now plus discard. As drive-loop
  modes they inherit the current kernel's zero-node handled step; as nodes
  they would pay construction for the cases that need it least.
- `HandleContext` should follow the current kernel's threaded-environment
  model: bindings installed as drive state and read by lookup, never a node
  per read and never clause code per read. The open question is scope
  maintenance across parks, where the current kernel's per-hop re-wrap and
  the Handle*-node free park are the two known points in the design space.
- `HandleCont` is the one mode that must materialize the continuation by
  definition; it is where the proto3 step protocol already concentrates its
  caller-site dispatch.
- If parking with handlers installed becomes a requirement (resumable
  computations that retain their handler stacks), that is the argument for
  some reified representation, and it should be weighed as such: the cost is
  the always-suspend model this analysis quantifies, and any hybrid (sync
  drive that reifies only at park) has to name where the reification
  allocation happens.
