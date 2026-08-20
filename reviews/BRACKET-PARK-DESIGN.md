# Bracket, Park, and finalizers on the Stack

Design document, revision 3. No source was changed, nothing was built, nothing was committed.

Every `file:line` below was reopened against the worktree at `0abe804780`. The tree moved since revision 2:
`EffectTrace` landed, `Loop` dispatches on `Kyo`, `Effect` regained a by-name `defer` (`Effect.scala:14-15`), and
`Kyo.Defer`/`Suspend`/`Handle` plus the `Arrow` classes carry `toString`. No citation from revision 2 or from the
review is reused unchecked.

The binding shape:

```scala
abstract class Bracket[A, B, C, S] extends Kyo[C, S]:
    def acquire: Kyo[A, S]
    def use:     Arrow[A, B, S]
    def release: Arrow[A, Unit, Any]
    def cont:    Arrow[B, C, S]
```

---

## 0. What changed since the review

The review (`reviews/BRACKET-PARK-REVIEW.md`) returned REWORK. It confirmed the central reduction and rejected the
exactly-once layer. The reduction is kept and not re-argued; sections 2 and 3 state it in a fraction of the space
revision 2 used.

Four of the seven blocking findings are instances of one root cause, fixed by one decision in section 1.

| finding | answered in | how |
|---|---|---|
| **B1** the walk mints a fresh `Finalizer` from an `Acquire`, so a resume and an abandonment both release | 1.2 rule 1, 1.3 | `Acquire.apply` is the only site in the kernel that constructs a `Finalizer`. The walk has no `Acquire` arm |
| **B2** the walk cannot tell a settled acquire from a pending one and hands `release` a `Kyo` node | 1.3, question 2 in 9 | a park holding an unconsumed `Acquire` owes nothing. All three sub-defects go with the arm |
| **B3** a finalizer folded into a captured continuation is reachable from neither `unwind` nor `collect` | 1.2 rule 3, 5.4 | the drive stops hiding folds in closures: `Captured` at two sites, `Effect.defer` in place of `map` at two, a release-on-throw at the fifth |
| **B4** `collect` never descends into `Kyo.Defer.value` | 5.3 | one shared walker, value position first, mirroring `EffectTrace.Builder.drain` (`EffectTrace.scala:277-280`). It is also a worklist rather than recursion, which revision 2 got wrong independently |
| **B5** `sealed trait Barrier` cannot be implemented from two files | 5.2 | `private[kernel] trait Barrier` in `Stack.scala`, not `sealed`; the `Arrow.Transform` precedent is the same shape |
| **B6** `finalizeResources` cannot join the `inline self` extension block | 5.6 | its own `extension [A, S](self: A < S)` block; `Pending.scala:18` is the inline one |
| **B7** `Eval.drive` returning `Any < Nothing` is a representation-contract question | 6 | settled here, with the alternative, the mechanism and the pin. Marked for sign-off as question 4 |

Two further defects this revision found on its own, neither in the review:

- **`use` throwing before it suspends leaks the resource.** Revision 2 evaluated `use(a)` as an argument to
  `Effect.defer`, before `new Finalizer` ran. Section 3.2.
- **The abandonment walk overflows the Java stack on a deep value.** Revision 2 recursed on `Defer.value`; a park
  taken over 100k trailing maps is a 100k-deep left-nested `Defer`. Section 5.3.

The review's non-blocking findings are all answered in place: N1 in 3.2, N2 and N3 in 7, N4 and N13 by
re-derivation, N5 in 4.2, N6 and N7 in 11, N8 by 5.5 removing `compact`, N9 and N10 in question 5, N11 in 5.7,
N12 in question 6, N14 in 7.

---

## 1. Where a finalizer's identity lives

### 1.1 The decision

**A finalizer's identity is the `Finalizer` object and the CAS it carries. Position is not identity.**

Revision 2 assumed the stack entry was the identity, which produced B1 through B4. It is true only on the resume
path, the one path that was never at risk. Once identity is the object, two things become available that the entry
reading forbade, and they are what close all four findings:

- **Minting is privileged.** Only one site may construct a `Finalizer`, because a second construction is a second
  identity for one resource.
- **Replication is free.** The same object may be reachable from any number of positions at once, because the CAS
  decides which reader runs the release, not the position it was read from.

### 1.2 The four rules that follow

1. **`Acquire.apply` is the only site in the kernel that constructs a `Finalizer`.** Grepable as `new Finalizer`.
   No walk, no recovery path and no `Stack` method may synthesise one.
2. **A value holding an unconsumed `Acquire` owes nothing.** Nothing has been acquired, so there is nothing to
   release and nothing to mint from. This is the review's question 2 and its answer is taken.
3. **Every unrun `Finalizer` is reachable by public structure:**

   > **(F)** At every moment, a `Finalizer` whose `run()` has not been entered is reachable from the drive or from
   > the value it belongs to through one of: a `Stack` entry; a link of an `Arrow.Chain`; `Defer.value`,
   > `Defer.contA`, `Defer.contB`; `Handle.value`, `Handle.cont`; `Suspend.cont`; `Nested.value`; or
   > `Captured.captured`. Never only through a closure capture.

   Every drive site that folds a stack range into an arrow is checked against (F) in 5.4. One site cannot satisfy
   it and instead runs the fold's releases from its own `catch`; that is stated there rather than hidden.
4. **Every reachable position is walked**, by one walker (5.3) used by the throw path, the region-discard path and
   the abandonment path. There is no second traversal that can fall behind the first.

Rule 3 is the substantive one and it constrains the *drive*, not `Finalize`. The drive is what folds, and a fold
that disappears into a closure is what B3 is.

### 1.3 B1, B2 and B4 falling out

**B1.** The minting arm was `case q: Acquire[?, ?, ?] => acc.append(new Finalizer(q.release, pending))`. Rule 1
deletes it and rule 2 says nothing replaces it. A park whose stack still carries an `Acquire` is reachable (the
drive pushes `Acquire` in the `Defer` arm, `Eval.scala:37-40`, and only consumes it when the settled arm pops it,
`Eval.scala:187-195`, and the poll fires between those turns) and now yields the empty set, which is correct
because `acquire` has not settled. A resume of that same park runs `acquire` to a value and mints the one
`Finalizer` there. One mint, one CAS, one release.

**B2.** All three sub-defects were properties of the deleted arm: handing `release` a pending `Kyo` node, passing a
union value into `release` without the single permitted unnest, and giving an `Acquire` below the head of `contA`
the resource `()`. There is no arm, and the walker carries no `pending` parameter at all (5.3), so the shape that
produced the third does not exist. `Acquire.apply` reads its own resource through `unsafeGet`
(`Pending.scala:290-293`), which is the one delivery site for it.

**B4.** Rule 4. The walker visits `Defer.value` first, then `contA`, then `contB`, which is both the order the
pieces run in and the order `EffectTrace.Builder.drain` already uses for the same node (`EffectTrace.scala:277-280`
pushes `contB`, `contA`, `value` onto a prepending worklist, so `value` drains first). That is where
`Acquire.apply`'s fresh `Finalizer` lives one loop turn after the acquire settles, and it is where the ordering
guarantee comes from.

**B3** is not a walk defect and does not fall out of the walk. It is answered in 5.4.

---

## 2. The equations

### 2.1 Bracket

```scala
def bracket[A, B, S](acquire: A < S)(release: A => Unit < Any)(use: A => B < S)(using Frame): B < S

bracket(acquire)(release)(use)  ==  acquire.map(a => use(a).map(b => release(a).andThen(b)))
```

Complete for the path where `use` runs to a value. The primitive exists only for the paths where the outer `map` is
never applied: a throw inside `use`, a `Loop.done` discarding the region the bracket is in, and a park nobody
resumes. `map` cannot observe that its continuation will not be applied.

Two corollaries used later:

- `release` runs after `use` and before anything composed onto the bracket, by stack order, with no extra
  mechanism.
- `bracket(...)(...)(...).map(f)` puts `f` outside the release, because `map` on a pending value wraps rather than
  fuses (`Pending.scala:28-29` builds `Effect.defer(kyo, arrow, next)`). This is why the region-exit crossing
  `3a95636fa8:KyoInternal.scala:133-164` had to invent is not needed: there `Bracket.map` extended the *use* chain
  (`3a95636fa8:KyoInternal.scala:118-127`), so the boundary was invisible in the structure. Here it is structural.

### 2.2 Park

```scala
private[kyo] def partial[A, S](v: A < S): A < S
private[kyo] def partial[A, S](v: A < S, stop: () => Boolean): A < S

// for every A, S, and every context k that can consume an A < S
k(Eval.partial(v))  ==  k(v)
```

An observational identity, not a new combinator. An operation with no handler in `v` is the boundary of what `v`
can compute on its own, so stopping there is the definition of partial evaluation rather than a special case. That
equation is what `c2d5db5072` records as missing from the withdrawn attempt, alongside its `Parked` carrier
(`1cf05637e8:41`). This design introduces no carrier: a park is `Effect.defer(residual, stack.dump(stack.size))`,
an ordinary `Kyo.Defer` over an ordinary folded `Arrow`.

### 2.3 finalizeResources

