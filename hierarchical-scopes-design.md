# Hierarchical scopes

Status: design, unbuilt. Written to be rejected on paper before code exists.

Target: `Scope.run` never returns while a computation it spawned still holds a resource,
without any thread blocking, without joining a fiber, and with `interrupt` unchanged as a
pure signal.

## 1. What exists today

`ContextEffect` already carries the full hook set. `handle` takes `derive`, `fork`, `join`,
plus `done` and `release` (`ContextEffect.scala:278-301`), and `Handler.ContextHandler`
declares them along with `borrow`, `defers`, `reenter` and `discharge`
(`Handler.scala:209-231`).

`Bracket` uses them non-trivially. Its `fork` is deliberate:

```scala
def fork(parent: Cell)                        = Cell.inert          // Bracket.scala:101
def join(parent: Cell, fk: Cell, child: Cell) = parent              // Bracket.scala:102
```

so a spawned fiber gets a cell that "neither completes, releases, nor refuses"
(`Bracket.scala:29`), and cannot release the parent's bracket.

`Scope` uses none of them. It goes through `handleInheritable`, which hard-codes the trivial
pair (`ContextEffect.scala:197`):

```scala
handleInheritable(effectTag)(derive)(v) =
    handle(effectTag)(derive, (parent: A) => parent, (parent: A, _: A, _: A) => parent)(v)
//                            ^^^^^^^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//                            fork: hand out the     join: keep the parent's value,
//                            very same object       discard the fork's
```

and `Scope.run` calls exactly that (`Scope.scala:143`):

```scala
ContextEffect.handleInheritable(Tag[Scope], finalizer, _ => finalizer)(v)
```

Read the `derive` carefully, because it is easy to misread. `handleInheritable(tag, ifUndefined,
ifDefined)` expands to `outer.fold(finalizer)(_ => finalizer)`, and both arms are the fresh
finalizer this call just built. So a nested `Scope.run` does get a scope of its own. What it does
not get is a *link* to the enclosing one: the two registries do not know about each other.

On the normal path that link is redundant, which is why its absence has never shown up as a bug.
The inner run closes and awaits inline before returning (`Scope.scala:147-151`), and
`Abort.run[Any]` routes every completion including an abort through there, so the inner's
resources are released before the outer sees control again. Containment comes from sequencing
rather than from bookkeeping.

It is the two cases where sequencing does not hold that are broken:

- **A fork.** `fork(parent) = parent` hands a spawned computation the parent's own registry
  instead of a scope of its own. Nothing in the parent's control flow waits for it, and nothing
  knows to. That is D3, and it is why registering from a fork that outlives the parent hits a
  closed queue: the message at `Scope.scala:195-200` reads "This finalizer is already closed.
  This may happen if a background fiber escapes the scope of a 'Scope.run' call." With a real
  child scope that is not an error, it is the thing the parent waits on.
- **The unwind path of a nested run.** `Sync.ensure(finalizer.close)` (`Scope.scala:145`) fires
  `close` without `await`, and cannot await, because the kernel bracket release is synchronous.
  `close` spawns a fiber for the finalizer tasks and returns, so on an interrupt the inner's
  finalizers are still running when the outer proceeds, and the outer's own ensure behaves the
  same way. Nothing in the chain waits for anything.

The second case is why the nested run needs registering too, and not only for symmetry: a parent
that holds its children is the only thing that can cover the path where sequencing fails.

## 2. The shape

A `Finalizer` is today a `Queue.Unbounded` of finalizers plus a completion `Promise`
(`Scope.scala:177-180`). It gains two things, and the three uses are kept apart so nothing can
mis-order:

```scala
sealed abstract class Finalizer:
    // phase 3, unchanged: the resources this scope itself owns
    def ensure(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame): Unit < Sync

    // phase 1: told to stop. This is where Fiber.init's interrupt goes.
    private[kyo] def onClose(signal: Unit < Sync)(using Frame): Unit < Sync

    // phase 2 membership: a child scope, linked at birth, unlinked when it has closed
    private[kyo] def link(child: Finalizer)(using Frame, AllowUnsafe): Unit
    private[kyo] def unlink(child: Finalizer)(using Frame, AllowUnsafe): Unit
```

Membership cannot reuse the existing queue, because it needs removal. A long-lived parent (an
actor loop, a server, a stream that spawns per element) forks continually, and a structure
that only ever accumulates grows without bound even though almost every child finished long
ago. So membership is a concurrent set with add-at-birth and remove-on-close, the shape
`Actor` already uses for its reply waiters (`discard(waiters.remove(entry))`).

