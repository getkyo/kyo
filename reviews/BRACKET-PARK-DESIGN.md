# Bracket, Park, and finalizers on the Stack

Design document. No source was changed and nothing was run to produce it.

Read against worktree `.claude/worktrees/effervescent-painting-backus` at `0ef6d2726c` plus the in-flight
EffectTrace edits present in the working tree (`EffectTrace.scala` untracked, `Eval.scala` and `Stack.scala`
modified). Line citations are against that state; where the EffectTrace work moved a line I cite the working-tree
number, and I say so.

---

## 0. Summary of what this document concludes

Three claims, each argued below and each needing sign-off:

1. **Neither `Bracket` nor `Park` needs to be a new `Kyo` node kind.** `Kyo.Defer` already reifies both. A bracket
   is `Defer(acquire, acquireArrow, cont)`; a park is `Defer(residual, foldedStack)`. The whole feature lands as
   two new `Arrow` classes, one new `Stack` method, one new `Eval` entry point, and one extension method. The
   drive's `loop` gains no node arm.
2. **The finalizer is an ordinary `Stack` entry**, that is, an `Arrow` like every other entry. This is what makes a
   park carry its finalizers for free (`Stack.dump` folds them into the parked value), what gives LIFO ordering for
   free (stack order), and what makes a finalizer a complete value that survives capture into a foreign drive.
3. **The owner's `release: Arrow[A, Unit, S]` shape has a hole on the abnormal paths.** A release whose row `S` was
   answered by a handler *inside* the abandoned or discarded computation cannot be run, and cannot soundly be run
   under a rebuilt region either (argued in section 8.4). Two forks are presented; I recommend closing the release
   row at construction.

---

## 1. Problem statement, in the composition-first framing

The kernel has no way to say "this computation owns something that must be given back". Every existing node kind is
a reification of a combinator over *values*: `Defer` is `map`, `Suspend` is `ArrowEffect.suspend`, `Handle` is
`ArrowEffect.handle*`. Each one describes what happens when the computation *runs*. None of them describes what
happens when the computation *does not* run: when a throw unwinds the drive past it, when a `handleLoop` clause
answers `Loop.done` and discards the region it was inside, or when a scheduler drops a parked remainder it will
never resume.

That gap is exactly the set of paths a resource cares about, and it is why `Sync.ensure` in `kyo-core` is currently
written against a kernel primitive that no longer exists:

```scala
// kyo-core/shared/src/main/scala/kyo/Sync.scala:115
Effect.bracket(())((_, outcome) => Abort.run[Throwable](f(outcome).unit).map(_.getOrThrow))(_ => v)
```

`Effect.bracket` is gone from `kyo-kernel2` (`Effect.scala` today is 41 lines and has only `defer`). It existed at
`3a95636fa8`, was outcome-aware, and handled parks; it went away when the proto replaced the implementation at
`05cbc7eb12`. So this is not new surface, it is surface being re-derived on the new evaluator.

Partial evaluation is gone the same way. `Eval.partial` is referenced by live consumers that do not compile today:

- `kyo-kernel2/jvm/src/test/scala/kyo/kernel/internal/SafepointConcurrencyTest.scala:88`
- `kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/EvalTest.scala:206` and the group around it
- `kyo-kernel2/shared/src/test/scala/kyo/kernel/ArrowEffectTest.scala:446` and `:594` (commented, pointing at this
  document)

The two land together because partial handling is where the resource machinery is actually exercised: a park is the
only path on which the drive that acquired a resource ceases to exist while the resource is still held.

The composition-first question this document has to answer, for each piece, is the skill's question: *what equation
in the public combinators does the evaluator behavior read off?* Section 2 writes those equations before any
evaluator detail appears.

---

## 2. The equations

### 2.1 Bracket

The public surface:

```scala
object Effect:
    def bracket[A, B, S](acquire: A < S)(release: A => Unit < S)(use: A => B < S)(using Frame): B < S
```

Its normal-path equation, written only in combinators that exist today:

```scala
bracket(acquire)(release)(use)  ==  acquire.map(a => use(a).map(b => release(a).andThen(b)))
```

That equation is *complete* for the path where `use` runs to a value. Everything the node adds is the paths where
that `map` chain never gets applied:

```scala
// the three cases the equation cannot express, because each one is the ABSENCE of an application
throw inside use            -> the outer map is never applied
Loop.done discards the region containing the bracket -> the outer map is never applied
the drive parks and nobody resumes                   -> the outer map is never applied
```

`map` has no way to observe that its continuation will not be applied. That is the whole justification for a
primitive, and it is the only justification: the *positive* content of `bracket` is one `map` chain.

Two corollaries fall straight out of the equation and I use them later:

- `release` runs **after** `use` and **before** anything composed onto the bracket. In the equation this is the
  nesting of the two `map`s. In the evaluator it is stack order, with no extra mechanism.
- `bracket(acquire)(release)(use).map(f)` puts `f` outside the release, because `map` wraps. This is why the new
  shape does not need the `Kyo.exitStep` region-exit crossing that `3a95636fa8` had to invent
  (`KyoInternal.scala:133-164` at that commit): in that design `Bracket.map` extended the *use* chain, so an
  explicit crossing was needed to mark where the region ended. Here composition wraps the node rather than
  extending it, so the boundary is structural already. That is a real improvement over the prior art and it is
  worth keeping.

### 2.2 Park and partial evaluation

```scala
object Eval:
    private[kyo] def partial[A, S](v: A < S): A < S
    private[kyo] def partial[A, S](v: A < S, stop: () => Boolean): A < S
```

The equation is an *observational identity*, not a new combinator:

```scala
// for every A, S, and every context k that can consume an A < S
k(Eval.partial(v))  ==  k(v)
```

`partial` is partial evaluation: it computes as far as the handlers already present in `v` allow, and returns a
value observationally equal to `v`. Concretely, the two instances the tests pin:

```scala
Eval(answerAsk(41)(Eval.partial(ask.map(_ + 1))))  ==  Eval(answerAsk(41)(ask.map(_ + 1)))
Eval.partial(Eval.partial(v))                      ==  Eval.partial(v)      // up to further progress
```

This is the point on which the earlier attempt was withdrawn. `1cf05637e8` added a park at an unanswered operation
and `c2d5db5072` reverted it, with the reason recorded verbatim in the commit message:

> the Parked carrier was a control token in the drive's value channel, introduced without validation and without
> the equation for what a partial slice means at an operation no region answers.

Both objections are answerable now, and the answer is what makes this design different from that one:

- **The equation** is the identity above. An operation with no handler in `v` is precisely the boundary of what `v`
  can compute on its own, so stopping there is the *definition* of partial evaluation rather than a special case.
- **No carrier.** That attempt introduced a `final private class Parked(val v: Any)` (visible at
  `1cf05637e8:41`) and returned it through the drive's value channel. This design introduces nothing: the park
  value is `Effect.defer(residual, stack.dump(stack.size))`, an ordinary `Kyo.Defer` built from an ordinary folded
  `Arrow`. The drive's value channel carries what it always carried.

### 2.3 finalizeResources

```scala
extension [A, S](self: A < S)
    private[kyo] def finalizeResources: Unit < S
```

Equation: for a value `p` that a drive parked and nobody will resume,

```scala
// running the releases p holds is the same as resuming p in a context that immediately abandons it
p.finalizeResources  ==  the composition, innermost bracket first, of every release whose acquire completed
                         inside p and whose use has not yet delivered
```

and the idempotence law:

```scala
p.finalizeResources.andThen(p.finalizeResources)  ==  p.finalizeResources
Eval(p.finalizeResources); Eval(p)                ==  Eval(p) with every release already spent   // see 8.2
```

---

## 3. `Bracket`: the resolved shape

### 3.1 The type signature, resolved

The owner's shape as written does not type-check, because `Kyo` takes two parameters:

```scala
// KyoInternal.scala:11
sealed abstract class Kyo[+A, -S]
```

The correct reading is `extends Kyo[C, S]`, and the reason is that the node is structurally identical to `Defer`:

```scala
// KyoInternal.scala:15-19, today
abstract class Defer[A, B, +C, -S] extends Kyo[C, S]:
    def value: A < S
    def contA: Arrow[A, B, S]
    def contB: Arrow[B, C, S]
```