```scala
extension [A, S](self: A < S)
    private[kyo] def finalizeResources: Unit < Any

p.finalizeResources  ==  the composition, innermost bracket first, of every release whose acquire completed
                         inside p and whose use has not yet delivered

p.finalizeResources.andThen(p.finalizeResources)  ==  p.finalizeResources
```

The row is `Any` because the release's row is `Any`, so the equation holds with no side condition about which
handlers happen to be installed.

---

## 3. Bracket is `Defer` plus `Acquire`

### 3.1 The reduction

`Kyo.Defer` (`KyoInternal.scala:16-19`) is `value` then `contA` then `contB`, which is `acquire` then
register-and-use then `cont`. The drive's `Defer` arm (`Eval.scala:37-40`) is `push(contB); push(contA);
loop(value)`. A `Bracket` arm would be that verbatim except that `contA` also installs the finalizer, and `contA`
is an `Arrow`, so it can:

```scala
def bracket[A, B, S](acquire: A < S)(release: A => Unit < Any)(use: A => B < S)(using Frame): B < S =
    Effect.defer(acquire, new Acquire(Arrow(release), Arrow(use)), Arrow.id[B])
```

`Effect.defer(v, a, b)` drops an identity `b` (`Effect.scala:33-34`), so this is one node, and the whole normal
path runs on arms that exist today: the `Defer` arm pushes `Acquire` and drives `acquire`; the settled arm's
fall-through (`Eval.scala:187-195`) pops `Acquire` and applies it with `stack.dump()` as `next`, producing
`Effect.defer(use(a), Finalizer.chain(next))`; the `Defer` arm pushes that chain, which `Stack.push` flattens into
`[Finalizer, ...next]` (`Stack.scala:37-41`, `:53-64`); when `use` settles the finalizer is popped and applied, the
release runs, and the value flows on. `Acquire` and `Finalizer` are `Arrow.Transform`s and neither is a `Handler`
nor a `Chain`, so both reach that fall-through.

**Recommendation (needs sign-off): no `Bracket` node.** A fourth `Kyo` subclass adds one `instanceof` to the
settled arm, the hottest arm in the drive, and buys nothing the `Acquire` arrow does not. Nothing downstream
depends on the choice. If the owner keeps the node, its variance must be `[A, B, +C, -S]` to match `Defer` and
`Handle` (`KyoInternal.scala:16`, `:34`), and `Effect.bracket` must still collapse a settled acquire behind an
identity `Defer`, since `acquire: Kyo[A, S]` cannot represent one and `Bracket` has no law like `Handle`'s
`handle(settled) == done(settled)`, which the code takes at `ArrowEffect.scala:67`.

### 3.2 `Acquire`

```scala
final private[kyo] class Acquire[A, B, S](
    val release: Arrow[A, Unit, Any],
    val use: Arrow[A, B, S]
) extends Arrow.Transform[A, B, S]:

    def frame = use.frame

    def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]): C < (S & S2) =
        v match
            case kyo: Kyo[A, S2] @unchecked =>
                // acquire is still pending: stay a node, nothing is owned yet
                Effect.defer(kyo, this, next)
            case _ =>
                val a = v.unsafeGet
                // minted before `use` runs, so a throw out of `use` has a token to spend.
                // This is the only `new Finalizer` in the kernel
                val f = new Finalizer(release, a)
                val body =
                    try use(a)
                    catch
                        case ex: Throwable =>
                            try discard(f.run())
                            catch case t: Throwable => ex.addSuppressed(t)
                            throw ex
                // the finalizer goes above `next`, so the pop order is use, release, then
                // whatever the caller composed
                Effect.defer(body, f.chain(next))
end Acquire
```

Two things are deliberate and neither was in revision 2.

**The `try` around `use(a)`.** `Arrow(f)` overrides `apply(v: A) = f(v)` (`Arrow.scala:37`), so `use(a)` runs the
user's body synchronously here. Revision 2 wrote `Effect.defer(use(a), new Finalizer(release, a).chain(next))`, and
Scala evaluates the first argument first, so a `use` that threw before suspending threw before any `Finalizer`
existed and the resource leaked. The prior art covered this with a per-bracket `try`
(`3a95636fa8:Eval.scala:43-47`); the flat `@tailrec loop` here has no per-bracket frame to hang one on, so the
`try` belongs in the arrow. Pinned by test 12.

**The missing safepoint pair (N1).** `use(a)` is not gated on `Safepoint.enter`/`exit`, unlike every other strict
arm (`Pending.scala:31-37`, `Arrow.scala:43-49`, `Handler.scala:16-22`). That is correct here, structurally rather
than by omission: those arms gate because they *continue* after calling user code (`next.head(f(...), next.tail)`
recurses), and the budget bounds the Java stack that recursion builds. `Acquire.apply` returns a node in every arm,
so it fuses nothing and adds no depth to bound. Skipping both halves is also balanced, so the budget cannot drift.
Pinned by test 13.

---

## 4. Park and `Eval.partial`

### 4.1 A park is a `Defer`, and no `Park` node

`Stack.dump(pos)` folds entries into one `Arrow`, chaining each onto the one below and consuming the slots
(`Stack.scala:102-126`). Pair that arrow with the standing value and the result is a `Kyo.Defer`:

```scala
// Eval.scala, private, cold, never inlined into loop
private def reify(stack: Stack, curr: Any < Nothing): Any < Nothing =
    if stack.isEmpty then curr
    else Effect.defer(curr.asInstanceOf[Any < Any], stack.dump[Any, Any, Any](stack.size))
        .asInstanceOf[Any < Nothing]
```

Resumption is the existing `Defer` arm: it pushes the folded chain and `Stack.push` flattens it back into entries
(`Stack.scala:37-41`). Two details make that exact and both are already in `Stack`. A `Handler` is an ordinary
entry (`Handler.scala:9`), so a region folds, re-pushes and is found again by `Stack.find` (`Stack.scala:89-98`).
A stateful region comes back at its live state, because `dump` swaps in `Handler.HandlerLoopState(h, state)` before
folding (`Stack.scala:109-110`) and `put` re-seeds through `states(idx).getOrElse(f.initialState)`
(`Stack.scala:23-31`), which is exactly what the commented case at `ArrowEffectTest.scala:590-600` requires.

`d4e59ffa56` already ruled on the node: subject "proto: a captured continuation is a composition, no Park in any
value", body "Kyo.Park, Stack.copy*/pushAll/regionAbove, Eval.fold/park/restore go." A `Park` node would buy a bulk
push instead of a fold-and-flatten, a cold-path performance question with no measurement.
**Recommendation (needs sign-off): no `Park` node.**

### 4.2 What triggers a park

**Budget exhaustion never parks the drive.** `Safepoint.enter` is called from the strict arms of the composition
sites, not the drive; when the budget is spent the strict path stops fusing and produces a `Kyo.Defer`, which the
drive consumes and keeps going. The direct evidence is that `Eval.apply` contains no poll of any kind
(`Eval.scala:32-215`).

**The stop protocol parks, and only under `partial`.** `Safepoint.stop(thread)` CASes the slot from `Thread` to
`Stop(thread)` (`Safepoint.scala:137-159`). The next `Safepoint.get()` misses the fast path (`slots.get(h) eq
thread` is false, `:74`) and falls into `resolve`, which reads the arm bit and drains the budget when a `Stop` is
present (`:98-103`). `arm` is the coupling and it is one-directional (`:134-135`): arming makes a stop request
drain the budget, which makes the strict arms stop fusing, which makes the computation reach the drive as nodes
promptly; the drive then observes the request through `consumeStopped` (`:161-167`) at its poll and parks.

**Correcting revision 2's evidence (N5).** Revision 2 cited `SafepointConcurrencyTest.scala:206-252` as pinning the
`arm` coupling. It does not. That test is "the overflowed slot ignores budget operations and misses preemption"
(`:210`), and what it degrades is slot *availability*: `Slots` holder threads occupy the table so the probe
resolves to `Overflowed`. No stop is requested against the probe before `evalResult = Eval(burn(Period * 4))`
(`:240`); `stoppedResult` is read before the eval and asserted false (`:238`, `:247`), and
`assert(!Safepoint.stop(probe))` runs after it (`:249`). The coupling is real and readable at
`Safepoint.scala:98-103` and `:134-135`; test 16 pins it, not that test.

| trigger | mechanism | effect on the drive | who sees it |
|---|---|---|---|
| budget exhausted | `Safepoint.enter` returns false in a strict arm | none; one more `Defer` to consume | every drive, always |
| stop requested, slot armed | `Stop` in the slot, budget drained at `Safepoint.get()`, `consumeStopped` at the poll | park: reify and return | `Eval.partial` only |
| operation with no handler on the stack | `Stack.find` returns -1 | park under `partial`, `bug` under `apply` | `Eval.partial` only |

### 4.3 `partial`

```scala
private[kyo] def partial[A, S](v: A < S, stop: () => Boolean): A < S =
    val slot = Safepoint.get()
    // a stop delivered between slices short-circuits: the request is consumed here and the input
    // comes back by identity, so the caller can reschedule without any drive at all
    if Safepoint.consumeStopped(slot) then v
    else
        val saved = Safepoint.save(slot)
        Safepoint.arm(slot)
        val stack = Stack.borrow()
        try drive(stack, v.asInstanceOf[Any < Nothing], () => Safepoint.consumeStopped(slot) || stop())
            .asInstanceOf[A < S]
        catch
            case ex: Throwable =>
                Finalize.unwind(stack, ex)
                EffectTrace.splice(ex)
                throw ex
        finally
            Stack.release(stack)
            Safepoint.restore(slot, saved)
        end try
