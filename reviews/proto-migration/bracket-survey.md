# Survey: what closes the window between a resource existing and its release being owed, and what crosses a fork?

Two questions surveyed together because the same fiber machinery answers both. Citations are upstream
paths or verbatim docs; the incumbent's are file and line in this working tree, where `origin/main` has
no `Effect.bracket` (resource safety there is `Safepoint.ensure` plus `Scope`) and this branch carries
the landed kernel in which bracket is a primitive and `ContextEffect.handle` already takes `fork`,
`join`, and `release`.

## 1. Host interruption model: where can an interrupt land?

**Anywhere the thread allocates (GHC).** "an exception can only be raised when a thread reaches a *safe
point*, where a safe point is where memory allocation occurs" (`Control.Concurrent`), the most
permissive model surveyed. **At interpreter checkpoints (cats-effect 3, ZIO 2, Effect-TS).** CE3 gates
on `shouldFinalize() = canceled && isUnmasked()`, `isUnmasked() = masks == 0`
(`core/shared/src/main/scala/cats/effect/IOFiber.scala:1173-1176`), checked at async boundaries and
every 512 runloop reentries; ZIO on `shouldInterrupt() = isInterruptible() && isInterrupted()`
(`internal/FiberRuntime.scala:1459`), at trampoline boundaries, after an async resume, and at every
runtime-flag transition; Effect-TS the same as an `Interruption` bit in `RuntimeFlags`. **Only at
suspension (kotlinx):** "coroutine cancellation is cooperative". Java's `Thread.interrupt` is a flag
only blocking calls observe; Eio cancels where a fiber performs an effect.

**Only a park at a defer boundary (kyo, this tree).** No asynchronous interrupt exists: the eval stops
in front of a deferral, checks `Safepoint.stopped(slot)`, and parks
(`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Eval.scala:661`); cancellation is a holder
declining to resume a parked remainder, reaching the releases via `Eval.finalizeResources` (`:784-793`).

## 2. Acquire interruptibility

**Uncancelable by default, reopened by a caller-applied token (cats-effect 3, ZIO 2, Effect-TS, GHC).**
CE3's `Poll[F] extends (F ~> F)` passes into `uncancelable[A](body: Poll[F] => F[A])`, surfaced on
`bracketFull` and `Resource.makeFull` (`kernel/.../MonadCancel.scala:272,447`); ZIO's and Effect-TS's
`restore` both restore the *enclosing* interruptibility rather than unconditionally enabling it; GHC's
`mask` is never applied to `bracket`'s `before`. **With a separate interruptible tier (ZIO 2 only):**
`acquireReleaseInterruptible` (`core/shared/src/main/scala/zio/ZIO.scala:2767-2785`), where the type
pays, the release "is not allowed to access the resource produced by `acquire`" and takes only an
`Exit`. CE3 reaches the same place by documentation on `Resource.makeFull`: "the acquire action should
know how to cleanup after itself in case it gets canceled" (`Resource.scala:920-931`). **No primitive
(kotlinx):** `Closeable.use` is stdlib `try`/`finally` whose docs mention neither coroutines nor
cancellation. **Vacuous (kyo):** nothing interrupts inside a slice, so what is guarded is the boundary.

## 3. The settle-to-register gap: the mechanism