Line them up:

```scala
abstract class Bracket[A, B, +C, -S] extends Kyo[C, S]:   // C is the result, S is the row
    def acquire: A < S            //  <- Defer.value
    def use:     Arrow[A, B, S]   //  <- Defer.contA
    def release: Arrow[A, Unit, S]
    def cont:    Arrow[B, C, S]   //  <- Defer.contB
```

Note the variance annotations: `+C, -S`, matching `Defer` and `Handle` (`KyoInternal.scala:28`). Without them the
node cannot appear where an `A < S` at a wider row is expected, and every construction site pays a cast.

**Observation that follows from lining them up: `Bracket` is `Defer` with one extra field.** Section 3.4 takes that
observation to its conclusion.

### 3.2 `acquire: Kyo[A, S]` and the settled-acquire collapse

The owner directed `acquire: Kyo[A, S]` "mirroring `Kyo.Handle.value`". The mirror does not hold, and the reason
matters.

`Handle.value` is `Kyo[A, E & S]` because the settled case has a **total collapse that is a law**:

```scala
// ArrowEffect.scala:57-67
v match
    case body: Kyo[A, E & S] @unchecked => new Kyo.Handle[E, A, B, B, S]: ...
    case _                              => onDone(v.unsafeGet)     // handle(settled) == done(settled)
```

A settled body performs no operation, so no region is needed at all: the node is not merely skipped, it is
*meaningless*. `Kyo[A, E & S]` encodes that law in the type.

A settled acquire has no such law. `bracket(a)(release)(use)` with a settled `a` still has to register the release,
so the node still has to exist. Encoding `acquire: Kyo[A, S]` therefore encodes nothing; it only forces the
construction site to manufacture a `Kyo` it does not have.

**If the owner keeps `Kyo[A, S]`, the collapse is this and only this:**

```scala
def bracket[A, B, S](acquire: A < S)(release: A => Unit < S)(use: A => B < S)(using _frame: Frame): B < S =
    val acquireNode: Kyo[A, S] =
        acquire match
            case k: Kyo[A, S] @unchecked => k
            // a settled acquire is not representable as a node, so it is re-suspended behind
            // an identity Defer. The drive pops it in one turn and hands the value straight on
            case v => Effect.deferInline(v)
    new Kyo.Bracket[A, B, B, S]:
        def acquire = acquireNode
        def use     = Arrow(use)
        def release = Arrow(release)
        def cont    = Arrow.id[B]
```

`Effect.deferInline` (`Effect.scala:20-24`) builds `Kyo.Defer[A, A, A, S]` with both continuations `Arrow.id`, and
`Stack.push` drops `Arrow.Id` entries (`Stack.scala:34-36`), so the collapse costs one node allocation and one
`loop` turn, and nothing else.

**Recommendation (needs sign-off): `acquire: A < S`.** Same field type as `Defer.value`, no collapse, no
re-suspension. The argument is not that the collapse is expensive; it is that `Kyo[A, S]` on `Handle` states a law
and `Kyo[A, S]` on `Bracket` would state nothing, and a type that carries no claim is a type that misleads the next
reader into looking for the law. The `Sync.ensure` call shape (`Effect.bracket(())(...)`) makes the settled acquire
the common case, not the rare one, which is a second reason but not the load-bearing one.

### 3.3 Where each piece runs, read off the rows

Per the skill: signatures are semantics, and the row places the code.

| piece | type | where it runs | why the row says so |
|---|---|---|---|
| `acquire` | `A < S` | outside the region, in the caller's context | it is the node's `value` position, driven before anything is registered |
| `use` | `Arrow[A, B, S]` | inside the region, with the finalizer installed above it on the stack | it is `contA`: the drive applies it after `acquire` settles and after the register step |
| `release` | `Arrow[A, Unit, S]` | at the region boundary, with `S`'s handlers still installed and `use`'s frames gone | it sits at exactly the stack slot the bracket occupied |
| `cont` | `Arrow[B, C, S]` | outside the region, after `release` | it is `contB`, pushed *below* the finalizer, so the stack pops through the finalizer first |

The row `S` is the same on all four, and that is not an accident of notation: `release` and `cont` both name `S`
because both run at the bracket's own stack position, which is the position where `S`'s handlers are the ones the
caller installed. `use` names `S` because the region adds no effect of its own; a bracket is not a handler.

The one place the row lies is the abnormal paths, where `release` runs and its handlers may be gone. Section 8.4.

### 3.4 The reduction: `Bracket` is `Defer`

Take the alignment from 3.1 seriously. The drive's `Defer` arm is:

```scala
// Eval.scala:37-40 (working tree)
case kyo: Kyo.Defer[?, ?, A, S] @unchecked =>
    stack.push(kyo.contB)
    stack.push(kyo.contA)
    loop(kyo.value)
```

A `Bracket` arm would be, verbatim modulo names:

```scala
case kyo: Kyo.Bracket[?, ?, A, S] @unchecked =>
    stack.push(kyo.cont)          // == contB
    stack.push(register(kyo))     // == contA, but it also has to install the finalizer
    loop(kyo.acquire)             // == value
```

The only difference is that `contA` has to do something extra. And `contA` is an `Arrow`, so it can:

```scala
// the acquire arrow: the "register" half of a bracket, as a value
final private[kyo] class Acquire[A, B, S](
    val release: Arrow[A, Unit, S],
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
                // the finalizer goes onto the stack ABOVE next, so the pop order is
                // use, then release, then whatever the caller composed
                Effect.defer(use(a), new Finalizer(release, a).chain(next))
end Acquire
```

and `Effect.bracket` becomes a composition of two existing nodes:

```scala
def bracket[A, B, S](acquire: A < S)(release: A => Unit < S)(use: A => B < S)(using Frame): B < S =
    Effect.defer(acquire, new Acquire(Arrow(release), Arrow(use)), Arrow.id[B])
```

`Effect.defer(v, a, b)` is `Effect.scala:32-39` and already drops an identity `b`.

Trace the drive over it, using only arms that exist today:

```
curr = Defer(acquire, Acquire, id)
  Defer arm       -> push id (dropped, Stack.scala:35), push Acquire, loop(acquire)
curr = <acquire settles to a>
  settled arm     -> pop Acquire, apply it with stack.dump() as next   (Eval.scala:169-170 wt)
                  -> Effect.defer(use(a), Finalizer(a).chain(next))
curr = Defer(use(a), Finalizer.chain(next), id)
  Defer arm       -> push chain: Stack.push flattens it (Stack.scala:37-41) into
                     [Finalizer, ...next's entries], loop(use(a))
curr = <use settles to b>
  settled arm     -> pop Finalizer, apply it -> release runs, then b flows on
```

That is the entire feature on the normal path, with **no new node kind and no new drive arm**. The skill's rule is
that wanting a new node kind is the signal you are off the path; here the signal fired and the reduction was
available.

**Cost of the node version, for the record.** `loop`'s match is a chain of `instanceof` tests
(`Defer`, `Suspend`, `Handle`, then `case _`). A fourth `Kyo` subclass adds one test to the fall-through, which is
the *settled* arm, the hottest arm in the drive. That is a measurable cost on every settled delivery and it buys
nothing the arrow form does not already give.

**Recommendation (needs sign-off): drop the `Bracket` node; ship `Effect.bracket` as `Defer` + `Acquire`.**
Section 3.1 and 3.2 stand as the specification for the node form if the owner keeps it, and everything else in this
document works unchanged either way (the finalizer, the park, and `finalizeResources` do not care which one built
the `Finalizer` entry).

### 3.5 `Finalizer`, the entry