The completion promise stays the coordination channel, and it is already there.

## 3. Membership is created at the two entry hooks

`ContextHandler` has an entry hook for each of the two ways a scope is born. Today `fork` is flat
and `derive` builds a real scope but links it to nothing:

| | entry | exit | today |
|---|---|---|---|
| nested `Scope.run` | `derive(outer)` | `done` / `release` | own scope, unlinked |
| forked scope | `fork(parent)` | `done` / `release` on the forked region, and `join` when it restores | no scope at all |

Both entry hooks create a child and link it into the parent:

```scala
ContextEffect.handle(Tag[Scope])(
    derive = outer => outer.fold(root)(_.newChild()),   // nested run: a child of the enclosing scope
    fork   = parent => parent.newChild(),               // isolate crossing: the spawn gets its own scope
    join   = (parent, _, _) => parent,                  // linkage already happened at fork
    done   = state => state.closeDetached(Absent),
    release = (state, ex) => state.closeDetached(Present(ex))
)(v)
```

`fork` is the larger change: it gives a spawned fiber a scope of its own instead of a share in
the parent's registry, the same non-trivial choice `Bracket` already makes. `derive` is the
smaller one: the scope it returns is already correct, and all that is added is the link, which is
what lets a parent cover the unwind path its child cannot await on its own.

### `derive` must stay pure

This is a trap worth naming, because it would be found at runtime rather than by the compiler.
`derive` is not called once per region. The abandonment walk calls it to reconstruct the state
of a `HandleContext` that was never installed (`Eval.scala:538`):

```scala
collected += hc.derive(Maybe.empty).asInstanceOf[AnyRef]
```

A `derive` that allocates and links a child would therefore allocate and link a stray child on
every abandonment walk, and that child would never close, so the parent would wait on it
forever.

The repair is to keep the construction where it already is. `Scope.run` builds its finalizer
once inside `Sync.Unsafe.defer`, so it should also read the enclosing finalizer and link there,
leaving `derive` the constant function it is today:

```scala
Sync.Unsafe.defer {
    ContextEffect.suspendWith(Tag[Scope], Finalizer.none) { outer =>
        val finalizer = Finalizer.Awaitable.Unsafe.init(closeParallelism)
        outer.foreach(_.link(finalizer))
        ContextEffect.handleInheritable(Tag[Scope], finalizer, _ => finalizer)(v) ...
    }
}
```

`fork` has no such problem: it is called once per crossing, at `Isolate.scala:308`.

## 4. Membership is dissolved at the forked region's exit hooks

This is the part where I had it wrong in discussion, and the code is unambiguous.

`join` fires from `Isolate.Contextual.restore` (`Isolate.scala:274-286`), and for a spawned
fiber the restore is not part of the fiber at all. `IOTask` composes it onto the value the
promise answers with (`IOTask.scala:535`):

```scala
boundary(isolate.isolate(state, body))(t => completeDiscard(Result.succeed(isolate.restore(t))))
```

So `join` runs in the *consumer's* stack when someone reads the fiber's result. A fiber whose
result is never consumed never joins. Under a rule of "membership is dissolved only in
`join`", such a fiber's entry never drains and the parent waits forever, which is strictly
worse than today's leak.

The hooks that do fire unconditionally, in the child's own stack, are the region's own exit
hooks:

- normal completion: `contextExit` calls `hc.done(state, value)` (`Eval.scala:353`);
- abandonment: the release walk collects the `Park`'s entries and calls `release`
  (`Eval.scala:542-554`, `Eval.scala:520`).

So the exit event that matters for lifetime is "the forked extent ended", and it is already
wired. `join` answers a different question, state reconciliation, and for `Scope` the joined
value is `parent` regardless, since a child scope is a separate registry with nothing to fold.

### The `Forked` forwarding gap

There is one obstacle, and it looks like drift rather than intent. `Forked`, the wrapper that
stands in for a region across a crossing, forwards five members and drops the rest
(`Isolate.scala:291-299`):

```scala
final private class Forked[State, E <: ContextEffect[State], A, S](val origin: ...)
    extends Handler.ContextHandler[State, E, A, S]:
    def tag                                 = origin.tag
    def derive(outer: Maybe[State])         = origin.derive(outer)
    def fork(parent: State)                 = origin.fork(parent)
    def join(parent, forked, child)         = origin.join(parent, forked, child)
    override private[kyo] def reenter(state) = origin.reenter(state)
```