**One mask spanning both steps, with the interrupt check gated on it (cats-effect 3, ZIO 2, Effect-TS,
GHC).** In all four the guarantee is not that the window is short but that the check cannot pass while
the flag is set. CE3: `uncancelable { poll => acquire(poll).flatMap { a => guaranteeCase(poll(unit >>
use(a)))(release(a, _)) } }` (`MonadCancel.scala:447-454`), where `masks` never returns to zero across
the `flatMap` into `guaranteeCase` (itself `uncancelable`), so the `masks == 0` gate cannot pass in the
window. ZIO's scoped form is the most literal, `ZIO.uninterruptible(acquire.tap(a =>
ZIO.addFinalizerExit(exit => release(a, exit))))` (`ZIO.scala:2757`), putting the `ReleaseMap`
insertion inside the acquire's own region; its `acquireReleaseWith` is `uninterruptibleMask(restore =>
acquire().flatMap { a => ... restore(use(a)) ... })` with only `use` restored (`:5704-5717`). Effect-TS
is the same statement (`packages/effect/src/internal/fiberRuntime.ts`); GHC runs `before` and installs
the `onException` handler both inside `mask`.

**By refusing the boundary, with no mask (kyo, this tree).** The window is made unreachable instead.
`Effect.bracket` builds the binding through a `BindingStep`, not a `map`: "the step is what the eval's
park guard recognizes, so no slice can end between the resource existing and the scope that owes it
being installed, and its apply skips the budget gate so a pending stop cannot defer the bind against
that refusal" (`kyo-kernel/shared/src/main/scala/kyo/kernel/Effect.scala:146-150`, implementation
`:134-183`). The refusal is one line, conditioned on the binding actually owing a release:
`if armed && kyo.release.isDefined && Safepoint.stopped(slot) then` (`Eval.scala:661`), the park allowed
again once the finalizer is installed and reachable by a drain (`:658-659`). **Absent (kotlinx):** a
documented hazard, not a property; issue #1936 records a resource sent through a channel "can be lost if
either send or receive operation are cancelled in transit", fixed per-API by an opt-in
`onUndeliveredElement`, not by a masking primitive.

## 4. Finalizer edges and outcome data

**Nothing (GHC `bracket`, Eio `on_release`).** Base's release takes only the resource, `bracketOnError`
splitting the error edge by running only on it; Eio calls each hook with zero arguments
(`lib_eio/core/switch.ml`), outcome-blind by construction. **A three-way ADT (cats-effect 3, ZIO 2,
Effect-TS, Haskell `exceptions`).** CE3's `Outcome` is `Succeeded(fa: F[A]) | Errored(e: E) |
Canceled()`, mirrored by `Resource.ExitCase`; ZIO and Effect-TS fold all three into `Exit.Success |
Exit.Failure(Cause)` where cancellation is `Cause.Interrupt(fiberId)`, making interruption one more
constructor rather than a distinct edge. **A nullable cause (kotlinx):** `Job.invokeOnCompletion` gets
`cause: Throwable?`, null for normal completion, `CancellationException` for normal cancellation.

**A `Result` with a distinguished abandonment (kyo, this tree).** The release is
`(A, Result[Any, B]) => Any < Any` (`Effect.scala:140`) and three outcomes reach it: the completing
value, the exception an unwind passed, and `Finalizer.Abandoned` where an eval ended holding a
continuation nobody resumed. The source argues the third is a case, not a failure: "That is neither a
completion nor a failure, so it has a value of its own rather than borrowing an error the computation
never raised" (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Finalizer.scala:59-65`), a case "a
`Maybe[Error]` could not say" (`:34-40`). `Sync.ensure` then narrows to `Maybe[Error[Any]]` publicly
(`kyo-core/shared/src/main/scala/kyo/Sync.scala:116-123`).

## 5. Exactly-once

**An atomic flag on the finalizer (kyo).** `Finalizer extends AtomicBoolean`, running under
`if compareAndSet(false, true) then discard(Eval(release(resource, outcome)))`
(`Finalizer.scala:32,41-42`); atomic rather than a `var` because "a captured continuation can be resumed
on one thread while the eval that created it drains on another, so the two paths genuinely race"
(`:22-23`). The flag is set before the release runs, so a throwing release still counts as run.