```scala
// the stack entry a bracket installs. It is an ordinary Arrow, which is what makes it survive
// a dump into a captured continuation, a park, and a replay in a foreign drive
final private[kyo] class Finalizer[A, B, S](val release: Arrow[A, Unit, S], val resource: A)
    extends java.util.concurrent.atomic.AtomicBoolean
    with Arrow.Transform[B, B, S]:

    def frame = release.frame

    def apply[C, S2](v: B < S2, next: Arrow[B, C, S2]): C < (S & S2) =
        if !compareAndSet(false, true) then
            // already released: this is a second shot of a captured continuation, or a race
            // with finalizeResources. The continuation still runs; only the release is spent
            next(v, Arrow.id)
        else
            Effect.defer(release(resource), new Resume(v).chain(next))
end Finalizer

// hands back a value the caller already holds, ignoring its input. The release's Unit is dropped here
final private[kyo] class Resume[B, S](val value: B < S) extends Arrow.Transform[Unit, B, S]:
    def frame                                                     = Frame.internal
    def apply[C, S2](v: Unit < S2, next: Arrow[B, C, S2]): C < (S & S2) = next(value, Arrow.id)
end Resume
```

Three properties to note, because each one is load-bearing:

1. **Self-contained.** `Finalizer.apply` implements the whole semantics. The drive needs no arm for it: the generic
   settled arm (`Eval.scala:169-170` working tree, `loop(head.asInstanceOf[Arrow[Any, ?, EX & S]](curr,
   stack.dump()))`) drives it. This is the "everything handed out is a complete value" rule from the skill: the same
   entry works when the drive pops it, when a `dump` folded it into a continuation a clause replays, and when a
   fresh drive on another thread pushes it back out of a park.