`done`, `release`, `discharge`, `borrow` and `defers` are not forwarded, so they resolve to the
no-op defaults on `ContextHandler`. A forked region therefore receives no lifecycle
notification at all.

This is unobservable today, which is why it has not been caught: the only handler with a
non-trivial `fork` is `Bracket`, and it forks to `Cell.inert`, whose finalizer is `_ => ()`
(`Bracket.scala:79`), so forwarding or not makes no difference. `Scope` is the first effect
that needs it.

The repair is to forward the remaining hooks. With that in place the rule is:

- membership added in `derive` and `fork`;
- the child's close begun by the forked region's `done` or `release`;
- membership removed when the child's close promise completes, which is the same event phase 2
  waits on, so there is one source of truth and no way for the two to disagree.

`join` stays `(parent, _, _) => parent`.

## 5. The close, phased

Ordering the interrupts before the waits is not a sort key, it is the phase structure:

```scala
def close(ex: Maybe[Error[Any]])(using Frame): Unit < Async =
    signals.drain.map(Kyo.foreachDiscard(_)(identity))        // 1. tell every child to stop
        .andThen(Kyo.foreachDiscard(children.snapshot)(_.await)) // 2. wait for their scopes
        .andThen(runOwn(ex))                                     // 3. release what I own
```

Phase 3 after phase 2 is the safety property: a parent never releases what a child may still
be holding. If a parent opens a connection, forks a child that uses it, and then closes,
running the parent's own release while the child is alive is a use-after-release, and ordering
only the interrupts does not prevent it.

Phase 1 before phase 2 is why the naive repair does not work. Registering the await as another
finalizer puts signalling and waiting in the same collection, which at the default
`closeParallelism` of 1 deadlocks whenever the wait sorts first. Separating them removes the
question.

It also means the signal stops being a finalizer. Today `Fiber.init` registers `_.interrupt`
as a queue entry (`Fiber.scala:137`), which is precisely why it can be mis-ordered against
anything else in that queue. Under the hierarchy it moves to phase 1:

```scala
// today
Scope.acquireRelease(initUnscoped[E, A, S, S2](v))(_.interrupt)

// becomes
Scope.acquireRelease(initUnscoped[E, A, S, S2](v))(_ => Kyo.unit)
    .map(fiber => Scope.onClose(fiber.interruptDiscard).andThen(fiber))
```

`acquireRelease` is still what spawns, so the interrupt-atomicity fix from the live review
keeps holding: the fiber and its registration arrive in the same step, with no preemption
point between them.

`interrupt` itself is untouched: still `Boolean < Sync`, still fire and return. Nothing
anywhere joins a fiber.

## 6. Finalizers stay async

`Scope.ensure` keeps `Maybe[Error[Any]] => Any < (Async & Abort[Throwable])`, phase 3 runs
them as effects, and phase 2 is an ordinary suspension. No thread blocks.

The boundary worth stating: the kernel's bracket release stays synchronous,
`(A, Maybe[Throwable]) => Unit`, and must, because it runs on abandonment where nothing is
installed to answer for an effect. So `done` and `release` can only *begin* a child's close and
return; they cannot await it. That is fine, because `close` already completes its promise from
a spawned fiber (`Scope.scala:214-220`), and phase 2 waits on the promise rather than on the
hook.

## 7. Invariants

1. **A child is linked before it can hold anything.** Linking happens in `derive` and `fork`,
   which run at region entry, before any body code.
2. **A child unlinks only after its own finalizers have run.** Unlinking is driven by the close
   promise, which completes after phase 3.
3. **Every forked region gets exactly one exit notification**, `done` or `release`. This is what
   the `Forked` forwarding must preserve; two notifications would close a child twice (harmless,
   since `close` is idempotent) and zero would hang the parent.
4. **`derive` is pure.** See section 3.

Invariants 2 and 3 are the load-bearing ones. If 2 breaks, the parent stops waiting too early
and the guarantee is silently false. If 3 breaks in the "zero" direction, the parent hangs.

## 8. Failure modes

The design converts a leak into a hang, and that is the point, but it means the failure modes
change shape and should be listed plainly.

| condition | today | after |
|---|---|---|
| child still holding at parent exit | parent returns, resource leaks | parent waits |
| child ignores its interrupt | parent returns, resource leaks | parent waits as long as the child holds |
| forked region gets no exit hook | nothing to notice | parent hangs |
| child unlinks before closing | not expressible | parent returns early, guarantee silently false |