end partial
```

`1cf05637e8:24-36` with the stack borrow made explicit and the boundary catch added. `save` then `arm` in that
order preserves the caller's armed state in `saved` and restores it in the `finally`, matching `1cf05637e8:31-32`.
The identity short-circuit is what `EvalTest.scala:1021` pins.

The poll goes at the top of the loop, `if (stop ne null) && stop() then reify(stack, curr) else curr match ...`.
`stop` is `null` for `Eval.apply`, so the feature is one reference comparison on the hot path, and `reify` is a
separate method so nothing cold enters `loop`'s bytecode budget. Both are shape claims and both need numbers (13).

**The unhandled-operation park needs one care.** The `Suspend` arm pushes `kyo.cont` *before* it searches
(`Eval.scala:42-43`), so when `find` returns -1 the continuation is already on the stack and reifying `kyo` would
stack it twice. Reify the bare operation instead:

```scala
if pos < 0 then
    if stop eq null then
        try bug(s"unhandled suspension: ${kyo.tag}")
        catch case ex: Throwable => EffectTrace.attach(ex, kyo, Arrow.id[Any], stack); throw ex
    else
        // kyo.cont is already on the stack; the parked value carries the operation with an
        // identity continuation so the resume does not stack it twice
        reify(stack, new Kyo.Suspend[IX, OX, EX, CX, OX[CX], Any]:
            def frame = kyo.frame
            def tag   = kyo.tag
            def input = kyo.input
            def cont  = Arrow.id[OX[CX]]
        )
```

`IX, OX, EX, CX` are bound by the arm's pattern (`Eval.scala:41`), so this is a construction, not a cast. The guard
only bites for `ArrowEffect.suspendWith`, whose `cont = this` (`ArrowEffect.scala:39`); with `ArrowEffect.suspend`
the `cont` is `Arrow.id[O[C]]` (`ArrowEffect.scala:24`) and `Stack.push` drops identity entries
(`Stack.scala:35`), so the two reifications are indistinguishable. Test 17 is a dedicated `suspendWith` park for
that reason (N7).

### 4.4 Park, resume, and the Stack pool

- **A park value holds no `Stack`.** `reify` consumed every slot via `dump(size)`, so the drive's `finally`
  (`Eval.scala:212`) hands `Stack.release` an empty ring; `release` calls `clear()` (`Stack.scala:186`,
  `:145-149`), which resets `head` and `tail` to 0. A resume borrows a fresh stack, possibly from another thread's
  pool (`Stack.local`, `Stack.scala:193-195`), and it starts clean regardless of provenance.
- **A resumed value is not consumed.** It is a complete value, driveable any number of times on any threads. That
  is what the "captured continuation is a value" group at `d4e59ffa56` established for the fold; it transfers, and
  it needs its own pins (tests 19, 20).
- **Cross-thread visibility of the resource itself is the publisher's business.** The `Finalizer`'s `AtomicBoolean`
  gives a happens-before for the release *decision*, not for the resource's fields. Same contract as the CPS
  kernel; it belongs in the scaladoc.

---

## 5. Finalizers on the Stack

### 5.1 `Finalizer`

```scala
final private[kernel] class Finalizer[A, B](val release: Arrow[A, Unit, Any], val resource: A)
    extends java.util.concurrent.atomic.AtomicBoolean
    with Arrow.Transform[B, B, Any]
    with Barrier:

    def frame = release.frame

    /** Runs the release exactly once, to completion, and reports whether this call ran it.
      *
      * The row is Any, so the release needs no handler and no drive of the caller's. Running it
      * here rather than deferring it is what makes exactly-once structural: the entry never leaves
      * the stack with an unrun release behind it, so there is no window in which the guard has been
      * spent and the work has not happened. The flag is set before the body runs, so a `false`
      * return means another caller owns the release, not that it has finished: exactly one caller
      * runs each release and no caller waits for another.
      */
    def run(): Boolean =
        if !compareAndSet(false, true) then false
        else
            release(resource) match
                case k: Kyo[?, ?] => discard(Eval.settle(k))   // cold: the release built a node
                case _            => ()                        // ordinary: the body already ran
            true

    def apply[C, S2](v: B < S2, next: Arrow[B, C, S2]): C < S2 =
        discard(run())
        next(v, Arrow.id)
end Finalizer
```

Four properties, each load-bearing:

1. **Self-contained.** `apply` implements the whole semantics, so the drive needs no arm: the generic settled arm
   drives it (`Eval.scala:187-195`). The same entry works when the drive pops it, when a `dump` folded it into a
   continuation a clause replays, and when a fresh drive on another thread pushes it back out of a park. That is
   the complete-value rule.
2. **Exactly once by CAS, with no window.** `extends AtomicBoolean` costs no extra allocation, mirroring the CPS
   kernel's `sealed abstract class Ensure extends AtomicBoolean with Function1[...]`
   (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:142-155`, comment at `:174-175`:
   "ensures the function is called once even if an interceptor executes it multiple times"). Because `run()` both
   flips the flag and completes the work, there is no state in which the flag is spent and the release has not
   happened. Row `Any` is what makes that possible: only a total release can run at the moment of the flip.
3. **The nested drive is cold.** For the `Arrow(f)` form, `release(resource)` calls `f(resource)` directly
   (`Arrow.scala:37`), so an ordinary side-effecting release has already run and returns `()`, taking the `case _`
   arm with no drive. `Eval.settle` is entered only for a release built out of `Effect.defer`.
4. **The second shot still runs the continuation**, `next(v, Arrow.id)` unconditionally, matching the CPS kernel's
   spent `Ensure`, which is transparent (`Safepoint.scala:148-149` at that same path). Row arithmetic:
   `Arrow.Transform[B, B, Any]` requires `C < (Any & S2)`, which is `C < S2`, and that call produces exactly it,
   with no cast.

### 5.2 `Barrier`, and B5

`Stack.dump()` (the no-argument form, `Stack.scala:128-133`) folds a bounded run of entries, stopping at
`entries(...).isInstanceOf[Handler[?, ?, ?, ?]]` (`:130`), and hands the fold to `h(curr, tail)`, `c(curr, tail)`
or `head(curr, tail)` at `Eval.scala:172`, `:181`, `:190`, each inside a `catch` that attaches trace and rethrows.
A `Finalizer` inside that fold would, on a throw from that call, be reachable from nothing: off the stack, inside a
dead local. So `boundary` must stop at a finalizer for the same reason it stops at a handler. This is invariant (F)
applied to the one fold that can be made not to happen.

**B5.** Revision 2 specified `private[kyo] sealed trait Barrier` in `Arrow.scala` with `Handler` (`Handler.scala:9`)
and `Finalizer` (a new `Finalize.scala`) as implementors. Scala 3 `sealed` permits direct extension only from the
same source file, so that does not compile. The fix:

```scala
// Stack.scala, beside its only consumer
/** A stack entry the bounded fold must not cross. `Handler` and `Finalize.Finalizer` are the only
  * implementors: a region because a fold across it is a region crossing, a finalizer because a
  * folded finalizer is reachable from nothing if the arrow that received the fold throws.
  */
private[kernel] trait Barrier
```

Not `sealed`; `private[kernel]` plus the comment carries the closure claim. The precedent is exactly this shape:
`Arrow` is `sealed trait Arrow` (`Arrow.scala:10`) and `Arrow.Transform` is a plain `private[kyo] trait`
(`Arrow.scala:71`) extended from `Handler.scala:9` and `ArrowEffect.scala:35`. `Stack.scala` rather than
`Arrow.scala` is the narrower home: `Stack.dump()` is the only reader and both implementors are already in
`kyo.kernel.internal`, so `private[kernel]` suffices where `private[kyo]` would be needed from `Arrow.scala`.

`boundary` tests `isInstanceOf[Barrier]`; `Stack.find` keeps testing `Handler` by tag (`Stack.scala:95`), because a
finalizer answers no tag. The cost claim needs care: this replaces a test against a `sealed abstract class` with
one against a trait, which is an interface check, so "an equal-cost test" is a hypothesis (13).

### 5.3 The walk

One walker, used by all three recovery paths. Two shapes in it correct revision 2.