**Deregistration by popping (cats-effect 3):** finalizers sit on a fiber-owned `ArrayStack`, normal
completion popping before proceeding and the cancellation loop popping as it runs, so each is either
popped-and-discarded or popped-and-run (`IOFiber.scala:1250-1256,1465-1475`). **A CAS'd region state
machine (ZIO 2, Effect-TS):** ZIO's `ReleaseMap` is an `AtomicReference[Running(nextKey, LongMap) |
Exited(exit)]`, `release` removing the key in the same CAS that runs it, and after the `Exited`
transition a later `add` runs the newcomer immediately while a second close is a no-op
(`core/shared/src/main/scala/zio/Scope.scala:213-226,265-359`). **Lexical (GHC, kotlinx).**

## 6. Finalizer execution context

**Arbitrary effects, masked while running (cats-effect 3, ZIO 2, Effect-TS).** CE3 increments `masks`
before any finalizer, "suppress all subsequent cancelation on this fiber" (`IOFiber.scala:1143-1145`),
documenting "Finalizers are always uncancelable" (`MonadCancel.scala:257-258`); ZIO's and Effect-TS's
release is simply never passed through `restore`. No system surveyed imposes a time bound. **Effects,
only interruptibly masked, hence the pitfall (GHC):** cleanup inside `mask` can still take an async
exception at any interruptible operation, so multi-step cleanup can be pierced midway; the fix is
cleanup under `uninterruptibleMask`, which `safe-exceptions` does by default, against a warning that it
otherwise leaves a blocked thread "unresponsive and unkillable". **Opt-in (kotlinx):** a suspending call
in the `finally` of a cancelled coroutine throws unless wrapped in `NonCancellable`.

**No effects at all (kyo, this tree).** The release is `Any < Any`, run through a bare `Eval(...)`
(`Finalizer.scala:42`), because "It has to be able to run where nothing is installed to answer for it,
which is what an eval that is ending can offer" (`Effect.scala:128-130`). `Sync.acquireReleaseWith`
re-admits `Sync & Abort[Throwable]` above the kernel by discharging the abort at the boundary,
`Abort.runWith[Throwable](release(resource))(_.getOrThrow)` (`Sync.scala:84-90`).

## 7. Finalizer failure policy

**Body error wins, release error suppressed onto it (kyo, Java).** The incumbent drains with
`case Present(fail) => if ex ne fail then fail.addSuppressed(ex)`, and on a completing path with nothing
in flight throws the first release failure with later ones suppressed onto it
(`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Stack.scala:567-595`); the identity guard exists
because a release rethrowing the exception it was told about must not self-suppress. This is the JVM
convention exactly: "the exception originating from the try block is propagated and the exception from
the finally block is added to the list of exceptions suppressed by the exception from the try block"
(`Throwable#addSuppressed`).

**Both aggregated into one cause (ZIO 2, Effect-TS).** ZIO folds `e.foldExit(_ ++ cause2, _ => cause2)`
where `++` is `Cause.Then`, sequential composition (`ZIO.scala:5704-5717`, `Cause.scala:34-41`); if only
release failed, its cause alone surfaces. Effect-TS is `internalCause.sequential`. **Body error wins,
release error to a side channel (cats-effect 3):** `guaranteeCase` runs the error-edge finalizer under
`.reportError`, handing the secondary throwable to `ExecutionContext#reportFailure`
(`IO.scala:523-529,967-970`). **Release error silently replaces the body's (GHC base):** ``onException
io what = io `catch` \e -> do _ <- what; throwIO e``, so a throwing `what` propagates and `throwIO e` is
never reached; `safe-exceptions` inverts this, dropping the cleanup's failure to keep the original.
Neither behavior is stated in prose by either package; both are read off the source.

## 8. Nesting order and parallel release

**LIFO everywhere, by five mechanisms.** CE3 pops an `ArrayStack`, "Nested resources are released in
reverse order of acquisition" (`Resource.scala:100-101`); ZIO gets it from key ordering, allocating
decrementing keys so "when we do the final iteration, the finalizers are already in correct order"
(`Scope.scala:216-222`); Effect-TS documents reverse order "just like in stack unwinding"; Eio prepends
then iterates and `resourcet` reverses explicitly; GHC and kotlinx get it from lexical nesting. The
incumbent walks backwards because "index zero is the outermost: a resource acquired inside another is
released before it" (`Eval.scala:810-811`), and `Scope` reverses before closing
(`kyo-core/shared/src/main/scala/kyo/Scope.scala:182`).