2. **Exactly once, by CAS.** `extends AtomicBoolean` costs no extra allocation, mirroring the CPS kernel's
   `Safepoint.Ensure` (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:142-155`), which is
   `sealed abstract class Ensure extends AtomicBoolean with Function1[...]` with
   `if compareAndSet(false, true) then ... run(v)` and the comment at `:174-175`, "ensures the function is called
   once even if an interceptor executes it multiple times". Same requirement here, same mechanism, and it is what
   makes multi-shot continuations and a `finalizeResources`/resume race safe.
3. **The second shot still runs the continuation.** `next(v, Arrow.id)` rather than a no-op, again matching the CPS
   kernel, where a spent `Ensure` is transparent.

`Resume` deserves a sentence: `value` arrives already union-represented (it is the `B < S2` the drive handed to
`Finalizer.apply`), so `next(value, Arrow.id)` moves it without lifting and without casting. That is deliberate and
it is what keeps this file out of the macro-suspension cascade. The prior art is `Finalize.constant` at
`3a95636fa8:kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Finalize.scala:51-55`, which is the same arrow.

---

## 4. `Park` and partial handling, end to end

### 4.1 `Park` does not need to exist either

A park is "the drive's remaining work, as a value". The drive already has the operation that turns its stack into a
value: `Stack.dump` (`Stack.scala:100-126` working tree) folds entries into one `Arrow`, chaining each entry onto
the one below and consuming the slots. Pair that arrow with the standing value and you have a `Kyo.Defer`:

```scala
// Eval.scala, private, cold, never on the hot path
private def reify(stack: Stack, curr: Any < Nothing): Any < Nothing =
    if stack.isEmpty then curr
    else Effect.defer(curr.asInstanceOf[Any < Any], stack.dump[Any, Any, Any](stack.size))
        .asInstanceOf[Any < Nothing]
```

Resumption is the drive's existing `Defer` arm: it pushes the folded chain, `Stack.push` flattens it back into
entries (`Stack.scala:37-41`), and each entry is what it was. Two details make that exact rather than approximate,
and both are already in `Stack`:

- **A region comes back as a region.** `Handler` extends `Arrow.Transform` (`Handler.scala:9`), so a handler is an
  ordinary entry, folds like one, and after the re-push `Stack.find` locates it again (`Stack.scala:89-98`).
- **A stateful region comes back at the state it reached.** `dump` swaps a `HandlerLoopState` for one carrying the
  live state before folding it (`Stack.scala:107-111`), and `put` re-seeds the state slot on the way back in
  (`Stack.scala:23-31`). This is exactly what the commented pin at `ArrowEffectTest.scala:594` ("a parked stateful
  region resumes with its state and done") asks for.

So the park value is a `Defer`, the resume is the `Defer` arm, and there is nothing left for a `Park` node to do.
This is not a new opinion: it is the ruling already recorded in this repository. Commit `d4e59ffa56`, subject line:

> [kyo-kernel2] proto: a captured continuation is a composition, no Park in any value

and in the body, "Kyo.Park, Stack.copy*/pushAll/regionAbove, Eval.fold/park/restore go." Reintroducing `Kyo.Park`
would relitigate that ruling, and the skill is explicit that an item conflicting with a recorded decision is
raised, not executed.

**The one thing a `Park` node would buy** is avoiding the fold-then-flatten round trip: a node holding a
`Span[Arrow[?, ?, ?]]` plus a `Span[Maybe[Any]]` could be pushed with a bulk copy instead of walking a chain. That
is a performance question, it is on a cold path (once per park, and a park costs a scheduler hop anyway), and per
the skill it needs a measurement before it can be an argument. It is not a semantic argument, and I found no
semantic argument for the node.

**Recommendation (needs sign-off): no `Park` node. The park value is `Effect.defer(residual, stack.dump(size))`.**

### 4.2 What triggers a park, and how the two triggers differ

The brief asks whether the trigger is the arm/stop protocol, budget exhaustion, or both. It is both, they are
different mechanisms, and only one of them parks the drive.

**Budget exhaustion never parks the drive.** `Safepoint.enter` is called from the *strict* arms of the composition
sites, not from the drive:

```scala
// Pending.scala:29-37, map's strict arm; Arrow.scala:41-49 and Handler.scala:15-23 are the same shape
val slot = Safepoint.get()
if !Safepoint.enter(slot) then
    Effect.defer(v, arrow, next)          // out of budget: build a node instead of recursing
else
    val out = next.head(f(v.unsafeGet), next.tail)
    Safepoint.exit(slot)
    out
```

When the budget is spent, the strict path stops fusing and produces a `Kyo.Defer` node. That is the trampoline, and
its purpose is Java stack safety. The drive then consumes the node and keeps going. `Eval.apply` has no poll of any
kind today (`Eval.scala:35-181` working tree), which is the direct evidence.

**The stop protocol parks, and only under `partial`.** `Safepoint.stop(thread)` CAS-es the thread's slot from
`Thread` to `Stop(thread)` (`Safepoint.scala:137-159`). The next `Safepoint.get()` then misses the fast path
(`slots.get(h) eq thread` is false, `:73`) and falls into `resolve`, where the arm bit is read:

```scala
// Safepoint.scala:98-103
val cached = local.get()
if cached ne null then
    val slot = cached.intValue()
    if depths(slot).isArmed && slots.get(slot).isInstanceOf[Stop] then
        depths(slot) = depths(slot).drained
    slot
```

So `arm` is the coupling between the two mechanisms, and it is one-directional: **arming makes a stop request drain
the budget**, which makes the strict arms stop fusing, which makes the computation reach the drive as nodes
promptly. The drive then observes the request through `consumeStopped` and parks. Without `arm` (that is, under
`Eval.apply`), a stop request drains nothing and the drive never looks, which is what
`SafepointConcurrencyTest.scala:206-252` pins: under `Eval`, `burn(Period * 4)` completes and returns 0 even with
the slot degraded.

Summarised:

| trigger | mechanism | effect on the drive | who sees it |
|---|---|---|---|
| budget exhausted | `Safepoint.enter` returns false in a strict arm | none; one more `Defer` node to consume | every drive, always |
| stop requested, slot armed | `Stop` in the slot, budget drained at `Safepoint.get()`, `consumeStopped` at the drive's poll | park: reify and return | `Eval.partial` only |
| operation with no handler on the stack | `Stack.find` returns -1 | park under `partial`, `bug` under `apply` | `Eval.partial` only |

### 4.3 `Eval.partial`

```scala
private[kyo] def partial[A, S](v: A < S): A < S = partial(v, neverStop)

private[kyo] def partial[A, S](v: A < S, stop: () => Boolean): A < S =
    val slot = Safepoint.get()
    // a stop delivered between slices short-circuits: the request is consumed here and the
    // input comes back by identity, so the caller can reschedule without any drive at all
    if Safepoint.consumeStopped(slot) then v
    else
        val saved = Safepoint.save(slot)
        Safepoint.arm(slot)
        val stack = Stack.borrow()
        try drive(stack, v.asInstanceOf[Any < Nothing], () => Safepoint.consumeStopped(slot) || stop())
            .asInstanceOf[A < S]
        finally
            Stack.release(stack)
            Safepoint.restore(slot, saved)
        end try
end partial
```

This is `1cf05637e8:24-36` with the stack borrow made explicit, and the identity short-circuit is what
`EvalTest.scala:259-261` pins (`assert(r.asInstanceOf[AnyRef] eq v.asInstanceOf[AnyRef])`).

The poll goes at the top of `drive`'s loop:

```scala
@tailrec def loop(curr: Any < Nothing): Any < Nothing =
    if (stop ne null) && stop() then reify(stack, curr)
    else curr match
        ...
```

`stop` is `null` for `Eval.apply`, so the whole feature is one reference comparison on the hot path, and `reify` is
a separate method so nothing cold lands inside `loop`'s bytecode budget.

**The unhandled-operation park needs one care.** The `Suspend` arm pushes `kyo.cont` *before* it searches
(`Eval.scala:42-43` working tree), so at the point where `find` returns -1 the continuation is already on the stack
and reifying `kyo` itself would duplicate it. The fix is to reify the *bare* operation:

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

The type parameters `IX, OX, EX, CX` are already bound by the arm's pattern (`Eval.scala:41`), so this is a
construction, not a cast. One allocation, on a cold path, once per park.

### 4.4 Park, resume, and the Stack pool

- **A park value holds no `Stack`.** It holds a `Kyo.Defer` whose continuation is a chain of `Arrow`s. The stack
  goes back to the per-thread pool in the drive's `finally` (`Stack.release`, `Stack.scala:199` working tree), which
  calls `clear()` (`:185`). `reify` consumed every slot via `dump(size)`, so `clear()` finds nothing.
- **Resume borrows a fresh stack**, possibly from a different thread's pool (`Stack.local` is a
  `ThreadLocal[Pool]`, `Stack.scala:193-197`). `clear()` resets `head` and `tail` to 0 (`:145-149`), so a recycled
  stack starts clean regardless of where it came from.
- **A resumed value is not consumed.** It is an ordinary value and can be driven any number of times, on any
  threads, concurrently. That is the "everything handed out is a complete value" rule and it is what the capture
  tests at `d4e59ffa56` (the "captured continuation is a value" group: resumed after the region completed, on other
  threads, under a later same-tag region) already established for the fold. The park value is built the same way,
  so the property transfers, but it needs its own pins (section 9).
- **Cross-thread visibility of the resource itself** is the publisher's business, not the kernel's. The park value's
  publication (a queue offer, a promise completion) supplies the happens-before; the kernel adds none.

---

## 5. Stack finalizers and `<.finalizeResources`

### 5.1 The finalizer is an entry, and the entries are the collection

The brief says "the `Stack` gains a collection of finalizers". The recommendation is that the collection is the
entry array it already has. Concretely, a `Finalizer` is pushed by `Acquire` like any other continuation, and:

| requirement | how the entry form satisfies it |
|---|---|
| LIFO across nested brackets | stack order, no code |
| a park carries the unrun finalizers | `dump` folds every entry, no code |
| a resume reinstalls them | `push` flattens the chain, no code |
| a capture into a clause carries them | `dump(pos)` folds the interior, no code |
| release before anything composed onto the bracket | the finalizer is pushed above `cont`, no code |

The alternative, a parallel `finalizers: Array[...]` beside `states`, buys the ability to find them without a scan
and costs: a third array in `ensure`/`pop`/`truncate`/`clear`, a second type test in `put` (which is on the push
path), and, critically, it still has to attach the finalizers to the folded arrow at `dump` time, because the
parked value has to own them. That is the same work plus bookkeeping. Rejected in section 10.

What `Stack` does gain is one method, for the two paths that discard entries rather than applying them:

```scala
/** Truncates `n` entries and returns the finalizers among them, innermost first, as one arrow.
  * `Arrow.id` when there are none, which is the ordinary case: applying it is the value itself.
  */
def detach(n: Int): Arrow[Any, Any, Any] =
    @tailrec def loop(i: Int, acc: Arrow[Any, Any, Any]): Arrow[Any, Any, Any] =
        if i == 0 || head == tail then acc
        else
            val idx = head & mask
            val e   = entries(idx)
            entries(idx) = null
            states(idx) = Absent
            head += 1
            loop(i - 1, if e.isInstanceOf[Finalizer[?, ?, ?]] then acc.chain(e.asInstanceOf[Arrow[Any, Any, Any]]) else acc)
    loop(n, Arrow.id)
end detach
```

This is `truncate` (`Stack.scala:135-143`) with one type test folded into the loop it already runs and the matches
accumulated. It is not on the hot path: it fires once per region completion, not once per operation.

### 5.2 Where `detach` is used

**Region discard (`Loop.done`).** Today, at `Eval.scala:100-101` and `:146-147` (working tree):

```scala
case _ =>
    stack.truncate(pos + 1)
    loop(o)
```

becomes:

```scala
case _ =>
    // the region's interior is discarded: anything it acquired is released before the
    // answer leaves. detach returns Arrow.id when the interior held nothing, and applying
    // the identity arrow is the value itself (Arrow.scala:100)
    loop(stack.detach(pos + 1)(o, Arrow.id))
```

One line for one line. `Arrow.Id.apply(v, next)` short-circuits when `next eq Id` (`Arrow.scala:101-105`), so the
common case is a comparison and a return. When there *are* finalizers, applying the chain to `o` runs each release
and then hands `o` on, because that is exactly what `Finalizer.apply` does. No new semantics were written; the
ordinary arrow application does it.

**A throw unwinding the drive.** `Eval.apply` today has a `finally` but no `catch` at the drive boundary
(`Eval.scala:173-180` working tree). It gains one:

```scala
try
    loop(v.asInstanceOf[Any < Nothing]).asInstanceOf[A]
catch
    case ex: Throwable =>
        // the stack still holds every finalizer whose use had not delivered. Release them
        // innermost first before Stack.release clears the slots, and never let a failing
        // release replace the original throw
        Finalize.unwind(stack, ex)
        throw ex
finally
    Stack.release(stack)
    Safepoint.restore(slot, saved)
```

Placing this at the boundary rather than per bracket is forced by the shape: `loop` is one flat `@tailrec` method,
so there is no per-bracket Java frame to hang a `try` on, which is what `3a95636fa8:Eval.scala:43-47` used. The
`catch` runs before the `finally`, which matters: `Stack.release` calls `clear()`, and clearing a stack that still
holds finalizers would drop them silently.

`Finalize.unwind` is section 5.4.

**A capture (`dump(pos)`).** Nothing to do. `dump` folds every entry in `[0, pos)` into the returned arrow
(`Stack.scala:102-126`); no branch drops one. The finalizer travels into the continuation the clause receives. What
that means for a clause that calls the continuation zero or many times is open question 5, section 8.5.

### 5.3 `<.finalizeResources`

```scala
// Pending.scala, inside the existing extension block. Deliberately NOT inline: it is a
// boundary entry point called from a scheduler, never a user-composition site, and an
// inline method here would expand the walk at every call site the way Eval.apply does
extension [A, S](self: A < S)
    private[kyo] def finalizeResources: Unit < S =
        Finalize.compose(self).asInstanceOf[Unit < S]
```

and the walk, in a new `kyo/kernel/internal/Finalize.scala`:

```scala
private[kyo] object Finalize:

    /** The releases the value still owes, innermost bracket first, as one computation.
      * `()` when it owes none, which is the ordinary case for a value that is not a park.
      */
    def compose(v: Any < Nothing): Unit < Any =
        val acc = walkValue(v, Arrow.id[Unit])
        if acc eq Arrow.Id then unit else Effect.defer(unit, acc)

    private def walkValue(v: Any < Nothing, acc: Arrow[Unit, Unit, Any]): Arrow[Unit, Unit, Any] =
        v match
            case k: Kyo.Defer[?, ?, ?, ?] =>
                // a park between acquire and use: the resource is the Defer's own value and
                // its Acquire arrow is the head of contA. See 8.5
                walkArrow(k.contB, walkArrow(k.contA, acc, k.value), k.value)
            case k: Kyo.Suspend[?, ?, ?, ?, ?, ?] => walkArrow(k.cont, acc, ())
            case k: Kyo.Handle[?, ?, ?, ?, ?]     => walkArrow(k.cont, walkValue(k.value, acc), ())
            case _                                => acc

    private def walkArrow(a: Arrow[?, ?, ?], acc: Arrow[Unit, Unit, Any], pending: Any): Arrow[Unit, Unit, Any] =
        a match
            case c: Arrow.Chain[?, ?, ?, ?] => walkArrow(c.b, walkArrow(c.a, acc, pending), ())
            case f: Finalizer[?, ?, ?]      => acc.chain(release(f))
            case q: Acquire[?, ?, ?]        => acc.chain(releaseOf(q, pending))
            case _                          => acc
```

Semantics, and the answers to the brief's question 2:

- **Return type `Unit < S`.** The releases are values, not actions the kernel performs, so the caller drives them
  where its handlers are. This is the composition-first choice: `finalizeResources` composes, it does not run.
  Section 8.4 is the caveat on what `S` can actually mean here.
- **Order is innermost first**, matching the normal path: `walkArrow` accumulates in traversal order, and the
  traversal reaches the innermost bracket first because the fold placed the innermost entry at the head of the
  chain (`dump` builds `entries(0).chain(entries(1).chain(...))`, `Stack.scala:118-123`).
- **Calling it twice is safe**, and what makes it safe is the `Finalizer`'s CAS, not the walk. The walk is pure: it
  composes arrows and mutates nothing. The second composition contains the same `Finalizer` instances, whose CAS
  has already flipped, so each `apply` takes the `next(v, Arrow.id)` branch and no release runs. The same mechanism
  covers the harder case: a park value that is *both* resumed on one thread and finalized on another. Exactly one of
  the two runs each release, and which one wins is the CAS.
- **`finalizeResources` on a value that is not a park** returns `()`. The walk finds no `Finalizer` and `compose`
  returns unit without allocating.

**Visibility (needs sign-off).** The brief calls this "the public extension on the pending type". The prior art was
`private[kyo] def finalizeBracket(outcome: Maybe[Result.Error[Any]]): Unit`
(`3a95636fa8:kyo-kernel2/shared/src/main/scala/kyo/kernel/Pending.scala:38`), with the comment "runtime machinery,
not user surface: runs the finalizers a parked computation's brackets carry. The scheduler calls it when dropping a
continuation that will never be resumed." I recommend keeping `private[kyo]` for the same reason: a user holding a
value has no way to know whether it is a park, and calling this on a live value spends its releases. If the owner
wants it public, it should carry a scaladoc that says exactly that.

### 5.4 The throw path

```scala
private[kyo] def unwind(stack: Stack, ex: Throwable): Unit =
    // detach(size) hands back every finalizer the stack still holds, innermost first. The
    // walk is structural, so the release row is existential here; running it through a bare
    // drive demands the closed row the bracket's construction guaranteed (see 8.4)
    val releases = stack.detach(stack.size)
    if !(releases eq Arrow.Id) then
        try discard(Eval(Effect.defer(unit, releases).asInstanceOf[Unit < Any]))
        catch case t: Throwable => ex.addSuppressed(t)
```

The `addSuppressed` shape is `3a95636fa8:Finalize.scala:75-81`. The `.eval`-with-cast and its justification are
`3a95636fa8:Finalize.scala:98-100`, quoted in the comment above because the constraint is identical.

`unwind` and `compose` are the same operation over two different sources (a live stack, a parked value), so they
share `Finalizer.apply` and differ only in how they get the list.

---

## 6. Every touched file, and whether it summons the lift

The macro-suspension rule: a file that summons `CanLift` (that is, writes a *bare value* into a `<`-typed position
and lets the implicit conversion fire) is suspended to a retry run, and new summons in core inlined-from files
deepen the cascade into the dotty crash. The check I applied to every line below is mechanical: **every
`<`-typed expression in the new code is produced by `Effect.defer`, by an `Arrow` application, or is a value the
drive already holds at `<` type. No bare value is written into a `<` position anywhere.**

| file | change | lift summon |
|---|---|---|
| `kyo/kernel/Effect.scala` | add `def bracket[A, B, S](acquire: A < S)(release: A => Unit < S)(use: A => B < S)(using Frame): B < S`, a non-inline `def` building `Effect.defer(acquire, new Acquire(Arrow(release), Arrow(use)), Arrow.id[B])`. `Arrow(f)` is the existing `Arrow.apply` (`Arrow.scala:33`), which is inline and takes `inline f: A => B < S`; `release` and `use` arrive already at `< S`, so no conversion fires | **no** |
| `kyo/kernel/internal/Finalize.scala` | new file: `Acquire`, `Finalizer`, `Resume`, `compose`, `unwind`, the two walks. Every `<` value is a parameter or an `Effect.defer` result | **no** |
| `kyo/kernel/internal/Eval.scala` | extract the loop into `private def drive(stack: Stack, v: Any < Nothing, stop: () => Boolean): Any < Nothing`; add `partial` (two overloads) and `reify`; add the poll at the top of `loop`; replace the two `truncate(pos + 1); loop(o)` pairs with `loop(stack.detach(pos + 1)(o, Arrow.id))`; add the boundary `catch` calling `Finalize.unwind`; add the unhandled-operation park in the `pos < 0` branch. All currency is `Any < Nothing` and moves by cast, never by conversion | **no new one.** The file already carries one (per the skill's inventory) and this change adds none |
| `kyo/kernel/internal/Stack.scala` | add `def detach(n: Int): Arrow[Any, Any, Any]`. No `<` value appears in `Stack` at all | **no** |
| `kyo/kernel/Pending.scala` | add `private[kyo] def finalizeResources: Unit < S` to the existing extension block, non-inline, body `Finalize.compose(self).asInstanceOf[Unit < S]` | **no** |
| `kyo/kernel/internal/KyoInternal.scala` | **unchanged** under the recommendation. Under the node fork, add `Bracket` | n/a |

Two file-level notes that are design constraints rather than changes:

- **`Eval.apply` is `inline`** (`Eval.scala:32`), and the in-flight EffectTrace work records why that is
  load-bearing: "The class and its two entry points are public because `Eval.apply` is `inline`: its body is
  re-typechecked at every expansion site" (`EffectTrace.scala:35-37`). Everything the drive references therefore has
  to be reachable from every user expansion site of `<.eval`. `Finalize` must be `private[kyo]` with public members
  under the same rule EffectTrace follows, or the drive must stop being inline. **Extracting `drive` into a
  non-inline private method is the change I recommend anyway** (it also stops the 150-line loop from expanding at
  every `.eval` site), but it touches the drive's shape and the skill requires the full benchmark class on both
  variants before it lands. Flagged as work, not as a decision.
- **`Finalize.compose` and `finalizeResources` are non-inline on purpose**, so no walk expands at a call site and
  the lift cannot be summoned at a user site by them.

---

## 7. Casts introduced, each categorized

Against the skill's closed set. Nothing here needs a new category, so nothing here needs cast sign-off, but the
enumeration is the obligation.

| # | site | cast | category |
|---|---|---|---|
| 1 | `Eval.reify` | `curr.asInstanceOf[Any < Any]` and the result back to `Any < Nothing` | erasure-forced. Identical in kind to `Eval.scala:176` (`v.asInstanceOf[Any < Nothing]`): the drive's currency is the top of the row lattice and every constructor names a concrete row |
| 2 | `Eval.partial` | `v.asInstanceOf[Any < Nothing]` in, `.asInstanceOf[A < S]` out | erasure-forced. Byte-identical to the prior implementation at `1cf05637e8:33` |
| 3 | `Stack.detach` | `e.asInstanceOf[Arrow[Any, Any, Any]]` | erasure-forced, array element re-typing at the storage boundary. The same cast appears three times in `Stack` today (`:82`, `:87`, `:125`) |
| 4 | `Finalize.unwind` | `Effect.defer(unit, releases).asInstanceOf[Unit < Any]` | erasure-forced. The walk is structural so the release row is existential; the cast asserts the closed row the bracket's construction guaranteed. Verbatim the prior art's one cast (`3a95636fa8:Finalize.scala:100`) and its comment |
| 5 | `<.finalizeResources` | `Finalize.compose(self).asInstanceOf[Unit < S]` | erasure-forced, same claim as #4 seen from the caller |

Casts I checked for and found unnecessary, recorded so a later reader does not add them back:

- `Resume.apply` needs none. `next(value, Arrow.id)` types by variance: `<` is contravariant in `S`, so `B < S`
  conforms to `B < (S & S2)`, and `Arrow` likewise.
- The bare-operation rebuild in the unhandled-operation park needs none. `IX, OX, EX, CX` are bound by the `Suspend`
  arm's pattern at `Eval.scala:41`, so the new node is a construction at known types.
- `Finalizer.apply` needs none. Its `release` and `resource` are its own fields at its own type parameters.
- `Acquire.apply` needs none. `v.unsafeGet` is the existing settled reader (`Pending.scala:289-292`).

---

## 8. The six open questions

Each carries a recommendation. **All six need sign-off.**

### 8.1 Does `release` run on abort and on a thrown exception, and should it see the outcome?

**What the shape can express.** `Arrow[A, Unit, S]` sees the resource and nothing else, so as written the release is
outcome-blind. That is a coherent position, and under it the answer to "does it run on abort" is: the kernel does not
know what an abort is. `Abort` is an `ArrowEffect` handled above the kernel; a failed computation arrives at the
drive as an ordinary settled value that happens to be a `Result.Error`. The kernel would release on completion
(whatever the value), on a throw, on region discard, and on abandonment, and would never distinguish them.

**What the consumers need.** The one real consumer needs the outcome:

```scala
// kyo-core/shared/src/main/scala/kyo/Sync.scala:107-115
inline def ensure[A, S](f: Maybe[Error[Any]] => Any < (Sync & Abort[Throwable]))(v: => A < S)(using inline frame: Frame): A < (Sync & S) =
    // the kernel bracket owns the exactly-once guarantee and the outcome: Absent on
    // success, the error when the computation aborts or throws, and the boundary's own
    // error when a parked remainder is discarded
    Effect.bracket(())((_, outcome) => Abort.run[Throwable](f(outcome).unit).map(_.getOrThrow))(_ => v)
```

and `Scope.ensure` above it takes `Maybe[Error[Any]] => Any < (Async & Abort[Throwable])`
(`kyo-core/shared/src/main/scala/kyo/Scope.scala:66`). The prior kernel2 bracket supplied it
(`3a95636fa8:KyoInternal.scala:105`, `def release(r: R, outcome: Maybe[Result.Error[Any]]): Unit < S`) and read it
off the settled value with a documented type test:

```scala
// 3a95636fa8:KyoInternal.scala:33-36
private[kyo] def outcomeOf(v: Any): Maybe[Result.Error[Any]] =
    unnest(v) match
        case e: Result.Error[Any] @unchecked => Maybe(e)
        case _                               => Maybe.Absent
```

`kyo-kernel2` depends on `kyo-data` (`build.sbt:772-775`), so `Result` and `Maybe` are both available.

**Recommendation: add the outcome, as a method rather than an arrow.**

```scala
abstract class Bracket[A, B, +C, -S] extends Kyo[C, S]:
    def acquire: A < S
    def use: Arrow[A, B, S]
    def release(resource: A, outcome: Maybe[Result.Error[Any]]): Unit < S
    def cont: Arrow[B, C, S]
```

or, under the recommended arrow form, `Acquire` and `Finalizer` carry a
`(A, Maybe[Result.Error[Any]]) => Unit < S` instead of an `Arrow[A, Unit, S]`. The outcome the kernel supplies is:
`Absent` on normal completion; `Present(Panic(t))` on a throw; `Present(e)` when the settled value is a
`Result.Error`, which is the currency an error handler settles with; and the caller's argument on
`finalizeResources`, which then takes a parameter.

**Why this is the right fork despite being a surface change.** Without the outcome, `Sync.ensure`'s outcome-aware
overload has no kernel support and has to be rebuilt on a parallel mechanism above the kernel, which is the exact
duplication the kernel bracket exists to prevent. The counter-argument, that outcome-awareness is a `Result`-level
concern and `Result` is not the kernel's vocabulary, is real: `outcomeOf` above is the kernel pattern-matching a
`kyo-data` type it does not own. The prior kernel2 accepted that with the comment at
`3a95636fa8:KyoInternal.scala:30-32` and shipped it green.

**If the owner keeps `Arrow[A, Unit, S]`:** the release runs on all four paths, blind, and `Sync.ensure` loses its
outcome overload. That is a coherent design, it is smaller, and it is the owner's call; it just has to be a call.

### 8.2 `finalizeResources`: exact signature and semantics

**Recommendation:**

```scala
private[kyo] def finalizeResources(outcome: Maybe[Result.Error[Any]]): Unit < S     // with 8.1 adopted
private[kyo] def finalizeResources: Unit < S                                        // without
```

- **`Unit < S`, not `Unit`.** Returning a value keeps the kernel out of the business of running user effects and
  lets the caller drive the releases where its own handlers are. Returning `Unit` would force the kernel into the
  bare-drive-plus-cast of section 5.4 on the abandonment path too, which is exactly the path where the caller most
  plausibly does have handlers.
- **Safe twice, and safe against a concurrent resume.** The safety is the `Finalizer`'s CAS
  (`compareAndSet(false, true)`), not the walk. The walk composes and mutates nothing, so composing it twice is
  free; the second composition holds the same `Finalizer` instances, already flipped, so every `apply` takes the
  pass-through branch. Same for a race between `Eval(p.finalizeResources)` on one thread and `Eval(p)` on another:
  each release runs exactly once and the CAS decides which side runs it.
- **Order:** innermost bracket first, matching the normal path and matching what the prior art pinned
  ("innermost-first finalization of a discarded parked remainder", `3a95636fa8` commit body).
- **On a value that is not a park:** `()`, no allocation.

The residual risk this signature carries is section 8.4's: `S` may name effects whose handlers lived inside the
abandoned computation.

### 8.3 Resume then park again, and resume on a foreign thread

**Park, resume, park again.** The second park re-folds the stack, and the `Finalizer` entry that came in from the
first park's chain goes back out in the second park's chain. It is the same object throughout, so its CAS is the
same CAS, and the exactly-once guarantee spans any number of park/resume cycles. The state a stateful region carries
across the cycles is `Stack`'s existing `dump`/`put` pair (`Stack.scala:107-111` and `:23-31`), unchanged by any of
this.

**Resume on a different thread.** Three things are per-thread and all three are handled:

- The `Stack` pool (`Stack.local`, `Stack.scala:193-197`). The park value holds no stack, so the resuming thread
  borrows its own, cleared.
- The `Safepoint` slot. `partial` calls `Safepoint.get()` on the resuming thread and arms *that* slot; the arming
  from the previous slice was undone by `Safepoint.restore` in that slice's `finally`.
- The `Frame`/trace machinery, which is already per-drive.

**What the kernel does not promise.** Memory visibility of the resource object itself. If thread A acquires and
thread B resumes, the happens-before comes from however the park value was published (a queue, a promise), not from
the kernel. The `Finalizer`'s `AtomicBoolean` gives a happens-before for the *release decision* but not for the
resource's own fields. This is the same contract the CPS kernel has and it should be stated in the scaladoc.

**A resume that is not the only resume.** A park value is a complete value, so it can be driven twice, and both
drives run `use`'s remainder against the same resource. Exactly one of them releases (whichever reaches the
`Finalizer` first); the other passes through. That is the CPS kernel's behavior for a multi-shot `Ensure` and I
recommend matching it, because the alternative (release per shot) would need a re-acquire the kernel cannot
perform. Pinned in section 9.

### 8.4 A finalizer that suspends, aborts, or throws

Three sub-cases, and they are not equally hard.

**Throws.** On the normal path, the throw propagates out of the drive and `Finalize.unwind` catches it at the
boundary, running the *outer* finalizers and suppressing any secondary throw into the primary
(`ex.addSuppressed(t)`). On the unwind path itself, a throwing release is suppressed into the original exception
and the unwind continues to the next finalizer. This is `3a95636fa8:Finalize.scala:75-81` and `:102-106`.

**Aborts.** The kernel has no `Abort`. A release that aborts settles to a `Result.Error` value, which `Resume`
passes to `next` like any other value, so the error flows into whatever handler is installed. Under 8.1 that same
value is also what a later bracket reads as its outcome. Nothing special is needed.

**Suspends: this is the hard one, and it is where the owner's shape has a hole.**

On the **normal path** a suspending release is fine. The release runs at the bracket's own stack slot, so every
handler for `S` that the caller installed is still on the stack below it, and `Stack.find` answers the operation
normally. The `Finalizer` is an ordinary entry and `Effect.defer(release(a), Resume(v).chain(next))` is an ordinary
node; a suspension inside it goes through the ordinary dispatch. Nothing to design.

On the **abnormal paths** it is not fine, and the reason is structural rather than incidental:

- `unwind` runs after the stack has been detached, so the handlers are gone.
- `finalizeResources` runs on a value nobody is driving, so there are no handlers at all.
- Region discard runs after the region has been truncated, so *that* region's handler is gone.

The obvious repair, rebuilding the regions found in the fold around each release, **does not work, and it is worth
recording why so nobody tries it.** To install handler `h` around a `Unit`-returning release you would build
`Kyo.Handle(value = releases, handler = h, cont = Arrow.id)`. But the handler's clause is typed at the *region's*
result type, not `Unit`:

```scala
// Handler.scala:29-36
def run[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)                    // HandlerCont
def run[X](input: I[X]): Outcome[O[X] < (E & S), B] < S                            // HandlerLoop
def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B] < S      // HandlerLoopState
```

A `HandlerCont` clause that answers without resuming produces an `A`; a `HandlerLoop` clause that answers
`Loop.done(b)` produces a `B`. Both would land in a `Unit` slot. The region cannot be reinstalled around a different
body, and no amount of care fixes that: it is the clause's type, not the plumbing's.

So the honest statement is: **on the abnormal paths, a release can only perform effects that a bare drive can
run**, that is, `Effect.defer`-shaped effects with no handler requirement.

**Fork, needing sign-off:**

- **Fork A (recommended): close the release row at construction.**
  `def bracket[A, B, S](acquire: A < S)(release: A => Unit < Any)(use: A => B < S): B < S`. Every path then runs the
  same code and is total; `finalizeResources` returns `Unit < Any`; `unwind` needs no cast (cast #4 disappears);
  the "a release that suspends on a discarded region" failure mode cannot be written. The real consumer already
  satisfies it: `Sync.ensure` handles its `Abort` inline (`Abort.run[Throwable](...)`, `Sync.scala:115`) and `Sync`
  itself is `Effect.defer`-shaped, so its release's residual row is already `Any` in the sense that matters. The
  cost is that a release cannot read an ambient context effect, which is a live concern only when `ContextEffect`
  returns to this kernel (it was removed at `27b0740af7` and has no replacement today).
- **Fork B (the owner's shape): keep `Arrow[A, Unit, S]`.** Correct on the normal path, and on the abnormal paths a
  release that actually suspends on an effect whose handler is gone reaches `bug("unhandled suspension")`, exactly
  as any other out-of-scope operation does. That is a defensible report, it just has to be a documented one, and it
  needs cast #4 plus its comment.

### 8.5 Interaction with `Stack.dump`

**The positive half is already true.** `dump(pos)` folds *every* entry in `[0, pos)` into the returned arrow and
nulls the slots (`Stack.scala:102-126`); there is no branch that drops an entry. A `Finalizer` between the operation
and its region's handler therefore travels into the continuation the clause receives, and the continuation is a
complete value: applying it runs `use`'s remainder, then the release, then whatever was below. Nothing to add.

Two details that are easy to break and should be pinned rather than reasoned about:

- **Entry boundaries must survive the round trip.** `dump`'s `wrap` handling (`Stack.scala:114-121`) exists so that
  an entry that is itself an `Arrow.Chain` is not re-flattened into two entries when the fold is pushed back
  (`Stack.push`'s `count`/`fill`, `:47-64`, descend into chains). The park's whole-stack fold uses `wrap = true`
  (the public `dump(pos)` at `:100`), which is the right one; the no-argument `dump()` uses `wrap = false` and is a
  different operation with a different consumer. Pin the round trip, do not reason about it.
- **`dump` must be the only way a finalizer leaves the stack alive.** The other two exits, `truncate` and `clear`,
  destroy entries. `detach` (section 5.1) replaces `truncate` at the two sites that can see a finalizer, and the
  boundary `catch` runs before `Stack.release`. Those are the only three sites; the invariant is checkable by
  grepping for `truncate` and `clear`.

**The negative half is real and cannot be closed at the kernel.** A `handleCont` clause receives the continuation as
a plain function:

```scala
// Handler.scala:29
def run[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)
// built by the drive at Eval.scala:53-55 (working tree)
val k = stack.dump[OX[CX], AX, EX & S](pos)
val next = h.run(kyo.input, k(_))
```

- **Called once:** correct, the release runs at its place in the continuation.
- **Called many times:** the release runs on the first shot and is transparent on the rest, by the CAS. This is the
  behavior, and it is a choice (section 8.3).
- **Called zero times:** the resource is never released. The clause dropped the only reference, and it dropped it as
  a `Function1`, so it could not have called `finalizeResources` on it even if it wanted to.

That last case is inherent to a first-class continuation and I do not recommend trying to close it in the kernel.
Two things I explicitly reject as closures for it, with reasons, in section 10: a sweep at region completion (it
would release resources a legitimately-captured continuation still needs), and changing the clause signature to
hand over an `Arrow` instead of a function (a surface change the owner has not asked for, and it only moves the
obligation onto the clause author). The correct place for a lifetime that must not depend on continuation usage is
the layer above, which is exactly what `Scope` is: its finalizers live in a queue owned by `Scope.run`
(`Scope.scala:157-191`), not in the continuation.

### 8.6 Interaction with regions

`stack.truncate(pos + 1)` fires when a `handleLoop` or `handleLoopState` clause answers `Loop.done`
(`Eval.scala:100-101` and `:146-147`, working tree). It drops the handler at `pos` and the whole interior `[0, pos)`,
which is where a bracket acquired inside the region has its `Finalizer`.

**Recommendation: the discarded interior's finalizers run, and they run after the region is dropped.** The one-line
change is in section 5.2. Ordering is innermost first, then the region's answer flows on:

```scala
case _ =>
    loop(stack.detach(pos + 1)(o, Arrow.id))