```scala
// Finalize.scala

/** A value position. The same object can occupy a value position and an arrow position at once: the
  * drive's clause dispatchers are their own `contA` (Eval.scala:77, :120). The wrapper keeps the two
  * visits apart, the same way EffectTrace.Builder.Node does (EffectTrace.scala:277-280).
  */
final private class Node(val kyo: Kyo[?, ?])

/** The finalizers reachable from what has been pushed, innermost first.
  *
  * A worklist rather than recursion: a park taken over a long map chain is a left-nested `Defer` as
  * deep as the chain, and recursing on `value` would overflow. `Kyo.render` (KyoInternal.scala:46-57),
  * `Arrow.Chain.toString` (Arrow.scala:98-119) and EffectTrace.Builder.drain (EffectTrace.scala:258-293)
  * are the three existing walkers and all three are loops. This one carries no fuel cap, because a
  * dropped finalizer is a leak, not a truncated rendering.
  */
final private class Walk:
    private val work = new ArrayDeque[Any]
    private val out  = ArrayBuffer.empty[Finalizer[?, ?]]

    def push(item: Any): Unit =
        if !((item: AnyRef) eq Arrow.Id) then discard(work.prepend(item))

    def pushValue(v: Any): Unit =
        v match
            case n: Nested[?] => pushValue(n.value)
            case k: Kyo[?, ?] => push(new Node(k))
            case _            => ()

    @tailrec def drain(): Chunk[Finalizer[?, ?]] =
        if work.isEmpty then Chunk.from(out)
        else
            work.removeHead() match
                case f: Finalizer[?, ?]         => discard(out.append(f))
                case c: Arrow.Chain[?, ?, ?, ?] => push(c.b); push(c.a)
                case c: Captured                => push(c.captured)
                case n: Node =>
                    n.kyo match
                        case k: Kyo.Defer[?, ?, ?, ?]         => push(k.contB); push(k.contA); pushValue(k.value)
                        case k: Kyo.Handle[?, ?, ?, ?, ?]     => push(k.cont); pushValue(k.value)
                        case k: Kyo.Suspend[?, ?, ?, ?, ?, ?] => push(k.cont)
                case _ => ()
            drain()
end Walk

/** Runs every finalizer, so one failure cannot strand the outer resources. The first failure is the
  * one that propagates and the rest are suppressed onto it. With a `primary` in hand every failure
  * is suppressed onto it and nothing new is thrown, because the primary is already unwinding.
  */
private def runAll(fs: Chunk[Finalizer[?, ?]], primary: Throwable): Unit =
    var first = primary
    fs.foreach { f =>
        try discard(f.run())
        catch case t: Throwable => if first eq null then first = t else first.addSuppressed(t)
    }
    if (first ne null) && (first ne primary) then throw first
end runAll

/** The releases the value still owes, innermost first. `()` when it owes none, the ordinary case
  * for a value that is not a park.
  */
private[kyo] def compose(v: Any < Nothing): Unit < Any =
    val w = new Walk
    w.pushValue(v)
    val fs = w.drain()
    if fs.isEmpty then () else Effect.defer(runAll(fs, null))
```

The other three entry points differ only in what they push: `unwind(stack, primary)` pushes `stack.entry(i)` for
`i` in `[0, size)` in reverse so index 0 drains first, `discard(stack, n)` does the same over `[0, n)`, and
`unwindArrow(a, primary)` pushes one arrow. All three then `runAll(w.drain(), primary)`.

- **`Defer`'s value comes first**, which is B4: `push` prepends, so the last item pushed drains first and the order
  is `value`, `contA`, `contB`. That is the order the pieces run in, so the collected order is innermost first,
  which is what `runAll` needs. Identical to `EffectTrace.scala:277-280`.
- **`Nested` is stripped before matching `Kyo`**, mirroring `EffectTrace.scala:213-217`. Without it a boxed payload
  hides its node.
- **`Captured` in arrow position stops there** and does not re-push `value`: its `value` is the clause, which the
  drive is separately driving and which reaches the walk through the residual. Reached in *value* position it goes
  through `Node` and the `Defer` arm, which pushes `contA` (itself, in arrow position, terminating at the
  `Captured` arm) and `value`. Both positions are covered and neither loops.
- **No `Acquire` arm and no `pending` parameter** (rules 1 and 2); an `Acquire` reaches `case _ => ()`. **No
  `Handler` arm** either, since a `Handler` is neither a `Finalizer` nor a `Chain` nor `Captured`.

`Stack.entry(i)` (`Stack.scala:87`) is the indexed read the EffectTrace work added, described there as "an indexed
read of a slot without knowing its kind". It is exactly the accessor `unwind` and `discard` need, so this adds no
`Stack` surface beyond `Barrier` and `Captured`.

The throw path is the boundary `catch` the EffectTrace work already put in (`Eval.scala:205-210`), gaining one
line before `splice` so a suppressed release failure is in place when the trace is written:

```scala
catch
    case ex: Throwable =>
        Finalize.unwind(stack, ex)
        EffectTrace.splice(ex)
        throw ex
```

It runs before the `finally`, which matters: `Stack.release` calls `clear()`, and clearing a stack that still holds
finalizers would drop them silently. The boundary is the right home rather than a per-bracket `try`, because `loop`
is one flat `@tailrec` method with no per-bracket Java frame to hang one on.

### 5.4 B3: the five folds the drive hands out

Invariant (F) constrains the drive. There are five `dump(pos)` sites and each is checked here. `dump(pos)` folds
`[0, pos)`, the region interior between the suspension and its handler, which is exactly where a bracket acquired
inside the region has its `Finalizer`.

**Sites 1 and 2: the settled-clause continue arms, `Eval.scala:96-97` and `:142-143`.**

```scala
val k = stack.dump(pos);  loop(r._1.map(k))                    // today
val k = stack.dump[OX[CX], Any, EX & S](pos);  loop(Effect.defer(r._1, k))   // specified
```

`map` on a pending value builds `Effect.defer(kyo, arrow, next)` where `arrow` is an anonymous `Arrow.Transform`
closing over `k` (`Pending.scala:22-29`), so today the fold, and the `Finalizer` in it, are a closure capture with
no structural path. The window is the whole evaluation of `r._1`, arbitrary user code: a throw in there reaches the
boundary with the finalizer unreachable, and a park in there produces a value whose walk finds nothing.

`Effect.defer(r._1, k)` is observationally the same value, since `map`'s pending arm is literally
`Effect.defer(kyo, arrow, Arrow.id)`, which `Effect.defer(v, a, b)` collapses to `Effect.defer(v, a)`
(`Effect.scala:33-34`). The difference is that `k` becomes the node's `contA`, so the drive pushes it and
`Stack.push` flattens the chain into entries before `r._1` runs, making the finalizer a stack entry for the whole
window. It is also one fewer node and one fewer anonymous class per region continue.

Two disclosures. The entries the drive sees change from one anonymous arrow whose `frame` is `Frame.internal`
(`Eval.scala:29`) to the interior's real arrows, and `EffectTrace.Builder.frame` skips `Frame.internal`
(`EffectTrace.scala:189`), so a spliced trace taken in that window gains the interior's user frames. And this is a
shape change on the region-continue path, which per the skill makes the emitting row mandatory (13).

**Sites 3 and 4: the suspended-clause dispatchers, `Eval.scala:70` and `:113`.** Here the fold cannot be avoided.
The clause lives *outside* the region it serves (`HandlerLoop.run` returns `Outcome[O[X] < (E & S), B] < S`,
`Handler.scala:32`), which the skill states as the reason outer-row code must run with the region absent, so
`dump(pos)` and `discard(stack.pop())` (`Eval.scala:70-71`, `:113-114`) must happen before the clause is driven and
`k` is captured into the dispatcher. The dispatcher is itself a stack entry (`contA = this`, `Eval.scala:77`,
`:120`), so the fix is to make its capture visible:

```scala
// Stack.scala, beside Barrier
/** An entry holding a folded continuation it has not pushed back. Its finalizers are still owed, so
  * the recovery walk has to see them; without this they are a closure capture and structurally
  * invisible. The two implementors are the drive's clause dispatchers.
  */
private[kernel] trait Captured:
    def captured: Arrow[?, ?, ?]
```

```scala
// Eval.scala:73-90, changed lines only
    with Captured:                                     // <- new
    def captured = k                                   // <- new
    ...
            case r: Loop.Continue[OX[CX] < (EX & S)] @unchecked =>
                Effect.defer(r._1, k, h)               // <- was Effect.defer(r._1.map(k), h)
```

`Effect.defer(r._1, k, h)` is the three-argument form (`Effect.scala:32-39`): the drive pushes `h`, flattens `k`
above it, then loops `r._1`. That is the same stack the two-node form reaches one turn later, minus a node and a
closure. `Eval.scala:125` takes the same change with `HandlerLoopState(h, r._1)` in place of `h`.

**Site 5: `HandlerCont`, `Eval.scala:53-55`.** `k` becomes the `cont: O[X] => A < (E & S)` a clause receives as a
`Function1` (`Handler.scala:29`). There is no structure to expose: the clause owns it. This is the one site (F)
cannot cover, and the compensating measure is local, using the local and the `catch` that already exist:

```scala
val k = stack.dump[OX[CX], AX, EX & S](pos)
val next =
    try h.run(kyo.input, k(_))
    catch
        case ex: Throwable =>
            // the interior left the stack inside `k`. A throw out of the clause means no one will
            // ever apply it, so its releases run here or not at all
            Finalize.unwindArrow(k, ex)
            EffectTrace.attach(ex, kyo, k, stack)
            throw ex
loop(next)
```

If the clause called `k` and then threw, the value `k` produced is discarded and running the same `Finalizer`
objects is correct; if it called `k` and returned, no catch fires and the finalizers are back on the stack
structurally, because `k(o)` is `Arrow.Chain.apply(v) = Effect.defer(v, a, b)` (`Arrow.scala:91-92`) which the
drive pushes. What stays uncovered is the clause that **drops** `k` and returns normally, which is the zero-shot
hole (5.8) and is not made worse here. That is why (F) says "reachable from the drive **or from the value it
belongs to**": once a clause takes ownership of `k`, the drive is not the owner any more.