**Parallel release is always an opt-in on the region, never on the primitive.** ZIO and Effect-TS take an
`ExecutionStrategy` of `Sequential | Parallel | ParallelN(n)` when a scope is made or forked; CE3 offers
`Resource#both`, where finalizers run concurrently "but within *each* of the two resources, nested
finalizers are run in the usual reverse order" (`Resource.scala:245-283`); the incumbent's is
`Scope.run(closeParallelism)` (`Scope.scala:129-138`) over the reversed list.

## 9. Region variant

**An interpreted ADT value (cats-effect 3).** `Resource` is `Allocate(Poll[F] => F[(A, ExitCase =>
F[Unit])]) | Bind | Pure | Eval`, its interpreter threading a running release accumulator through each
`Allocate` (`Resource.scala:474-495`); being a value it can be `allocated` out of, "an advanced and
potentially unsafe api which can cause a resource leak". **A mutable region object in the environment
(ZIO 2, Effect-TS, resourcet, Eio).** ZIO's `Scope` is a `ReleaseMap` plus `R with Scope` plumbing, and
`ZIO.acquireRelease` is built from the same `uninterruptible` and `addFinalizerExit` any user could
write, so the region is data, not an opcode; Eio's `Switch.run` "waits until `fn` and all other attached
fibers have finished, and then releases any attached resources". **A context value (kyo, this tree):**
`sealed trait Scope extends ContextEffect[Scope.Finalizer]` (`Scope.scala:37`), bracketed by the kernel
primitive as `Sync.ensure(finalizer.close)` closing with `result.error` (`:129-138`), its finalizer
seeing `Maybe[Error[Any]]` (`:145-146`).

**Ownership across a concurrency boundary converges:** the forked child owns its own region and the
enclosing region owns only the right to interrupt it. ZIO's `forkIn` forks with `.onExit(child.close(_))`
and registers `interrupt(fiber)` on the outer scope (`ZIO.scala:796-822`); Effect-TS's `forkScoped`
registers an interrupting finalizer in `scopeImpl.state.finalizers` and removes it via an observer. CE3
has no such wiring on `start` and offers `Supervisor`; `resourcet` reference-counts across
`resourceForkIO`; kotlinx makes the case unrepresentable. The incumbent detects rather than manages it: a
`Scope` finalizer registered after close raises "This finalizer is already closed. This may happen if a
background fiber escapes the scope of a 'Scope.run' call" (`Scope.scala:162-172`).

## 10. Mechanics

**Composition over two primitives, everywhere but the incumbent.** CE3's `IO` ADT has no `Bracket` node:
`bracket`, `guarantee`, and `guaranteeCase` are methods over `Uncancelable` and `OnCancel`; ZIO's and
Effect-TS's are methods over their runtime-flag-update primitive plus a finalizer insertion; GHC's is a
function over the `maskAsyncExceptions#` primops. **Where the state lives:** CE3 puts both halves on the
fiber as plain fields, `finalizers: ArrayStack[IO[Unit]]` and `var masks: Int`
(`IOFiber.scala:88,103-104`), safe because the runloop is single-owner; ZIO and Effect-TS split them, the
mask a fiber `RuntimeFlags` bit and the finalizers a `Scope` decoupled from any fiber, free to outlive or
be extended across one; GHC's mask is per-thread in the RTS and Kotlin's state is the `Job`.

**A kernel primitive whose state is one object with three roles (kyo, this tree).** The release is a field
on the `Binding` node (`ContextEffect.scala:166,178`, `Effect.scala:170`), and the live obligation is a
`Finalizer`, at once the entry the stack holds so an eval that throws or abandons can still release, the
arrow spliced after the extent so a completing eval releases where that extent ends rather than at the
boundary, and the flag making the two paths exclusive (`Finalizer.scala:15-27`). Outstanding releases are
held apart from the stack entries "so a fold cannot bury one" (`Stack.scala:23-24`), and a park carries
them rather than running them, leaving the choice to whoever holds it (`Eval.scala:784-793`).