The third row is the one to verify empirically rather than reason about. A spawn whose `IOTask`
is discarded before it ever runs must still reach `Eval.release`, or its child scope is never
notified. I have not confirmed that path and it is the first thing a prototype should test.

## 9. Open decisions

**A child that never unwinds blocks its parent forever.** This is not a bug under the design,
it is the honest reading of "the scope guarantees release", and it is what the whole structure
exists to provide. The alternative is to bound phase 2, which reintroduces the "returned while
something was still held" hole. I recommend leaving it unbounded and documenting it.

**Does a child's finalizer failure propagate into the parent's close, or is it only reported?**
`Scope.run` already answers this for its own finalizers by logging, `Log.error("Scope finalizer
failed", ...)` at `Scope.scala:210-212`. Applying the same rule to children is consistent and
is what I would do, but it is a real decision and it is the only one left that changes
observable behaviour.

## 10. Cost

Two things I would measure before committing, because either can kill the design and both are
cheap to answer:

1. **Per-crossing cost.** Today `fork(parent) = parent` is free. After, every isolate crossing
   allocates a child `Finalizer` (a queue, a promise, a set) and mutates the parent's membership.
   Crossings are on the hot path of every `Async` operation. This is the single number most
   likely to reject the design, and it should be measured first.
2. **The degenerate scope.** A `Scope.run` with one finalizer, no children and no fork should
   not pay for machinery it does not use. Lazy allocation of the membership set and the signal
   list keeps that case at roughly today's cost.

## 10b. Scope of applicability: it only helps where a `Scope` is installed

Found by an external audit of this doc against `KERNEL-BRACKET-SCOPE-REPORT.md`, and it qualifies
everything in section 11.

`Isolate.Contextual.fork` walks the regions present in the stack snapshot (`Isolate.scala:302-311`)
and calls `origin.fork(...)` once per region. If no `Scope` region is installed when a crossing
happens, there is no entry for it, so no child scope is created and nothing is owned. The hierarchy
therefore changes nothing for a spawn that occurs outside any `Scope.run`.

The concrete case is `Async.timeout`, whose row is `A < (Abort[E | Timeout] & Async & S)`
(`Async.scala:174-176`) and carries no `Scope`. Its orphan (`Async.scala:207-212`) is defect 4 in
that report, and this design does not reach it unless the caller happens to be inside a `Scope.run`.

Three conditions have to hold together for the hierarchy to own a given spawn:

1. a `Scope` region is installed in the caller at the moment of the crossing;
2. the `Forked` forwarding gap in section 4 is fixed, so the forked region receives `done`/`release`;
3. the abandonment path reaches a spawn whose task has not run yet, which section 8 already lists as
   unconfirmed.

Only the first is new here, and it is the one that cannot be fixed inside this design: it is a
property of the call site, not of the mechanism. Anything that must be owned regardless of caller
context needs its own bracket at the spawn, which is the `ensureMap` fix, not this.

## 11. What this closes

- **D3** directly: `Scope.run` waiting on member scopes is the missing guarantee.
- **D2**: `merge`, `mergeHaltingLeft`, `collectAll*` and `mapPar` orphan internal fibers because
  nothing owns their lifetime; a child scope per crossing is that owner, subject to section 10b:
  only where a `Scope` is installed at the crossing.
- The `Closed` message at `Scope.scala:195-200` stops describing an error and starts describing a
  child, which removes the failure it names rather than reporting it better.
- The unwind path of a nested `Scope.run`, where `Sync.ensure` fires `close` without `await` and
  no one in the chain waits. Sequencing covers the normal path already; membership is what covers
  this one.

It does **not** remove `Bracket`, and the divergence between the two fork policies is deliberate
and should be written down where both can be read: `Bracket` hands a fork an inert cell so a
fiber cannot release the parent's bracket; `Scope` hands a fork a live child so the parent can
wait for it. Both are right for their own semantics.

## 12. Validation plan

Reproduce first, in this order:

1. A pin that a `Scope.run` containing a `Fiber.init` whose body holds a resource does not return
   until that resource is released. This is the existing D3 pin at `ScopeInterruptTest:135`,
   which should stop being order-dependent once the guarantee is real.
2. A pin per D2 site: `merge`, `mergeHaltingLeft`, `collectAll`, `mapPar`.
3. A pin that an interrupted outer `Scope.run` does not return before a nested `Scope.run`'s
   finalizers have finished. This is the unwind path, and it should fail today for the right
   reason: the inner's `close` is fired but never awaited.