**No sixth site.** `dump(size)` in `reify` hands the fold to the park value, which owns it, so (F) holds through
the `Chain` arm of the walk. `dump()` with no argument stops at a `Barrier`. The invariant is checkable by grepping
`Eval.scala` for `dump(`, `truncate` and `clear`.

### 5.5 Region discard

`stack.truncate(pos + 1)` fires when a `handleLoop` or `handleLoopState` clause answers `Loop.done`
(`Eval.scala:100`, `:147`). It drops the handler at `pos` and the whole interior `[0, pos)`. The interior is being
abandoned, so its resources must be released, not dropped.

Revision 2 added `Stack.compact(n)`, keeping the finalizers on the stack at the top. This revision routes the site
through the same path as a dropped park, which is the review's question 7 and, on the merits, the right answer:

```scala
case _ =>
    Finalize.discard(stack, pos + 1)   // walks entries [0, pos], runs their releases innermost first
    stack.truncate(pos + 1)
    loop(o)
```

Why this rather than `compact`:

- **One mechanism instead of three.** `unwind`, `discard` and `compose` become three seeds for one walker. Revision
  2 had `compact`'s hand-written reordering as a fourth way for a finalizer to change position.
- **`Stack` is untouched, and N8 evaporates.** `compact` had no `head != tail` guard where its sibling `truncate`
  does (`Stack.scala:137`, pinned by `StackTest.scala:238-243`), so `compact(n)` for `n > size` was a spec defect
  being added next to a method that does not have it.
- **The `Captured` and `Chain` cases come for free.** `compact` scanned raw entries for `isInstanceOf[Finalizer]`
  and would have missed a finalizer inside a `Chain` entry or a `Captured` dispatcher.
- **Ordering and throw behavior are the same.** The walk is innermost first and `runAll` aggregates. With `compact`
  a throwing finalizer relied on the boundary `catch` reaching the survivors; here they have all had their turn
  before anything is thrown.

Cost, stated: the site gains a walk of `pos + 1` entries where it had none. `truncate` already loops `n` times
nulling slots (`Stack.scala:135-143`), so this is a type test per slot on a loop that exists, but it is on the
region-completion path and it is unmeasured (13).

**Consequence, stated plainly:** the releases of a discarded region run after the region's handler is gone. Row
`Any` makes that unconditionally correct: the release could not have named the region's effect in the first place.

### 5.6 `finalizeResources`, and B6

```scala
// Pending.scala, a NEW extension block. The existing one at :18 is
// `extension [A, S](inline self: A < S)` and every member of it is inline (:21, :43, :65, :87,
// :109, :130, :281, :284, :290); an `inline` parameter is only legal on an `inline` method, so a
// non-inline def cannot join it
extension [A, S](self: A < S)
    /** The releases this value still owes, innermost bracket first, as a value the caller drives.
      *
      * Runtime machinery, not user surface: a caller holding a value has no way to know whether it
      * is a park, and calling this on a live value spends its releases. The scheduler calls it when
      * dropping a continuation it will never resume.
      */
    private[kyo] def finalizeResources: Unit < Any = Finalize.compose(self)
```

`A < S` conforms to `Any < Nothing` by variance (`<[+A, -S]`, `Pending.scala:13`), so the call needs no cast. Two
extension blocks on the same type in the same object are legal, and the split is what B6 requires. Revision 2 wrote
"inside the existing extension block, deliberately NOT inline" above a snippet that already spelled
`extension [A, S](self: A < S)` without `inline`; both statements were wrong and they disagreed with each other.

**Visibility (needs sign-off).** The brief called this "the public extension on the pending type". The prior art
was `private[kyo] def finalizeBracket(outcome: Maybe[Result.Error[Any]]): Unit`
(`3a95636fa8:kyo-kernel2/shared/src/main/scala/kyo/kernel/Pending.scala:38`) with the comment "runtime machinery,
not user surface". Recommendation `private[kyo]`, for the reason in the scaladoc above.

### 5.7 `Stack.dump`'s `wrap` guard (N11)

The park needs one property from `dump`: **whole-stack `dump(size)` followed by `push` restores the same entry
sequence.** That is what makes a resumed region findable at the position it was at.

`Stack.push`'s `count`/`fill` descend into chains (`Stack.scala:47-64`), so an entry that is itself an
`Arrow.Chain` re-flattens into two entries unless the fold wraps it as `new Arrow.Chain(c, Arrow.id)`, which makes
`count` see `c.a` as one entry and `c.b` as identity. The guard is `if wrap && !handlers && !(c.b eq Arrow.Id)`
(`Stack.scala:116`), and `handlers` turns true as soon as a `Handler` is folded (`:122`). The fold runs from
`pos - 1` down to `0`, that is outermost to innermost, so for a whole-stack park fold the `below` wrap is inactive
for every entry above the outermost handler. Revision 2 asserted the round trip without noticing this. The reason
it does not break: the arm that wraps a chain-valued *entry* is the separate one at `:120`, which is not gated on
`handlers`, and `count`/`fill` descending a chain is correct for every chain that was not itself an entry.

That is an argument, not a proof, so the honest treatment is a test. Test 39 pins the round trip with a `Chain`
entry both above and below a handler. **What breaks if it fails:** a resumed park has one entry split into two, so
`Stack.find` returns a position one off and a region answers the wrong operation, or a `Handler` is no longer at
the index `handler(pos)` reads (`Stack.scala:81-82`) and the cast there fails.

### 5.8 The hole that stays open

A `handleCont` clause receives its continuation as a `Function1` (`Handler.scala:29`, built at `Eval.scala:53-55`).
Called once, the release runs at its place in the continuation. Called many times, see question 5. Called zero
times, the resource is never released: the clause dropped the only reference, and it dropped it as a `Function1`,
so it could not have finalized it even if it wanted to.

That is inherent to a first-class continuation and it is not closed here. The correct home for a lifetime that must
not depend on continuation usage is the layer above, which is what `Scope` is: its finalizers live in a queue owned
by `Scope.run` (`kyo-core/shared/src/main/scala/kyo/Scope.scala:157-191`), not in the continuation. Two apparent
closures are rejected with reasons in 12.

---

## 6. B7: which side unnests

Today's `loop` returns `Any`. Its settled arm reads `val r = curr.unsafeGet` (`Eval.scala:157`) and returns `r`
when the stack is empty (`:197`); `unsafeGet` (`Pending.scala:290-293`) is the single permitted unnest at delivery.
`Eval.apply` then casts that to `A` (`:204`).

`partial` needs the *pending* form, and its consumers prove it: `EvalTest.scala:993` asserts
`parked.evalNow == Maybe.Absent`, and `evalNow` (`Pending.scala:284-287`) itself performs the unnest
(`case _ => Maybe(self.unsafeGet)`). So a `partial` that returned an already-unnested value would hand a
once-unnested value into a `<`-typed position and let `evalNow`, or the next drive, unnest it again. When `A` is
itself a computation, that reads the user's data as a node. This is the double-delivery defect the representation
contract exists to prevent, and the skill records that "one such removal passed the compiler and failed nine
nesting tests".

**Recommendation (needs sign-off, question 4): `drive` returns the union and `apply` unnests.** That is the prior
shape, `Nested.unnest[A](loop(v, armed = false, neverStop))` at `1cf05637e8:20` with `partial` at `1cf05637e8:33`
returning `loop(...)` uncast on that axis. Three edits:

```scala
// Eval.scala settled arm: `r` has exactly two readers today, the HandlerLoopState delivery at :163
// and the empty-stack return at :197. The delivery keeps its unnest; the return hands back the union
case _ =>
    if !stack.isEmpty then
        ... h.apply(s.getOrElse(h.initialState), curr.unsafeGet.asInstanceOf[AX])
    else curr

Eval.apply:    drive(stack, v.asInstanceOf[Any < Nothing], null).unsafeGet.asInstanceOf[A]
Eval.partial:  drive(stack, v.asInstanceOf[Any < Nothing], stopFn).asInstanceOf[A < S]
```

`Nested.unnest` no longer exists (`Nested.scala` carries only `lift`, `:10-13`); `unsafeGet` is its equivalent and
is already the reader the drive uses. This also makes an old claim true: revision 2's cast table said `partial`'s
pair was "byte-identical to the prior implementation at `1cf05637e8:33`" while proposing `drive: Any < Nothing`,
which it was not. Under this recommendation the pair really is the prior pair.

**The alternative**, if the owner prefers `drive` to unnest, is that `partial` must re-lift its result through
`Nested.lift` (`Nested.scala:10-13`). That is a second nest site, and the contract says nest exactly once at the
public lift boundary, which is why it is not the recommendation.

**A side effect worth naming.** Moving `curr.unsafeGet` into the `HandlerLoopState` arm removes one type test from
every settled turn whose head is not a `HandlerLoopState`, the hottest arm in the drive. Plausible small win,
unmeasured (13). **Pinned by test 21**, a park whose result type is itself a computation; the corpus has no such
case.

---

## 7. Every touched file, and whether it summons the lift