## 11. Context propagation at fork

**A per-value fork transform declared by the value's author (ZIO 2, Effect-TS, Java, kotlinx, kyo).** The
majority answer, unanimous on the second half: the policy is fixed where the value is constructed, never
at the fork site. ZIO: `def make[A](initial: => A, fork: A => A = ZIO.identityFn[A], join: (A, A) => A =
ZIO.secondFn[A])` (`core/shared/src/main/scala/zio/FiberRef.scala:369-375`). Effect-TS: `make<A>(initial,
options?: { fork?: (a: A) => A; join?: (left: A, right: A) => A })` (`packages/effect/src/FiberRef.ts`).
Java: `protected T childValue(T parentValue)`, "called from within the parent thread before the child is
started ... should be overridden if a different behavior is desired". Kotlin: `copyForChild()` and
`mergeForChild(overwritingElement)` on `CopyableThreadContextElement`. The incumbent: `def fork(value: A):
Maybe[A]` (`kyo-prelude/shared/src/main/scala/kyo/Local.scala:44`), `init` defaulting to `Maybe(value)`
(`:121`) and `initNoninheritable` defined as `init(defaultValue)(_ => Maybe.Absent)` (`:160-161`), so the
non-inheritable tier is no longer a type but a fork function returning nothing. The kernel records why:
"No non-inheritable marker: a binding that must not cross a fork says so with `fork = _ => Absent`, which
also lets one cross as something else, where a type could only say yes or no"
(`kyo-kernel/shared/src/main/scala/kyo/kernel/ContextEffect.scala:35-36`).

**Whole-context copy with no per-value policy (cats-effect 3).** "A **forked** fiber ... operates on a
**copy** of the parent `IOLocal`. Hence, the children operations are not reflected on the parent context"
(`core/shared/src/main/scala/cats/effect/IOLocal.scala:79-82`), with no opt-out on `IOLocal.apply`.
**Structural inheritance, no copy (kotlinx, Java `ScopedValue`, Eio):** Kotlin's context is an immutable
indexed set combined by key at launch, ordinary elements shared by reference and only copyable ones
duplicated; `ScopedValue` bindings are captured when a `StructuredTaskScope` is created and inherited by
every thread forked in it; Eio's `Fiber.with_binding` binding "will be propagated to any forked fibers".
**Nothing (GHC):** `forkIO` inherits only the masking state.

## 12. Merge at join, and the handoff question

**No merge at all (cats-effect 3, kotlinx, Java, Eio, GHC).** CE3 demonstrates rather than asserts it: two
children update copies to 41 and 43, the parent joins both, and the documented output ends `// fiber A:
42` (`IOLocal.scala:98-127`); `Fiber#join: F[Outcome[F, E, A]]` carries nothing else. Kotlin states it
outright, "Writes will not be visible to the parent coroutine, peer coroutines, or coroutines that happen
to use the same thread"; `ScopedValue` has no setter, only `where(...).run(...)`.

**A per-value two-argument merge at an explicit join point (ZIO 2, Effect-TS).** ZIO's `Fiber#join` is
`await.unexit <* inheritAll` (`core/shared/src/main/scala/zio/Fiber.scala:212-213`): `await` alone does
not merge, fiber completion does not merge, only the explicit join does; Effect-TS matches with
`core.zipLeft(core.flatten(self.await), self.inheritAll)`. The user function is two arguments in both,
`join: (A, A) => A` defaulting to the second, so the child wins unless the author says otherwise, and the
merged value is pushed as a new stack entry under the parent's fiber id, making it the parent's value for
the rest of its life.