4. A pin for invariant 3: a spawn abandoned before it runs still closes its child scope.
5. A pin that a child finalizer failure does not prevent the parent's phase 3 from running,
   whichever way decision 2 in section 9 goes.
6. The crossing benchmark, before anything else is believed.

Cross-platform: everything in the live review so far is JVM only. `Scope`, `Isolate` and the
kernel hooks are shared source, so JS and Native have to run before this is called done.

## 13. What the hierarchy makes expressible: held scopes and `Closeable`

The phased close is worth more than the guarantee it was built for. Once a scope can signal its
members and then wait for them, "interrupt and wait until the resources are actually freed" stops
needing a primitive.

### `interruptAwait` is a scope operation, not a fiber one

A fiber has no backpressure on closing and should not grow any: `interrupt` stays
`Boolean < Sync`, fire and return. The waiting belongs to the scope the fiber was spawned into,
because that is what knows when the resources are released.

```scala
val s = Scope.open()                  // a child of the current scope, handed back as a value
val f = s.run(Fiber.init(body))       // f's interrupt is a phase-1 signal on s
...
s.closeAwait                          // 1 signal, 2 await members, 3 release own
```

Per-fiber granularity comes from giving the fiber its own scope, not from teaching `Fiber` to
wait. Nothing joins a fiber and no kernel mechanism is added.

### Why `close` and `closeAwait` are two operations

This is forced by the phase structure rather than being a convenience. Phase 1 must signal every
member without waiting on any, and phase 2 then waits. If closing a member always awaited it, the
close would run depth-first and a scope with two children would wait the first out before
signalling the second.

The naming already exists in the codebase: `Channel.close` / `Channel.closeAwaitEmpty`, and the
same pair on `Queue`.

### Membership beyond parent/child

Today the only way to obtain a scope is `Scope.run`, which ties it to a lexical block, so the only
relationship available is enclosing/enclosed. A scope that is a *value* can be held, passed, and
closed from outside the block that created it, and membership generalizes with it: an entry stops
meaning "a scope I lexically contain" and starts meaning "something I own that can be signalled
and awaited".

That type does not exist yet. `Scope.acquire` is pinned to `java.lang.AutoCloseable`
(`Scope.scala:107`), whose `close()` is synchronous and throws, while the kyo-native types spell
the pair themselves (`Channel`, `Queue`, `Hub`, `Meter`). A `kyo.Closeable` carrying the signal
and the await is the type a membership entry wants, and it would let `Scope.acquire` take a
kyo-native resource with an async close rather than only a Java one.

Whether to introduce it is a separate decision from the hierarchy, but the hierarchy is what makes
it load-bearing rather than cosmetic, so it should be decided alongside.

### Two hazards a held scope introduces

- **A handle nobody closes** is exactly the manual resource management the effect exists to
  remove. The repair is that `Scope.open` returns a child of the *current* scope, so forgetting is
  safe (the enclosing scope closes it at phase 2) and closing early is an optimization rather than
  an obligation.
- **A cycle hangs.** Awaiting a scope that transitively awaits you is a deadlock, and held handles
  make it reachable for the first time: passing a handle downward lets a child await its ancestor.
  Lexical nesting cannot express that today, so the hazard arrives with the value form, not with
  the hierarchy.

## 14. Rejected alternatives

**Tagging queue entries so interrupts sort first.** Fixes the deadlock and leaves the parent's
own finalizers interleaved with waiting for children, so the use-after-release in section 5
survives. The phases subsume it.

**Membership dissolved in `join`.** Section 4. `join` does not fire for a fiber whose result is
never consumed, so the parent would hang. `join` is the wrong event: it answers reconciliation,
not lifetime.

**Firing `join` unconditionally by hoisting it out of the `j >= 0` guard**
(`Isolate.scala:314-336`), with `Forked` capturing the parent state at fork time so a parent
value is available when the origin is not on the stack. This is a real and small change, and it
would make `join` uniform, but it does not help: the guard is not why `join` is missed for a
detached fiber. The restore is never reached at all. Worth doing on its own merits if some other
effect needs it; it is not part of this design.

**Making `Scope` the only bracketing mechanism, removing `Bracket` and `Sync.ensure`.** Ruled
out. `Bracket` lives in `kyo-kernel` and `kyo-prelude` uses it (`Stream.splitAtWith`), and
prelude sits below core, so it cannot reach `Scope`. Removing it would also relocate rather than
remove the replay machinery: a `Scope` region under a replaying handler closes on the first shot
and `close` is idempotent, so the second shot would run against released resources silently,
where `Bracket` at least refuses.