**Correcting revision 2's premise (N2).** Revision 2 wrote that summoning `CanLift` in a new kernel file "is
exactly the move the skill says deepens the cascade". That is over-stated. `CanLift.derived` is a plain
`inline given` returning `null` (`CanLift.scala:29`), as are `derivedCaseObject` (`:31`) and `nothing` (`:35`).
Only `derivedSingleton` (`:33`) splices `CanLiftMacro.checkSingleton`. The comment that said so was removed in
`ad4d2400f5` ("trim the CanLift given comments"); the code is unchanged. So a summon at a non-singleton type
expands no macro and suspends no unit. The column below records **which given resolves**, not merely whether a lift
fires.

| file | change | lift summon |
|---|---|---|
| `kyo/kernel/Effect.scala` | add `bracket`, building `Effect.defer(acquire, new Acquire(Arrow(release), Arrow(use)), Arrow.id[B])`. `Arrow(f)` (`Arrow.scala:34`) takes `inline f: A => B < S`; `release` and `use` arrive already at `<` type | **none**; no bare value in a `<` position |
| `kyo/kernel/internal/Finalize.scala` | new: `Acquire`, `Finalizer`, `Node`, `walk`, `compose`, `runAll`, `unwind`, `unwindArrow`, `discard` | **yes, one shape**: `compose` returns `Unit < Any` with a `()` branch. `Unit` is not a `Singleton`, so it resolves through `derived` (`CanLift.scala:29`) and `Implicits.lift`'s primitive arm (`Implicits.scala:12-13`) compiles it to a cast. No macro, no suspension |
| `kyo/kernel/internal/Eval.scala` | `drive`, `partial`, `reify`, `settle`, the poll, the five fold-site changes (5.4), the region-discard change (5.5), `Finalize.unwind` in the boundary catch, the unnest move (6) | **no new one**. The file already summons at `:89` and `:134` (`next(apply(o.unsafeGet), Arrow.id)`, a raw `BX` into a `BX < S2` slot; `BX` is an abstract type member at `:26`, so `derived` resolves and no macro expands) |
| `kyo/kernel/internal/Stack.scala` | add `trait Barrier` and `trait Captured`; `dump()`'s `boundary` tests `Barrier` | **none**; no `<`-typed expression exists in `Stack` at all |
| `kyo/kernel/Pending.scala` | add a non-inline `extension [A, S](self: A < S)` block with `finalizeResources` | **none**; `Finalize.compose(self)` returns `Unit < Any` and `self` conforms by variance |
| `kyo/kernel/internal/Handler.scala` | `Handler` also extends `Barrier` | **none** |
| `kyo/kernel/internal/EffectTrace.scala` | **unchanged**, see below | n/a |
| `kyo/kernel/internal/KyoInternal.scala` | **unchanged** under the recommendation; under the node fork, add `Bracket` | n/a |

**`EffectTrace` (N14).** No change, two consequences to record. `Finalizer` and `Acquire` are `Arrow.Transform`s
and neither is a `Handler` nor a `Chain`, so the sweep reaches them at `case a: Arrow[?, ?, ?] => frame(a.frame)`
(`EffectTrace.scala:288-289`), after the `Handler` arm (`:281`) and the `Chain` arm (`:285`). With
`Finalizer.frame = release.frame` and `Acquire.frame = use.frame`, a trace crossing a live bracket shows the
release site and the use site as effect frames. That is deliberate (a pending chain that will run the release at
line N should say so) and it is new user-visible trace content. `Builder.entries` (`:247-256`) reads slots through
`Stack.entry(i)` and is unaffected. `Builder.drain` does not descend into `Captured.captured`, so a fold held by a
dispatcher stays invisible to a trace taken in that window; adding the descent would improve trace quality, has no
bearing on resource correctness, and belongs to whoever owns `EffectTrace`.