**The handoff is reconstructed, never passed (ZIO 2, Effect-TS).** The finding most directly relevant
here: neither system's `join` receives what the child started from, but both compute it internally. Each
`FiberRef` value is stored per fiber as a stack of `StackEntry(fiberId, value, version)` frames, one per
ancestor that touched it. `FiberRefs.joinAs` walks both stacks with `findAncestor`, stepping the deeper
side down until fiber ids match, to recover the common-ancestor value; it then computes `patch =
ref.diff(ancestor, childValue)`, replays that delta onto the parent's *current* value with
`ref.patch(patch)(oldValue)`, and only then calls `ref.join(oldValue, rebasedChild)`
(`core/shared/src/main/scala/zio/FiberRefs.scala:181-267`; Effect-TS's
`packages/effect/src/internal/fiberRefs.ts` is the same algorithm). The purpose is a real three-way merge:
a parent's own updates between fork and join are not clobbered by a child's stale base. So the precedent
is not that the handoff is unnecessary, but that both systems paid a per-value stack and an ancestry walk
to reconstruct it.

**A two-argument merge keeping the parent's value by default (kyo, this tree).** `def join(held: A,
forked: A): A` (`Local.scala:47`), `init` defaulting to `(held, _) => held` (`:122`) and the overload
defaulting `joinValue` the same (`:143`), applied per local at the boundary by `joinMap`, which folds over
what the parent holds and keeps its value where the fork carried none (`:179-185`). The default is the
opposite of ZIO's and Effect-TS's: parent wins, not child. The stateful isolates are the same idea one
layer up, chosen at the isolate rather than per value: `Var.isolate.update` takes the fork's final value,
`Var.isolate.merge(f)` combines with `(V, V) => V`, `Var.isolate.discard` keeps the parent's
(`kyo-prelude/shared/src/main/scala/kyo/Var.scala:220,237,254`), and `Emit.isolate.merge` appends the
isolated emissions to the outer context (`.../Emit.scala:223`).

## 13. Fork and join under races and interruption

**Only the winner merges; the loser's state is dropped but its finalizers run and are awaited (ZIO 2,
Effect-TS).** ZIO's `raceWith` calls `winner.inheritAll` only when the first side to complete is an
`Exit.Success`, and `race` then stops the loser with `interruptAs`, never `join`, discarding its
`FiberRefs` (`ZIO.scala:1491-1508,1322-1334`); Effect-TS calls `internalFiber.inheritAll(winner)` and
gates `resume` on `latch && fibers.size === 0`, reached only after every loser's completion observer has
fired, that is, after its own teardown. Both await the loser; ZIO's scaladoc warns the race "will not
resume until the loser has been cleanly terminated". The asymmetric case: if ZIO's fast side *fails*,
`race` calls `join` on the other fiber, which does merge its state. Interruption alone never merges,
`interruptAs` never calling `inheritAll`, while `Fiber#join` calls it unconditionally whatever the exit.

**The loser is cancelled and awaited, with no state to merge (cats-effect 3, GHC `async`).** CE3's `race`
sequences `f.cancel *> ...` before producing a result, and `cancel` "semantically blocks the caller until
finalization of the cancellee has completed"; Haskell's `race` cancels the loser and waits for the thread
to quit. **The loser is not cancelled at all (kotlinx `select`):** the guide's own example prints "11
coroutines are still active" after a `select` over twelve deferreds resolves. **Failure cancels the group
(Eio):** `Switch.fail` cancels the switch's fibers, failures folding into `Exn.Multiple`
(`lib_eio/core/exn.ml`).

## Comparison matrix