```

**The consequence, stated plainly:** a release whose row names the discarded region's own effect will not find its
handler and reaches `bug("unhandled suspension")`. Under Fork A of 8.4 that is unwritable. Under Fork B it is
writable and it fails.

**The alternative I rejected** is running the releases with the region still installed. It cannot be spelled without
either an arrow that mutates a specific `Stack` (which would not be a complete value, so it would break the moment
it were captured) or a region rebuild, which section 8.4 shows is ill-typed. I would rather report a scoping error
than build either.

**Second-order case worth naming:** the interior may itself contain a nested region with its own brackets. `detach`
walks the whole range in stack order, so nested finalizers come out innermost first regardless of how many region
boundaries they cross. The handler entries in the range are simply discarded, which is what `truncate` did.

---

## 9. Pinning tests

Grouped by piece. The first three groups are the skill's named hostile axes; the fourth is what resource release
adds. Placement follows the module's rule that a test file shares a prefix with its source: `Eval.partial` and the
park go in `EvalTest.scala`, the stack behavior in `StackTest.scala`, `Effect.bracket` in `EffectTest.scala`, the
walk in a new `FinalizeTest.scala`, and the region interactions in `ArrowEffectTest.scala`.

### Bracket, normal path (`EffectTest.scala`)

1. acquire, use, release run in that order, each exactly once, on a settled acquire
2. same on a pending acquire (acquire suspends on an operation a region answers)
3. release runs before anything composed onto the bracket: `bracket(...).map(f)` records release before `f`
4. release runs before an *effectful* downstream, under a `handleCont` region and under a `handleLoopState` region
   (two cases: the state the handler reached at the boundary is the state the downstream sees)
5. nested brackets release innermost first
6. an effectful release runs its effects under the handlers installed around the bracket
7. a bracket whose `use` settles immediately still releases

### Bracket, abnormal paths (`EffectTest.scala`)

8. a throw inside `use` releases, then propagates; the original exception is the one that escapes
9. a throw inside `use` with two nested brackets releases both, innermost first
10. a throw inside a *release* is suppressed into the primary exception and the outer release still runs
11. a `Loop.done` that discards a region containing a bracket releases it, and the answer still flows
12. same with the bracket nested two regions deep
13. a bracket whose `use` settles to a `Result.Error` releases (and, under 8.1, sees the error as its outcome)

### Park and partial (`EvalTest.scala`, restoring the group at `:193-262` plus new cases)

14. a preemption stop reifies mid-computation and the resumed value completes with the handler state intact
    (the existing `"a preemption stop reifies and resumes with handler state"`)
15. the `stop` function ends the slice (existing)
16. `partial` completes when nothing stops (existing)
17. an unhandled operation parks for a handler installed later (existing), and the operation is not re-stacked:
    assert the resumed computation performs the operation exactly once
18. an unhandled operation parks with the regions above it intact (existing)
19. a stop delivered between slices short-circuits by identity (existing)
20. **park in a foreign drive:** a parked value evaluated on a different thread completes
21. **replay:** a parked value evaluated twice produces the same answer both times (both drives independent)
22. **double nesting:** a park inside a park (partial over a value that is already a park) resumes correctly
23. **budget park mid-path:** `SafepointConcurrencyTest.scala:77-102` unchanged, which is the live consumer
24. `Eval.apply` still reports `bug("unhandled suspension")`; parking is `partial`-only
25. a park whose stack is empty returns the residual by identity (no `Defer` wrapper allocated)

### Resources across parks and captures (`FinalizeTest.scala`)

26. **release exactly once:** a value parked mid-`use` and then resumed releases once, not twice
27. **release on abandonment:** the same value, not resumed, releases when `finalizeResources` is driven
28. **twice is safe:** `finalizeResources` driven twice releases once
29. **resume and finalize race:** resume on one thread, `finalizeResources` on another; the release count is
    exactly one (loop the scenario until deterministic, per the repository's concurrency-reproduction rule)
30. **the acquiring drive never resumes:** park between acquire settling and `use` starting; `finalizeResources`
    still releases (this is the `Acquire`-in-the-walk case of section 5.3, and it is the case a naive walk misses)
31. **ordering on abandonment:** nested brackets in a park release innermost first
32. **park, resume, park again, abandon:** releases once
33. **multi-shot capture:** a `handleCont` clause that calls its continuation twice releases once, and both shots
    run to completion
34. **zero-shot capture:** a `handleCont` clause that drops its continuation does not release; asserted as the
    documented limitation, with the test named so it reads as a specification and not as a bug

### Stack (`StackTest.scala`)

35. `detach(n)` returns `Arrow.id` when the range holds no finalizer, and truncates exactly `n`
36. `detach(n)` returns the finalizers in the range, innermost first, and truncates exactly `n`
37. whole-stack `dump(size)` followed by `push` restores the entries in order, including a chain entry that must
    not be re-flattened, and including a `HandlerLoopState` at its live state (this is the round trip section 8.5
    says to pin rather than reason about)
38. `clear()` on a stack that was fully dumped is a no-op (nothing left to drop)

---

## 10. Rejected alternatives

**`Bracket` as a `Kyo` node kind.** Rejected because `Kyo.Defer` already reifies "value, then contA, then contB",
which is exactly "acquire, then register-and-use, then continue" (section 3.4). Adding the node adds one
`instanceof` to the drive's fall-through, which is the settled arm and the hottest arm there is, and it buys nothing
the `Acquire` arrow does not. Specified in full in 3.1 to 3.3 in case the owner keeps it.

**`Park` as a `Kyo` node kind.** Rejected on the recorded ruling at `d4e59ffa56`, "a captured continuation is a
composition, no Park in any value", which deleted `Kyo.Park`, `Stack.copy*`, `Stack.pushAll`, `Stack.regionAbove`,
and `Eval.fold/park/restore` in one change. The one thing the node would buy is a bulk push instead of a
fold-and-flatten, which is a cold-path performance question with no measurement behind it.

**A `Parked` carrier in the drive's value channel.** This is the `1cf05637e8` design, `final private class
Parked(val v: Any)`, withdrawn at `c2d5db5072` as "a control token in the drive's value channel, introduced without
validation and without the equation". Section 2.2 supplies the equation; the carrier stays deleted, because
`Effect.defer(residual, fold)` needs no carrier.

**Finalizers as a parallel `Stack` array beside `states`.** Rejected because the parked value must own the
finalizers, so `dump` would have to attach them to the folded arrow anyway; the array is then a second copy that
`ensure`, `pop`, `truncate`, and `clear` all have to maintain, plus a second type test in `put`, which is on the
push path. The entry array already is the collection.

**A `Finalizer` the drive recognizes by class, with the release logic in the drive.** Rejected because it would not
be a complete value: the same entry has to work when a `dump` folds it into a continuation a clause replays in a
foreign drive, and drive-resident logic cannot follow it there. Keeping the semantics inside `Finalizer.apply` also
keeps `Eval$::loop` unchanged, which section 6 needs.

**A region-exit crossing (`Kyo.exitStep`), as in `3a95636fa8`.** Not needed here. That design needed an explicit
crossing because `Bracket.map` extended the *use* chain (`KyoInternal.scala:118-127` at that commit), so the region
boundary was not visible in the structure. In this kernel, `map` wraps rather than fuses (`Pending.scala:27-28`), so
composition lands below the bracket on the stack and runs after the release by stack order. The commit body records
that two designs which encoded the boundary per composition site both failed; this design does not have to encode it
at all.

**Running a discarded region's finalizers under a rebuilt region.** Rejected as ill-typed, argued in 8.4: a
`HandlerCont` clause answers at the region's `A` and a `HandlerLoop` clause answers at the region's `B`, so neither
can be installed over a `Unit`-returning release. This is a type-level obstruction, not a plumbing difficulty.

**A `Finalize` `ArrowEffect` handled at the boundary instead of a stack entry.** Rejected because it fails on the
two paths that motivate the whole feature: a `Loop.done` that discards a region discards its handler with it, and an
abandoned park has no drive and therefore no handler at all. An effect can only be interpreted where an interpreter
is running, and the resource paths are exactly the paths where none is.

**A sweep at region completion, releasing anything a clause did not use.** Rejected because it cannot distinguish a
clause that dropped a continuation from a clause that legitimately captured one to resume later. The capture case is
already pinned in this repository (the "captured continuation is a value" group at `d4e59ffa56`: resumed after the
region completed, on other threads, under a later same-tag region). Releasing under it would turn a leak into a
use-after-free, which is worse.

**Changing the `handleCont` clause to receive an `Arrow` instead of a `Function1`, so a discarding clause could
finalize.** Not recommended. It is a surface change the owner has not asked for, the skill forbids inventing surface
silently, and it only relocates the obligation onto every clause author rather than discharging it.

---

## 11. What I could not settle, and what a reader should check first

- **The whole document assumes `Eval.apply` stops being `inline`, or that `Finalize` follows the `EffectTrace`
  visibility pattern.** `EffectTrace.scala:35-37` (working tree) records the constraint explicitly. Extracting
  `drive` is the change I would make, and per the skill it needs the full benchmark class on both variants before
  it can land. That measurement is not in this document.
- **No number in this document is measured.** Every performance statement is a shape argument (bytecode not added
  to `loop`, one reference comparison on the poll, cold paths kept out of line). The claims that need numbers are:
  the poll's cost on the hot rows, the `detach` type test on the region-completion rows, and the `Defer`-plus-arrow
  bracket against the node form.
- **The working tree is moving.** `Eval.scala`, `Stack.scala`, and the tests were being edited by the EffectTrace
  work while this was written. The new boundary `catch` in section 5.2 has to compose with the per-arm `catch`es
  that work added (`Eval.scala:44-49`, `:56-59`, `:64-67` working tree); they attach trace information and rethrow,
  so the boundary `catch` sees the same exception and the composition is fine, but that is a reading of code that
  was still changing.