**`Eval.apply` stays `inline` (N3, question 3).** Revision 2 made "extract the loop into a non-inline `Eval.drive`,
replacing the inline `Eval.apply`" a hard prerequisite. It is not, and the review is right on all three counts: the
macro argument does not hold, it reverses the owner's explicit `inline` decision, and it changes what
`EffectTrace.isPlumbing` filters, a consequence `EffectTrace.scala:119-122` records deliberately ("With
`Eval.apply` inline, the drive's own frames no longer appear under `kyo.kernel.internal.Eval` ... There is no
correct fix here"). What `Finalize` actually needs is one way to settle a release that built a node:

```scala
// Eval.scala, beside apply
/** Settles a value with its own stack. The one non-inline entry into the drive, for
  * `Finalize.Finalizer.run`: `apply` is `inline`, so a call from another kernel file would expand
  * the whole drive body there.
  */
private[kernel] def settle(v: Any < Nothing): Any = apply(v)
```

Cost: one more expansion of the drive body inside `Eval.scala` itself, where it already lives, so a second copy of
roughly 1500 bytes in the same class file and no new file summoning anything. Frames from that expansion carry
class `kyo.kernel.internal.Eval$`, which `isPlumbing`'s first predicate (`EffectTrace.scala:126`) does filter, so a
release that throws inside `settle` has its nested drive frames filtered while the release's own frame survives.
That is intended, and a narrower version of what a full de-inline would do everywhere. `partial` needs the loop
with a `stop` parameter, so `Eval.scala` grows a shared `drive` used by all three entry points with `apply` still
`inline` around it; whether `drive` itself is `inline` is the same question as whether `apply` is, and this design
does not reopen it.

---

## 8. Casts introduced, each categorized

| # | site | cast | category |
|---|---|---|---|
| 1 | `Eval.partial` | `v.asInstanceOf[Any < Nothing]` in, `.asInstanceOf[A < S]` out | erasure-forced: the drive's currency sits at the top of the row lattice and every constructor names a concrete row. Byte-identical to `1cf05637e8:33` under the section 6 recommendation |
| 2 | `Eval.reify` | `curr.asInstanceOf[Any < Any]`, result back to `Any < Nothing` | erasure-forced, same kind as the existing `v.asInstanceOf[Any < Nothing]` at `Eval.scala:204` |

That is the whole list. Row `Any` deleted the ones revision 1 had, and rule 1 of 1.2 deleted the rest: `Finalize`
does not cast a release into a closed row because the release *is* at row `Any`, so the prior art's one cast here
(`3a95636fa8:Finalize.scala:100`, "the walk is structural, so the release row is existential here") has nothing
left to justify; `finalizeResources` does not cast, by variance; the walk binds through typed patterns
(`case f: Finalizer[?, ?]`), which is step 2 of the ladder and not step 4, so the runtime test is identical and the
claim is visible; and `Stack` does not cast, because `compact` is gone (5.5) and `unwind` reads through the
existing `Stack.entry`.

Checked for and found unnecessary, so a later reader does not add them back: `Finalizer.run` (its own fields at its
own type parameters); `Finalizer.apply` (`Arrow.Transform[B, B, Any]` requires `C < (Any & S2)` which is `C < S2`,
and `Arrow.Id.apply(v, Arrow.id)` short-circuits to `v`, `Arrow.scala:130-134`); `Acquire.apply` (`v.unsafeGet` is
the existing settled reader and the row arithmetic is contravariance); the bare-operation rebuild (`IX, OX, EX, CX`
bound by the pattern at `Eval.scala:41`); and `Effect.defer(r._1, k, h)` (`Arrow[AX, BX, S] <: Arrow[AX, BX, EX & S]`).

---

## 9. Decisions needing sign-off

Three of the review's questions are **not** here, because the answer was clear and this document takes it.

- **Question 1, where a finalizer's identity lives.** The decision (1.1) implies no drive-side registry: the
  mechanism is the `Node` wrapper, the `Captured` marker and two `map`-to-`Effect.defer` substitutions, all pure
  structure. One leg is drive-local rather than structural, the `HandlerCont` catch at `Eval.scala:53-59`, and it
  is named as such in 5.4 rather than folded into the structural claim. A registry is rejected in 12.
- **Question 2, what a park owes when the `Acquire` has not run: nothing, no resource exists.** Taken. It removes
  B1's minting arm and B2 entirely (1.3).
- **Question 3, de-inlining the drive: no.** `Eval.apply` stays `inline`; `Eval.settle` is the narrow entry and
  section 7 states what it costs.

The rest are genuinely the owner's.

**A. The `Bracket` node (3.1) and the `Park` node (4.1).** Recommendation: neither. The reduction is complete and
`d4e59ffa56` already ruled on `Park`.

**B. `finalizeResources` visibility (5.6).** Recommendation: `private[kyo]`.

**C. Question 4, which side unnests (6).** Recommendation: `drive` returns the union, `apply` unnests, the
`1cf05637e8:20` shape. The alternative adds a second nest site to a contract that says nest exactly once.

**D. Question 5, multi-shot capture. Two cases, and the design answers them differently.** Revision 2 stated only
the second and asserted the kernel cannot re-acquire, which is false for the first.

- *Capture taken above the acquire point*, while `acquire` is still suspended: `dump(pos)` folds the `Acquire`
  arrow itself into `k`, so each shot re-runs the acquire remainder, calls `Acquire.apply` again, and mints its own
  `Finalizer` for its own resource. **Per-shot acquire, per-shot release.** Nothing special is needed; it is what
  rule 1 gives, and it is the correct reading of a multi-shot continuation, where each shot is an independent run
  of the bracket.
- *Capture taken below the finalizer*: every shot holds the same `Finalizer`. The first releases; the second shot's
  remainder runs against a released resource, because the CAS makes the second `run()` a no-op and `apply` still
  runs `next`. **Use-after-release on the second shot.** Recommendation: accept and document. It matches the CPS
  kernel's spent `Ensure`, and the alternatives are worse: failing the second shot breaks legitimate replay, and
  re-acquiring is not available because the acquire is not in the captured range. It belongs in the scaladoc and in
  the concession table, and its pin asserts the hazard rather than asserting that both shots complete (test 27).

**E. Question 6, is `Effect.bracket` `inline`?** The prior art was (`3a95636fa8:Effect.scala:56`, `:88`, both
`private[kyo] inline def bracket[R, A, S]`). Revision 2 specified non-inline without noting the reversal (N12).
The mechanism: `Arrow(f)` is `inline` either way, so `inline def bracket` mints the `use` and `release` arrows at
each call site with the user's bodies fused into `override def apply(v: A) = f(v)` (`Arrow.scala:37`), while a
non-inline `bracket` mints two anonymous classes once inside `Effect.scala`, each calling through a `Function1`.
The `Frame` is correct either way because `bracket` takes it `using`. Recommendation: ship non-inline and revisit
with a number, because the skill says `inline` is never added without approval and the fusion argument here is
unmeasured. The owner may reasonably say the prior art already settled it.

**F. Question 7, region discard (5.5).** Recommendation: route it through the abandonment path and drop `compact`.

**G. The throw policy**, named as open in the brief. Recommendation: every finalizer runs, the first failure
propagates, later ones are suppressed onto it, and a finalizer that threw is not retried, because its CAS flipped
before the release body ran and a retry would double-release whatever part succeeded.

---

## 10. The concession table

| concession | justification | minimal scope | protection | pinning test |
|---|---|---|---|---|
| `release` is at row `Any`, so a finalizer cannot perform effects | it must be runnable with no drive, no handlers and no row obligations, because the abandonment path has none of the three | one field's row; every other row in the node is `S` | **type-level**: `<` is contravariant in `S`, so an effectful release does not conform to `A => Unit < Any` and does not compile, while `Arrow[A, Unit, Any]` still conforms at every use site. That makes "the parked value is the only owner of these resources" compiler-checked | 24, 26, plus a `// does not compile` case for the non-conformance |
| `Finalizer` runs its release eagerly inside `apply`, entering a nested drive when the release built a node | deferring it opens a window between the CAS flip and the drive reaching the release node, in which a park produces a value with a spent guard and unrun work | one method, `Finalizer.run` | the nested drive is entered only for a release built out of `Effect.defer`; an ordinary side-effecting release has already run when `release(resource)` returns (`Arrow.scala:37`) | 6, 24; the cold-drive frequency needs a number (13) |
| `Finalizer` is a `Barrier`, so `dump()` stops folding at it | a folded finalizer is reachable from nothing if the arrow that received the fold throws | one condition in `Stack.dump()`'s `boundary` (`Stack.scala:130`) | `Stack.find` still tests `Handler` by tag, so dispatch is untouched | 8, 38; the equal-cost claim is a hypothesis (13) |
| the drive's clause dispatchers expose their fold through `Captured` | the fold is otherwise a closure capture, structurally invisible to both recovery paths (B3) | one abstract member on two anonymous classes that already exist | the walk has a `Captured` arm and invariant (F) is grepable against `dump(` | 10, 11 |
| the `HandlerCont` fold is recovered from a `catch`, not from structure | the clause receives a `Function1`; there is nothing to expose | one `catch` that already exists (`Eval.scala:56-59`) | the clause that drops `k` and returns normally is unchanged and is the documented zero-shot hole | 9, 28 |
| the drive polls a `stop` function at the top of `loop` | partial evaluation needs a park point and every arm must be covered | one reference comparison, `null` for `Eval.apply` | `reify` is a separate method, so no cold code enters `loop`'s bytecode budget | 16; the poll cost needs numbers (13) |
| the second shot of a capture taken below a finalizer observes a released resource | closing it needs a re-acquire the captured range does not contain | documented, not mechanised | matches the CPS kernel's spent `Ensure`; the shot runs to completion rather than failing unpredictably | 27, which asserts the hazard |
| a `handleCont` clause that drops its continuation leaks | closing it needs a sweep that cannot distinguish a dropped continuation from a legitimately captured one | documented, not mechanised | the layer above owns lifetimes that must not depend on continuation usage (`Scope.scala:157-191`) | 28, named so it reads as a specification |

---

## 11. Pinning tests

Placement follows the module's rule that a test file shares a prefix with its source: `Eval.partial` and the park
in `EvalTest.scala`, stack behavior in `StackTest.scala`, `Effect.bracket` in `EffectTest.scala`, the walk in a new
`FinalizeTest.scala`, region interactions in `ArrowEffectTest.scala`. Each entry states **what breaks if the
property fails**, because the review found revision 2 listing tests that would not catch what they claimed.

**Bracket, normal path (`EffectTest.scala`)**

1. acquire, use, release in that order, each exactly once, on a settled acquire. *Breaks if:* the `Acquire` arrow
   installs the finalizer below `cont`, so the release runs after downstream code.
2. same on a pending acquire that suspends on an operation a region answers. *Breaks if:* `Acquire.apply`'s pending
   arm loses `this` and the registration never happens.
3. `bracket(...).map(f)` records release before `f`. *Breaks if:* composition ever fuses into the use chain, the
   failure `3a95636fa8` needed `Kyo.exitStep` for.
4. release runs before an effectful downstream, under a `handleCont` region and under a `handleLoopState` region,
   two cases, and the state the handler reached at the boundary is the state the downstream sees. *Breaks if:* the
   finalizer entry lands on the wrong side of a region crossing.
5. nested brackets release innermost first. *Breaks if:* the finalizer is pushed below rather than above `next`.
6. a release built out of `Effect.defer` runs to completion. *Breaks if:* `Finalizer.run`'s `case k: Kyo` arm or
   `Eval.settle` is missing. The only test that exercises the nested drive.

**Bracket, abnormal paths (`EffectTest.scala`)**

7. a throw inside `use` after it suspended releases, then propagates the original exception. *Breaks if:*
   `Finalize.unwind` is not called before `Stack.release` clears the slots (`Eval.scala:211-213`).
8. a throw inside `use` with a `dump()`-sized run of entries above the finalizer still releases. *Breaks if:*
   `Barrier` is not wired into `boundary` and the finalizer left the stack inside the fold at `Eval.scala:170`,
   `:179` or `:188`. Revision 2's tests 8 to 11 did not reach this.
9. a throw out of a `handleCont` clause, with a bracket acquired inside the region, releases. *Breaks if:* the
   `unwindArrow` call in the `HandlerCont` catch is missing. B3's site 5.
10. a throw during the evaluation of `r._1` on a region-continue, with a bracket acquired inside the region,
    releases. *Breaks if:* site 1 or 2 of 5.4 still uses `map` and the finalizer is inside the closure.
11. a park taken during the evaluation of a suspended `handleLoop` clause, then abandoned, releases. *Breaks if:*
    the dispatcher does not implement `Captured` or the walk has no `Captured` arm. B3's sites 3 and 4.
12. a `use` that throws synchronously, before it suspends, releases. *Breaks if:* `Acquire.apply` builds the
    `Finalizer` after calling `use`, which is what revision 2 specified.
13. 100k sequential brackets in one drive complete without a stack overflow. *Breaks if:* `Acquire.apply` ever
    fuses instead of returning a node, the invariant that makes its missing safepoint pair correct (N1).
14. a `Loop.done` discarding a region containing a bracket releases it and the answer still flows; same nested two
    regions deep. *Breaks if:* `Eval.scala:100` or `:147` truncates without the `Finalize.discard` call.
15. a bracket whose `use` settles to a `Result.Error` releases and is told nothing.

**Park and partial (`EvalTest.scala`)**

16. the five restored cases at `EvalTest.scala:949-1024` (N13 corrects revision 2's `:193-262`): a preemption stop
    reifies and resumes with handler state (`:956-970`), the `stop` function ends the slice (`:972-981`), `partial`
    completes when nothing stops (`:983-985`), an unhandled operation parks for a handler installed later
    (`:987-997`) and with regions above it intact (`:999-1015`), and a stop between slices short-circuits by
    identity (`:1017-1023`, assertion at `:1021`).
17. an unhandled `suspendWith` operation parks and is performed exactly once by the resumed computation. New and
    required (N7): revision 2 claimed `:987-997` pinned the `kyo.cont` double-stacking guard, and it does not,
    because `ArrowEffect.suspend`'s `cont` is `Arrow.id` (`ArrowEffect.scala:24`) which `Stack.push` drops
    (`Stack.scala:35`), making the two reifications indistinguishable. `suspendWith`'s `cont = this`
    (`ArrowEffect.scala:39`) is the shape that bites, and `d4e59ffa56` records four red tests all with
    `suspendWith`. *Breaks if:* the `pos < 0` branch reifies `kyo` and the effect runs twice.
18. the two commented `ArrowEffectTest` cases: a handler installed after a partial evaluation answers the parked
    operation (`:446-451`), and a parked stateful region resumes with its state and `done` (`:590-600`).
19. **park in a foreign drive:** a parked value evaluated on a different thread completes. *Breaks if:* the park
    value retained a `Stack` or a slot.
20. **replay:** a parked value evaluated twice produces the same answer both times. *Breaks if:* `reify` hands out
    something that is not a complete value.
21. **a park whose result type is itself a computation** (`A = Int < Any`), driven through `partial` twice, then
    settled. *Breaks if:* section 6 goes the other way without the compensating re-lift and the payload is unnested
    twice and read as a node. B7's pin; the corpus has nothing like it.
22. `Eval.apply` still reports `bug("unhandled suspension")`; parking is `partial`-only.
23. `SafepointConcurrencyTest.scala:77-106` restored, not "unchanged" (N6): the `Eval.partial` call is at `:92` and
    restoring it is the work. It is a **stop-protocol** park, not a budget park, consistent with 4.2 establishing
    that budget exhaustion never parks.

**Resources across parks and captures (`FinalizeTest.scala`)**

24. **release exactly once:** parked mid-`use`, then resumed, releases once. *Breaks if:* a second mint site exists.
25. **release on abandonment:** the same value, not resumed, releases when `finalizeResources` is driven. *Breaks
    if:* the walk misses `Defer.value`, which is B4.
26. **abandonment with no drive and no handlers:** `finalizeResources` on a park taken from inside two regions
    releases without reaching `bug`. *Breaks if:* the release row is not `Any`. The row-`Any` pin.
27. **multi-shot below the finalizer:** a `handleCont` clause calling its continuation twice releases exactly once,
    both shots complete, **and the second shot observes the resource in its released state**. *Breaks if:* someone
    changes the CAS to per-shot or makes a spent `Finalizer` opaque. Asserting only "both shots complete" would
    pass while the hazard is present, which is why the released-state assertion is in it (N10).
28. **zero-shot capture:** a clause dropping its continuation does not release. Named so it reads as a
    specification, not a bug.
29. **multi-shot above the acquire point:** a capture taken while `acquire` is still suspended acquires and
    releases once per shot. *Breaks if:* rule 1 is relaxed and the walk starts minting; also the pin that question
    5's first case really is per-shot (N9).
30. **twice is safe:** `finalizeResources` driven twice releases once.
31. **resume and finalize race:** resume on one thread and `finalizeResources` on another; the release count is
    exactly one, looped until deterministic per the repository's concurrency-reproduction rule.
32. **the acquiring drive never resumes:** park between `acquire` settling and `use` starting, abandoned. *Breaks
    if:* the `Defer.value` descent is missing. The one-loop-turn window B4 named.
33. **a park holding an unconsumed `Acquire` releases nothing**, and resuming it acquires and releases exactly
    once. *Breaks if:* the walk regains an `Acquire` arm. B1's and B2's pin, and the case revision 2 got backwards.
34. **ordering on abandonment:** nested brackets in a park release innermost first.
35. **park, resume, park again, abandon:** releases once.
36. **a throwing release during abandonment:** the rest still run and the failures aggregate onto the first.
37. **depth:** `finalizeResources` on a park taken over 100k trailing maps completes. *Breaks if:* the walk recurses
    instead of using a worklist, which is what revision 2 specified.

**Stack (`StackTest.scala`)**

38. `dump()` stops at a `Finalizer` the way it stops at a `Handler`: the finalizer is still on the stack
    afterwards. *Breaks if:* `boundary` still tests `Handler`.
39. whole-stack `dump(size)` followed by `push` restores the entries in order, **including a `Chain` entry both
    above and below a handler**, and including a `HandlerLoopState` at its live state. *Breaks if:* 5.7's `wrap`
    interaction does not hold; see the failure described there.
40. `clear()` on a fully dumped stack is a no-op.

Revision 2's `compact` tests go with `compact` (5.5), and N8 goes with them.

---

## 12. Rejected alternatives

**A drive-side registry of live finalizers.** The other answer to question 1: `Stack.put` appends every pushed
`Finalizer` to a side array `dump` does not consume, and `unwind` walks it. Rejected on three counts. It puts a
type test on the push path, which the settled path pays on every entry. It grows without bound in a drive that
enters many sequential brackets, so it needs a pruning policy the structural answer does not. And it does not solve
the park: the park value must own its finalizers, so `reify` would have to append the registry's live entries to
the folded arrow, where they would run at the end of the residual rather than at their place, working only because
the CAS makes the in-fold copies win the race.

**Keeping a discarded region's finalizers on the stack (`Stack.compact`).** Revision 2's shape, superseded by 5.5.

**Duplicating a folded `Finalizer` back onto the stack, relying on the CAS.** Tempting, since the CAS makes
duplication free for exactly-once. Wrong for *ordering*: the on-stack copy runs when the value below it settles,
which is before the continuation that still holds the resource runs. Exactly-once would hold and use-after-release
would be introduced everywhere instead of in the one documented case.

**Deferring the release behind `Effect.defer` instead of running it eagerly.** Revision 1's shape. It opens a
window between the CAS flip and the drive reaching the release node; a park landing in that window produces a value
whose `Finalizer` is gone from the stack and whose release has not run. Row `Any` is what makes the eager form
possible, which is the strongest argument for the owner's correction.

**A `Finalizer` the drive recognizes by class, with the release logic in the drive**, or **a `Finalize`
`ArrowEffect` handled at the boundary.** The first is not a complete value: the entry has to work when a `dump`
folded it into a continuation a clause replays in a foreign drive, and drive-resident logic cannot follow it there.
The second fails on the two paths that motivate the feature, since a `Loop.done` discards its handler with the
region and an abandoned park has no drive and therefore no handler at all. An effect can only be interpreted where
an interpreter is running, and the resource paths are exactly the paths where none is.

**An outcome parameter on `release`.** Rejected on the owner's directive and, independently, because kernel-level
outcome detection is position-dependent. The kernel has no `Abort`; a failure reaches the drive as a settled value
that happens to be a `Result.Error`, which the prior design read with a type test
(`3a95636fa8:KyoInternal.scala:33-36`). That works only when the error settles *through* the bracket. Put the
bracket inside `Abort.run`'s region and the abort is a suspension the region answers with `Loop.done`, which
discards the interior, and the release runs from the discard path with no value to inspect. Same program, two
positions, two answers; a parameter right for only one of them looks total and is not. So the kernel releases on
every path and tells the release nothing. Routing note for `kyo-core`, not a design: `Sync.ensure`'s outcome-aware
overload (`kyo-core/shared/src/main/scala/kyo/Sync.scala:107-115`, whose body is still the deleted
`Effect.bracket(())((_, outcome) => ...)` at `:115`) and `Scope.ensure`'s (`Scope.scala:66`) must capture the
outcome inside the `use` computation instead: wrap the use in `Abort.run`, thread the `Result` out, let the release
read it. Nothing here blocks that.

**A sweep at region completion, releasing anything a clause did not use**, or **changing the `handleCont` clause to
receive an `Arrow` instead of a `Function1`.** The sweep cannot distinguish a dropped continuation from one
legitimately captured to resume later, and would turn a leak into a use-after-free. The signature change is a
surface change the owner has not asked for, and it relocates the obligation onto every clause author rather than
discharging it.

**Stopping the LIFO chain at the first throwing release.** Leaks every outer resource because one inner release
failed. The other two throw policies rejected: a fresh composite exception loses the identity of the original
throw, which on the normal path is the user's; swallow-and-log needs a logger the kernel does not have, and `Scope`
above already logs (`Scope.scala:186`), which is the right layer for a policy decision.

**A region-exit crossing (`Kyo.exitStep`).** Not needed; see 2.1's second corollary.

---

## 13. What is not settled, and what to measure

**No number in this document is measured.** Every performance statement is a shape argument, which per the skill is
a hypothesis. The rows this change reaches, and therefore the rows that are mandatory:

- **The `stop ne null` poll at the top of `loop`** touches the loop head, which makes every row mandatory.
- **The region-continue change** (`map` to `Effect.defer` at `Eval.scala:96-97` and `:142-143`, three-arg `defer`
  at `:82` and `:125`) touches region rebuild and the `HandleLoopState` arms, making the emitting row and the
  stateful row mandatory. A small win is expected (one fewer node and one fewer anonymous class per region
  continue) and an expectation is not evidence.
- **The `Barrier` test in `dump()`'s `boundary`** replaces an `instanceof` against a `sealed abstract class` with
  one against a trait; "equal cost" is a hypothesis.
- **The unnest move** (6) removes a type test from the settled arm, the hottest arm in the drive.
- **`Finalize.discard` on the region-completion path** (5.5) adds a walk where there was none.
- **`Defer` plus `Acquire` against the node form**, if the owner keeps the node question open.
- **The nested-drive frequency in `Finalizer.run`** under a realistic `Sync.ensure` shape, which is what the
  concession table's second row claims is cold.

Per the skill: the whole benchmark class on both variants, same session, back to back, `-f 1` to screen and `-f 3`
to confirm anything outside the drift band, in a throwaway worktree.

**Two behaviors change that are not performance.** A spliced effect trace gains the interior's user frames at the
two region-continue sites (5.4), and a trace crossing a live bracket gains the use site and the release site as
frames (7). Both are improvements and both are user-visible.

**Out of scope.** `EvalTest.scala` carries a separate commented clause-scope group and `ArrowEffectTest.scala:1229`
a parked note about `handlePartial`; neither is unblocked by this change.