| System | Acquire | Settle gap | Payload | Once | Finalizer ctx | Both fail | Order | Fork | Join |
|---|---|---|---|---|---|---|---|---|---|
| cats-effect 3 | masked, `Poll` unmasks | mask spans both, `masks==0` gate | `Outcome`, `ExitCase` | pop from stack | effects, masked | body wins, release to EC reporter | LIFO, `both` parallel | copy, no per-value policy | none |
| ZIO 2 | masked, `restore`; interruptible tier | mask spans both | `Exit`/`Cause` | CAS map plus `Exited` | effects, masked | `Cause.Then` of both | LIFO, `ExecutionStrategy` | `A => A`, author-declared | `(A,A) => A` at `Fiber#join`, ancestry rebase |
| Effect-TS | masked, `restore` | mask spans both | `Exit`/`Cause` | scope state machine | effects, masked | `Cause.sequential` | LIFO, `ExecutionStrategy` | `fork` option, author-declared | `(left,right)` at `Fiber.join`, ancestry rebase |
| kyo, this tree | vacuous, nothing interrupts | park refused before the binding | `Result` plus `Abandoned` | `AtomicBoolean` CAS | no effects at primitive | body wins, release suppressed | LIFO, `closeParallelism` | `A => Maybe[A]`, author-declared | `(held, forked)`, parent wins by default |
| GHC base | masked, interruptibly | `mask` spans both | none, or `ExitCase` | lexical | effects, interruptible | release replaces body | LIFO, lexical | nothing crosses | none |
| kotlinx | no primitive | not guaranteed | `Throwable?` | lexical | needs `NonCancellable` | JVM `finally` rules | LIFO, lexical | structural, `copyForChild` | none |
| Java | not applicable | not applicable | none | lexical | plain code | body wins, `addSuppressed` | LIFO, lexical | `childValue`, author-declared | none |
| Eio | not applicable | not applicable | none, zero-arg hooks | list removal | effects | folded into `Exn.Multiple` | LIFO | propagated to forks | none |

## Implications for the proto kernel

Bearings on the open decisions, not answers to them.

**(a) Acquire interruptibility.** Every system that masks acquire does so because something can interrupt
mid-acquire, which a park-only model does not have, and the incumbent already exploits that by refusing
the park rather than masking. The question is not whether to mask but whether an acquire spanning a park
should be abandonable; ZIO alone made that first-class, paying by denying the release the resource.

**(b) Exit hook payload.** Three answers in the field: nothing, a three-way ADT, a nullable cause. The
incumbent is in the ADT camp and argues abandonment is a case a `Maybe[Error]` cannot express, while
`Sync.ensure` narrows to `Maybe[Error[Any]]` publicly anyway. The proto carries `release: (A, Throwable)
=> Unit` (`.../proto/kernel/ContextEffect.scala:107,118`); no surveyed system passes a full outcome to a
*pure* hook.

**(c) Finalizer-failure policy.** Suppression onto the body's error is both the JVM convention and what
the incumbent already does with an identity guard. The alternative with real precedent is aggregation
into a composite cause, needing an error type that holds two. The behaviors with no constituency are
CE3's side-channel report, which loses the error to a logger, and GHC base's silent replacement.

**(d) A first-class masked-acquire tier.** ZIO alone has a separate constructor; everywhere else the tier
is a token (`Poll`, `restore`) rather than a method, so the masked case stays default and the unmasked
case is visible at the call site. If nothing can interrupt acquire this may not arise; if it can, ZIO's
precedent puts the tier's cost in the release's type, not the acquire's.

**(e) A `Scope`-style region over the primitive.** Three precedents: an interpreted ADT value, a mutable
region object in the environment, and the incumbent's context value, the one that composes with the
kernel's own binding mechanism rather than sitting beside it. The convergent boundary rule holds
regardless: the forked child owns its region, the enclosing region owns only the right to interrupt it.

**(f) Join arity.** No surveyed system passes the handoff as an argument, but the two that merge at all
reconstruct it through an ancestry walk and use it for a three-way rebase before calling the two-argument
function. A `(parent, forked, child)` signature is that same information delivered by the caller instead,
which the proto already carries (`.../proto/kernel/ContextEffect.scala:96,124`); the open question is
whether the fork's value is genuinely the ancestor the rebase wants.

**(g) Merge scoping.** ZIO and Effect-TS push the merged value as a new stack entry under the parent's
fiber id, making it the parent's for the rest of its life, which is write-through. The incumbent's
`joinMap` produces the value the binding holds for the remainder of its extent, which is scoped
shadowing. Both are in the field, and nothing here makes one a consequence of the other properties.

**(h) Where the inheritable policy lives.** Unanimous across ZIO, Effect-TS, Java, Kotlin, and the
incumbent: on the value, declared by its author at construction, never at the fork site. CE3 is the only
system with no per-value policy, and it has no opt-out either. The survey supports the incumbent's
retirement of the type-level marker, since `childValue` and `copyForChild` are transforms, not booleans.

**(i) Pure versus effectful hooks.** Every fork and join function surveyed is pure. Finalizers are the
opposite, effectful everywhere except the incumbent's primitive, which types the release `Any < Any`
because it must run where nothing is installed to answer for it, re-admitting effects only in `Sync`
above. The proto's hooks are pure on both counts, in line with every surveyed system on fork and join,
and with the incumbent alone on release.

Sources:
- [cats-effect, series/3.x](https://github.com/typelevel/cats-effect): `kernel/shared/src/main/scala/cats/effect/kernel/{MonadCancel,Poll,Resource,Outcome,Fiber,GenSpawn}.scala`, `core/shared/src/main/scala/cats/effect/{IO,IOFiber,IOLocal}.scala`
- [ZIO, series/2.x](https://github.com/zio/zio): `core/shared/src/main/scala/zio/{ZIO,Cause,Scope,FiberRef,FiberRefs,Fiber}.scala`, `internal/FiberRuntime.scala`; [scope](https://zio.dev/reference/resource/scope/), [fiberref](https://zio.dev/reference/state-management/fiberref/)
- [Effect-TS, tag effect@3.22.1](https://github.com/Effect-TS/effect): `packages/effect/src/{Effect,Scope,Exit,Cause,FiberRef}.ts`, `packages/effect/src/internal/{core,fiberRuntime,fiberRefs,fiber}.ts`; [scope docs](https://effect.website/docs/resource-management/scope/)
- [Control.Exception](https://hackage.haskell.org/package/base/docs/Control-Exception.html), [GHC.IO](https://hackage.haskell.org/package/base/docs/GHC-IO.html), [exceptions](https://hackage.haskell.org/package/exceptions/docs/Control-Monad-Catch.html), [safe-exceptions](https://hackage.haskell.org/package/safe-exceptions), [resourcet](https://hackage.haskell.org/package/resourcet/docs/Control-Monad-Trans-Resource.html), [async](https://hackage.haskell.org/package/async/docs/Control-Concurrent-Async.html)
- [Kotlin cancellation](https://kotlinlang.org/docs/coroutines-cancellation.html), [exception handling](https://kotlinlang.org/docs/exception-handling.html), [ThreadContextElement.kt](https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/jvm/src/ThreadContextElement.kt), issue [#1936](https://github.com/Kotlin/kotlinx.coroutines/issues/1936)
- [InheritableThreadLocal](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/InheritableThreadLocal.html), [Throwable](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Throwable.html), [ScopedValue](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ScopedValue.html)
- [ocaml-multicore/eio](https://github.com/ocaml-multicore/eio): `README.md`, `lib_eio/core/{eio__core.mli,switch.ml,exn.ml}`
- kyo, this working tree: `kyo-kernel/shared/src/main/scala/kyo/kernel/Effect.scala:117-183`, `.../kernel/ContextEffect.scala:35-36,159-181`, `.../kernel/internal/{Finalizer.scala,Stack.scala:567-595,Eval.scala:649-661,725-825}`, `kyo-core/shared/src/main/scala/kyo/{Sync.scala:60-123,Scope.scala}`, `kyo-prelude/shared/src/main/scala/kyo/{Local.scala,Var.scala:197-259,Emit.scala:211-271}`, proto at `.../proto/kernel/ContextEffect.scala:95-132`
