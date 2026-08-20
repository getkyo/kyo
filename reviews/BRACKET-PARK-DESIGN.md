# Bracket, Park, and finalizers on the Stack

Design document. No source was changed and nothing was run to produce it.

Read against worktree `.claude/worktrees/effervescent-painting-backus` at `0ef6d2726c` plus the in-flight
EffectTrace edits present in the working tree (`EffectTrace.scala` untracked, `Eval.scala` and `Stack.scala`
modified). Line citations are against that state.

Revision 2. The owner's correction to the `Bracket` shape is binding and is incorporated throughout:

```scala
abstract class Bracket[A, B, C, S] extends Kyo[C, S]:
    def acquire: Kyo[A, S]
    def use:     Arrow[A, B, S]
    def release: Arrow[A, Unit, Any]
    def cont:    Arrow[B, C, S]
```

`extends Kyo[C, S]` is settled: the node's result is `C`, its row is `S`. The load-bearing change is
`release: Arrow[A, Unit, Any]`, and it turns out to buy more than the abandonment path: section 5.3 shows it is
what lets a release run **synchronously at the point the drive reaches it**, which closes an exactly-once window
that revision 1 of this document had open.

---

## 0. What this document concludes

1. **Neither `Bracket` nor `Park` needs to be a new `Kyo` node kind.** `Kyo.Defer` already reifies both. A bracket
   is `Defer(acquire, acquireArrow, cont)`; a park is `Defer(residual, foldedStack)`. The drive's `loop` gains no
   node arm. Both node forms are specified in full in case the owner keeps them, and nothing else in the design
   depends on which form ships.
2. **A finalizer is an ordinary `Stack` entry**, an `Arrow` like every other entry, and additionally a **stack
   barrier** in the sense `Handler` already is. Entry-ness gives LIFO ordering, park-carries-finalizers, and
   capture-carries-finalizers for free. Barrier-ness is what keeps a not-yet-run finalizer findable on the stack
   when something throws.
3. **`release: Arrow[A, Unit, Any]` is a type-level protection and it earns that name.** It makes the release
   total: runnable with no drive, no handlers, and no row obligations. Section 5.3 takes that further than the
   correction states, and makes the release run eagerly, which is what makes exactly-once structural instead of a
   discipline.
4. **The one genuinely open behavior left is what a throwing finalizer does to the rest of the LIFO chain.**
   Answer, in section 8.4: every finalizer still runs, the first failure propagates, later failures are suppressed
   onto it, and a finalizer that threw is not retried.
5. **One hard requirement falls out of the macro-suspension rule:** `Eval.apply` is `inline`, so `Finalize.scala`
   cannot call it. The drive has to be extracted into a non-inline `Eval.drive`. That is a prerequisite of this
   design, not a nice-to-have (section 6).

Decisions needing sign-off are marked inline and listed in section 8.

---

## 1. Problem statement, in the composition-first framing

The kernel has no way to say "this computation owns something that must be given back". Every existing node kind
reifies a combinator over *values*: `Defer` is `map`, `Suspend` is `ArrowEffect.suspend`, `Handle` is
`ArrowEffect.handle*`. Each describes what happens when a computation *runs*. None describes what happens when it
*does not* run: when a throw unwinds the drive past it, when a `handleLoop` clause answers `Loop.done` and discards
the region it was inside, or when a scheduler drops a parked remainder it will never resume.

Those are exactly the paths a resource cares about, and it is why `Sync.ensure` in `kyo-core` is written against a
kernel primitive that no longer exists:

```scala
// kyo-core/shared/src/main/scala/kyo/Sync.scala:115
Effect.bracket(())((_, outcome) => Abort.run[Throwable](f(outcome).unit).map(_.getOrThrow))(_ => v)
```

`Effect.bracket` is gone from `kyo-kernel2` (`Effect.scala` today is 41 lines and carries only `defer`). It existed
at `3a95636fa8`, and it went away when the proto replaced the implementation at `05cbc7eb12`. This is not new
surface; it is surface being re-derived on the new evaluator.

Partial evaluation went the same way. `Eval.partial` has live consumers that do not compile today:

- `kyo-kernel2/jvm/src/test/scala/kyo/kernel/internal/SafepointConcurrencyTest.scala:88`
- `kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/EvalTest.scala:206` and the group around it
- `kyo-kernel2/shared/src/test/scala/kyo/kernel/ArrowEffectTest.scala:446` and `:594`, commented out and pointing
  at this document

The two land together because partial handling is where the resource machinery is actually exercised: a park is the
only path on which the drive that acquired a resource ceases to exist while the resource is still held.

---

## 2. The equations

### 2.1 Bracket

```scala
object Effect:
    def bracket[A, B, S](acquire: A < S)(release: A => Unit < Any)(use: A => B < S)(using Frame): B < S
```

Normal-path equation, in combinators that exist today:

```scala
bracket(acquire)(release)(use)  ==  acquire.map(a => use(a).map(b => release(a).andThen(b)))
```

That equation is *complete* for the path where `use` runs to a value. Everything the primitive adds is the paths
where the outer `map` is never applied:

```scala
throw inside use                                       -> the outer map is never applied
Loop.done discards the region containing the bracket   -> the outer map is never applied
the drive parks and nobody resumes                     -> the outer map is never applied
```

`map` cannot observe that its continuation will not be applied. That is the entire justification for a primitive,
and it is the only one: the positive content of `bracket` is one `map` chain.

Two corollaries used later:

- `release` runs **after** `use` and **before** anything composed onto the bracket. In the equation that is the
  nesting of the two `map`s; in the evaluator it is stack order, with no extra mechanism.
- `bracket(...)(...)(...).map(f)` puts `f` outside the release, because `map` wraps rather than fuses
  (`Pending.scala:27-28`). This is why the new shape does not need the `Kyo.exitStep` region-exit crossing that
  `3a95636fa8` had to invent (`KyoInternal.scala:133-164` at that commit). In that design `Bracket.map` extended
  the *use* chain, so the region boundary was invisible in the structure and a crossing had to mark it. Here the
  boundary is structural already. The commit body records that two designs encoding the boundary per composition
  site both failed; this shape does not have to encode it at all.

### 2.2 Park and partial evaluation

```scala
object Eval:
    private[kyo] def partial[A, S](v: A < S): A < S
    private[kyo] def partial[A, S](v: A < S, stop: () => Boolean): A < S
```

The equation is an observational identity, not a new combinator:

```scala
// for every A, S, and every context k that can consume an A < S
k(Eval.partial(v))  ==  k(v)
```

`partial` is partial evaluation: it computes as far as the handlers already present in `v` allow, and returns a
value observationally equal to `v`. The two instances the tests pin:

```scala
Eval(answerAsk(41)(Eval.partial(ask.map(_ + 1))))  ==  Eval(answerAsk(41)(ask.map(_ + 1)))
Eval.partial(Eval.partial(v))                      ==  Eval.partial(v)      // up to further progress
```

An earlier attempt at this was withdrawn, and the reason is recorded verbatim at `c2d5db5072`:

> the Parked carrier was a control token in the drive's value channel, introduced without validation and without
> the equation for what a partial slice means at an operation no region answers.

Both objections are answerable, and the answers are what make this design different:

- **The equation** is the identity above. An operation with no handler in `v` is precisely the boundary of what `v`
  can compute on its own, so stopping there is the *definition* of partial evaluation rather than a special case.
- **No carrier.** That attempt introduced `final private class Parked(val v: Any)` (`1cf05637e8:41`) and returned it
  through the drive's value channel. This design introduces nothing: the park value is
  `Effect.defer(residual, stack.dump(stack.size))`, an ordinary `Kyo.Defer` over an ordinary folded `Arrow`.

### 2.3 finalizeResources

```scala
extension [A, S](self: A < S)
    private[kyo] def finalizeResources: Unit < Any
```

```scala
// running the releases p still owes is the same as resuming p in a context that immediately abandons it
p.finalizeResources  ==  the composition, innermost bracket first, of every release whose acquire completed
                         inside p and whose use has not yet delivered

// and it is idempotent, and it commutes with a resume in the sense that exactly one of them releases
p.finalizeResources.andThen(p.finalizeResources)  ==  p.finalizeResources
```

The row is `Any` because the release's row is `Any`. That is not a convenience, it is the point of the correction:
the composition is total, so this equation holds with no side condition about which handlers happen to be around.

---

## 3. `Bracket`: the resolved shape

### 3.1 Signature

`extends Kyo[C, S]`, per the owner. The variance annotations have to match the siblings or every construction site
pays a cast:

```scala
abstract class Bracket[A, B, +C, -S] extends Kyo[C, S]:
    def acquire: Kyo[A, S]
    def use:     Arrow[A, B, S]
    def release: Arrow[A, Unit, Any]
    def cont:    Arrow[B, C, S]
```

`Defer` and `Handle` are `[A, B, +C, -S]` and `[E, A, B, +C, -S]` respectively (`KyoInternal.scala:15`, `:28`).

### 3.2 Where each piece runs, read off the rows

Signatures are semantics, and the rows place the code.

| piece | type | where it runs | why the row says so |
|---|---|---|---|
| `acquire` | `Kyo[A, S]` | outside the region, in the caller's context | the node's value position; driven before anything is registered |
| `use` | `Arrow[A, B, S]` | inside the region, with the finalizer installed above it on the stack | `S` is the caller's row: a bracket adds no effect of its own, it is not a handler |
| `release` | `Arrow[A, Unit, Any]` | anywhere, including with no drive and no handlers | row `Any` is the claim that it needs nothing installed |
| `cont` | `Arrow[B, C, S]` | outside the region, after `release` | pushed *below* the finalizer, so the stack pops through the finalizer first |

`release` is the only one whose row differs from the node's, and the difference is the whole design. Read as
geography: `use` runs *inside* something and therefore inherits the caller's row; `release` may have to run when
there is no inside left, so it may not name a row at all.

The type system enforces this rather than documenting it. `<` is contravariant in `S`, so `Unit < Any <: Unit < Sync`
but not the reverse: a release written as `a => Sync.defer(close(a))` does not conform to `A => Unit < Any` and does
not compile. Conversely `Arrow[A, Unit, Any] <: Arrow[A, Unit, S]` for every `S`, so the narrower row costs nothing
at any use site. That pair is what makes "the parked value is the only owner of these resources" a compiler-checked
claim instead of an evaluator discipline, and it is recorded as such in the concession table (section 8.7).

### 3.3 The settled-acquire collapse

`acquire: Kyo[A, S]` means a settled acquire is not representable, so `Effect.bracket` collapses it at
construction:

```scala
def bracket[A, B, S](acquire: A < S)(release: A => Unit < Any)(use: A => B < S)(using _frame: Frame): B < S =
    val acquireNode: Kyo[A, S] =
        acquire match
            case k: Kyo[A, S] @unchecked => k
            // a settled acquire is not representable as a node, so it is re-suspended behind an
            // identity Defer: both continuations are Arrow.id, which Stack.push drops, so the
            // drive pops it in one turn and hands the value straight on
            case v => Effect.deferInline(v)
    new Kyo.Bracket[A, B, B, S]:
        def acquire = acquireNode
        def use     = Arrow(use)
        def release = Arrow(release)
        def cont    = Arrow.id[B]
```

`Effect.deferInline` is `Effect.scala:20-24` and builds `Kyo.Defer[A, A, A, S]` with both continuations `Arrow.id`;
`Stack.push` drops `Arrow.Id` entries (`Stack.scala:34-36`). So the collapse costs one node allocation and one
`loop` turn.

The collapse is unconditional: a settled acquire still has to register the release, so unlike `Handle`'s settled
arm there is no case where the node can be skipped. `Handle.value` is `Kyo[A, E & S]` because
`handle(settled) == done(settled)` is a law and `ArrowEffect.scala:67` takes it (`case _ => onDone(v.unsafeGet)`);
`Bracket` has no such law. Recording that difference because a reader who sees the two field types matched will go
looking for the matching law and will not find one.

### 3.4 The reduction: `Bracket` is `Defer`

Line the two node shapes up:

```scala
// KyoInternal.scala:15-19
abstract class Defer[A, B, +C, -S] extends Kyo[C, S]:
    def value: A < S              //  <- acquire
    def contA: Arrow[A, B, S]     //  <- use, plus the registration
    def contB: Arrow[B, C, S]     //  <- cont
```

The drive's `Defer` arm is:

```scala
// Eval.scala:37-40 (working tree)
case kyo: Kyo.Defer[?, ?, A, S] @unchecked =>
    stack.push(kyo.contB)
    stack.push(kyo.contA)
    loop(kyo.value)
```

A `Bracket` arm would be that, verbatim, except that `contA` also has to install the finalizer. And `contA` is an
`Arrow`, so it can:

```scala
// the acquire arrow: the "register" half of a bracket, as a value
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
                // the finalizer goes onto the stack ABOVE next, so the pop order is
                // use, then release, then whatever the caller composed
                Effect.defer(use(a), new Finalizer(release, a).chain(next))
end Acquire
```

and the primitive is a composition of two existing nodes:

```scala
def bracket[A, B, S](acquire: A < S)(release: A => Unit < Any)(use: A => B < S)(using Frame): B < S =
    Effect.defer(acquire, new Acquire(Arrow(release), Arrow(use)), Arrow.id[B])
```

`Effect.defer(v, a, b)` is `Effect.scala:32-39` and already drops an identity `b`.

Traced over arms that exist today:

```
curr = Defer(acquire, Acquire, id)
  Defer arm     -> push id (dropped, Stack.scala:35), push Acquire, loop(acquire)
curr = <acquire settles to a>
  settled arm   -> pop Acquire, apply it with stack.dump() as next   (Eval.scala:187-195 wt)
                -> Effect.defer(use(a), Finalizer(a).chain(next))
curr = Defer(use(a), Finalizer.chain(next), id)
  Defer arm     -> push chain; Stack.push flattens it (Stack.scala:37-41) into
                   [Finalizer, ...next's entries]; loop(use(a))
curr = <use settles to b>
  settled arm   -> pop Finalizer, apply it: the release runs, then b flows on
```

The whole normal path, with **no new node kind and no new drive arm**. The skill's rule is that wanting a new node
kind is the signal you are off the path; the signal fired and the reduction was available.

**Cost of the node form, for the record.** `loop`'s node match is a chain of `instanceof` tests
(`Defer`, `Suspend`, `Handle`, then `case _`). A fourth `Kyo` subclass adds one test to the fall-through, which is
the *settled* arm, the hottest arm in the drive.

**Recommendation (needs sign-off): drop the `Bracket` node; ship `Effect.bracket` as `Defer` plus `Acquire`.**
Sections 3.1 to 3.3 stand as the specification if the owner keeps the node, and everything downstream is unchanged
either way: the finalizer, the park, and `finalizeResources` do not care which form built the `Finalizer` entry.

---

## 4. `Park` and partial handling, end to end

### 4.1 `Park` does not need to exist either

A park is "the drive's remaining work, as a value". The drive already has the operation that turns its stack into a
value: `Stack.dump` (`Stack.scala:100-126`) folds entries into one `Arrow`, chaining each onto the one below and
consuming the slots. Pair that arrow with the standing value and you have a `Kyo.Defer`:

```scala
// Eval.scala, private, cold, never inlined into loop
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
  live state before folding (`Stack.scala:107-111`), and `put` re-seeds the state slot on the way back in
  (`Stack.scala:23-31`). This is exactly what the commented pin at `ArrowEffectTest.scala:594` asks for.

So the park value is a `Defer`, the resume is the `Defer` arm, and there is nothing left for a `Park` node to do.
That is also the ruling already on record. Commit `d4e59ffa56`, subject:

> [kyo-kernel2] proto: a captured continuation is a composition, no Park in any value

and in the body, "Kyo.Park, Stack.copy*/pushAll/regionAbove, Eval.fold/park/restore go." Reintroducing `Kyo.Park`
relitigates that ruling, and the skill is explicit that an item conflicting with a recorded decision is raised, not
executed.

**What a `Park` node would buy** is avoiding the fold-then-flatten round trip: a node holding a
`Span[Arrow[?, ?, ?]]` plus a `Span[Maybe[Any]]` could be pushed with a bulk copy instead of walking a chain. That
is a cold-path performance question (once per park, and a park costs a scheduler hop anyway), and per the skill it
needs a measurement before it can be an argument. I found no semantic argument for the node.

**Recommendation (needs sign-off): no `Park` node.**

### 4.2 What triggers a park, and how the triggers differ

Both mechanisms exist, they are different, and only one of them parks the drive.

**Budget exhaustion never parks the drive.** `Safepoint.enter` is called from the *strict* arms of the composition
sites, not from the drive:

```scala
// Pending.scala:29-37, map's strict arm. Arrow.scala:41-49 and Handler.scala:15-23 are the same shape
val slot = Safepoint.get()
if !Safepoint.enter(slot) then
    Effect.defer(v, arrow, next)          // out of budget: build a node instead of recursing
else
    val out = next.head(f(v.unsafeGet), next.tail)
    Safepoint.exit(slot)
    out
```

When the budget is spent, the strict path stops fusing and produces a `Kyo.Defer`. That is the trampoline and its
purpose is Java stack safety. The drive consumes the node and keeps going. `Eval.apply` has no poll of any kind
today (`Eval.scala:35-199` working tree), which is the direct evidence.

**The stop protocol parks, and only under `partial`.** `Safepoint.stop(thread)` CAS-es the slot from `Thread` to
`Stop(thread)` (`Safepoint.scala:137-159`). The next `Safepoint.get()` then misses the fast path
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

`arm` is the coupling, and it is one-directional: **arming makes a stop request drain the budget**, which makes the
strict arms stop fusing, which makes the computation reach the drive as nodes promptly; the drive then observes the
request through `consumeStopped` and parks. Without `arm` (that is, under `Eval.apply`) a stop drains nothing and
the drive never looks, which is what `SafepointConcurrencyTest.scala:206-252` pins: under `Eval`,
`burn(Period * 4)` completes and returns 0 even with the slot degraded.

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

This is `1cf05637e8:24-36` with the stack borrow made explicit and the boundary catch added. The identity
short-circuit is what `EvalTest.scala:259-261` pins (`assert(r.asInstanceOf[AnyRef] eq v.asInstanceOf[AnyRef])`).

The poll goes at the top of `drive`'s loop:

```scala
@tailrec def loop(curr: Any < Nothing): Any < Nothing =
    if (stop ne null) && stop() then reify(stack, curr)
    else curr match
        ...
```

`stop` is `null` for `Eval.apply`, so the whole feature is one reference comparison on the hot path, and `reify`
is a separate method so nothing cold lands inside `loop`'s bytecode budget.

**The unhandled-operation park needs one care.** The `Suspend` arm pushes `kyo.cont` *before* it searches
(`Eval.scala:42-43`), so at the point `find` returns -1 the continuation is already on the stack and reifying `kyo`
itself would stack it twice. Reify the *bare* operation instead:

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

`IX, OX, EX, CX` are bound by the arm's pattern (`Eval.scala:41`), so this is a construction, not a cast. One
allocation, cold, once per park.

### 4.4 Park, resume, and the Stack pool

- **A park value holds no `Stack`.** It holds a `Kyo.Defer` whose continuation is a chain of `Arrow`s. The stack
  returns to the per-thread pool in the drive's `finally` (`Stack.release`, `Stack.scala:199`), which calls
  `clear()` (`:145`). `reify` consumed every slot via `dump(size)`, so `clear()` finds nothing.
- **Resume borrows a fresh stack**, possibly from another thread's pool (`Stack.local` is a `ThreadLocal[Pool]`,
  `Stack.scala:193-197`). `clear()` resets `head` and `tail` to 0 (`:145-149`), so a recycled stack starts clean
  regardless of provenance.
- **A resumed value is not consumed.** It is a complete value and can be driven any number of times, on any
  threads, concurrently. That is the property the "captured continuation is a value" group at `d4e59ffa56` already
  established for the fold; the park value is built the same way, so the property transfers, but it needs its own
  pins (section 9).
- **Cross-thread visibility of the resource itself** is the publisher's business. The park value's publication (a
  queue offer, a promise completion) supplies the happens-before; the kernel adds none.

---

## 5. Stack finalizers and `<.finalizeResources`

### 5.1 The finalizer is an entry, and the entries are the collection

The brief says "the `Stack` gains a collection of finalizers". The recommendation is that the collection is the
entry array it already has. A `Finalizer` is pushed by `Acquire` like any other continuation, and:

| requirement | how the entry form satisfies it |
|---|---|
| LIFO across nested brackets | stack order, no code |
| a park carries the unrun finalizers | `dump` folds every entry, no code |
| a resume reinstalls them | `push` flattens the chain, no code |
| a capture into a clause carries them | `dump(pos)` folds the interior, no code |
| release before anything composed onto the bracket | the finalizer is pushed above `cont`, no code |

The alternative, a parallel `finalizers` array beside `states`, buys the ability to find them without a scan and
costs a third array in `ensure`/`pop`/`truncate`/`clear`, a second type test in `put` (which is on the push path),
and it *still* has to attach the finalizers to the folded arrow at `dump` time because the parked value has to own
them. Rejected in section 10.

### 5.2 A finalizer is also a barrier

`Stack.dump()` (the no-argument form, `Stack.scala:128-133`) folds a bounded run of entries and hands it to an
arrow as its continuation:

```scala
def dump[A, B, S](): Arrow[A, B, S] =
    @tailrec def boundary(i: Int): Int =
        if i == size || i == reach || entries((head + i) & mask).isInstanceOf[Handler[?, ?, ?, ?]] then i
        else boundary(i + 1)
    dump[A, B, S](boundary(0), false)
```

If a `Finalizer` is inside that run, it leaves the stack and lives inside a value in flight. Should the arrow that
received it then throw, the finalizer is unreachable: the boundary `catch` looks at the stack, and it is not there
any more. So the fold has to stop at a finalizer for the same reason it stops at a handler.

The change is to name the thing both are:

```scala
// Arrow.scala or Stack.scala, whichever the owner prefers as the home
private[kyo] sealed trait Barrier          // Handler and Finalize.Finalizer are the only implementors
```

and `boundary` tests `isInstanceOf[Barrier]` instead of `isInstanceOf[Handler[?, ?, ?, ?]]`. Same one type test per
entry, same loop, so the settled path's shape is unchanged; `Stack.find` keeps testing `Handler`, because a
finalizer answers no tag.

The *other* `dump` (`dump(pos)`, `Stack.scala:100`, the capture path) must keep folding finalizers in, and does:
that is how a captured continuation carries the resources its remainder still owes.

### 5.3 `Finalizer`, and why row `Any` makes it eager

```scala
final private[kyo] class Finalizer[A, B](val release: Arrow[A, Unit, Any], val resource: A)
    extends java.util.concurrent.atomic.AtomicBoolean
    with Arrow.Transform[B, B, Any]
    with Barrier:

    def frame = release.frame

    /** Runs the release exactly once, to completion, and reports whether this call ran it.
      *
      * The row is Any, so the release needs no handler and no drive of the caller's: an ordinary
      * release body has already run by the time `release(resource)` returns, and one built out of
      * Effect.defer is a node a bare drive settles. Running it here rather than deferring it is
      * what makes exactly-once structural: the entry never leaves the stack with an unrun release
      * behind it, so there is no window in which the guard has been spent and the work has not
      * happened.
      */
    def run(): Boolean =
        if !compareAndSet(false, true) then false
        else
            release(resource) match
                case k: Kyo[?, ?] => discard(Eval.drive(k))   // cold: the release built a node
                case _            => ()                       // the ordinary case: the body already ran
            true

    def apply[C, S2](v: B < S2, next: Arrow[B, C, S2]): C < S2 =
        discard(run())
        next(v, Arrow.id)
end Finalizer
```

Four properties, each load-bearing:

1. **Self-contained.** `apply` implements the whole semantics, so the drive needs no arm for it: the generic settled
   arm drives it (`Eval.scala:187-195`, `head.asInstanceOf[Arrow[Any, ?, EX & S]](curr, tail)`). This is the
   "everything handed out is a complete value" rule: the same entry works when the drive pops it, when a `dump`
   folded it into a continuation a clause replays, and when a fresh drive on another thread pushes it back out of a
   park.
2. **Exactly once, by CAS, with no window.** `extends AtomicBoolean` costs no extra allocation, mirroring the CPS
   kernel's `Safepoint.Ensure` (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:142-155`,
   `sealed abstract class Ensure extends AtomicBoolean with Function1[...]`, with the comment at `:174-175`
   "ensures the function is called once even if an interceptor executes it multiple times"). Because `run()` both
   flips the flag and completes the work, there is no state in which the flag is spent and the release has not
   happened. **Revision 1 of this document had exactly that window**: it deferred the release behind
   `Effect.defer`, so a park landing between the flip and the drive reaching the node would have produced a value
   whose `Finalizer` was gone and whose release had not run, leaking on abandonment. Row `Any` is what removes it,
   because only a total release can be run at the moment of the flip.
3. **The nested drive is cold.** `release(resource)` is `Arrow.apply(v: A): Unit < Any`, and for the `Arrow(f)`
   form (`Arrow.scala:33-49`) that calls `f(resource)` directly, so a plain side-effecting release has already run
   and returns `()`, which the `case _` arm takes with no drive at all. `Eval.drive` is entered only for a release
   built out of `Effect.defer`.
4. **The second shot still runs the continuation.** `next(v, Arrow.id)` unconditionally, not a no-op, again matching
   the CPS kernel, where a spent `Ensure` is transparent.

The `Resume` arrow that revision 1 needed (an arrow handing back a value after the release's `Unit`) is gone: with
an eager release there is no `Unit` to step over.

### 5.4 Region discard: `compact`, not `detach`

`stack.truncate(pos + 1)` fires when a `handleLoop` or `handleLoopState` clause answers `Loop.done`
(`Eval.scala:99-101` and `:146-148`). It drops the handler at `pos` and the whole interior `[0, pos)`, which is
where a bracket acquired inside the region has its `Finalizer`.

The interior is being abandoned, so its resources must be released, not dropped. The change keeps the survivors
**on the stack**:

```scala
/** Drops `n` entries, keeping the finalizers among them, in the same relative order, at the top.
  * A kept entry runs when the value below it settles, which is what releases what a discarded
  * region acquired; keeping them on the stack rather than folding them into an arrow is what
  * leaves the still-unrun ones findable if one of them throws.
  */
def compact(n: Int): Unit =
    var w = head + n
    var i = n - 1
    while i >= 0 do
        val e = entries((head + i) & mask)
        if e.isInstanceOf[Finalize.Finalizer[?, ?]] then
            w -= 1
            entries(w & mask) = e
            states(w & mask) = Absent
        i -= 1
    end while
    var j = head
    while j < w do
        entries(j & mask) = null
        states(j & mask) = Absent
        j += 1
    end while
    head = w
end compact
```

The write cursor is never below the read cursor: after processing indices `n-1 .. i` with `k` survivors,
`w == head + n - k` and `k <= n - i`, so `w >= head + i + 1` and the write at `w - 1` lands at or above the slot
just read. When every entry survives, each writes to its own slot.

The drive's two sites become:

```scala
case _ =>
    stack.compact(pos + 1)
    loop(o)
```

One line for one line, and the pop order does the rest: the settled arm pops each `Finalizer` in turn, innermost
first, each running its release before `o` continues outward. No new semantics were written; ordinary entry
application does it.

**Consequence, stated plainly:** the releases of a discarded region run after the region's handler is gone. With row
`Any` that is no longer a hazard, because the release could not have named the region's effect in the first place.
This is the clearest thing the correction bought: in revision 1 this was an open failure mode.

### 5.5 The throw path

The in-flight EffectTrace work already put a `catch` at the drive boundary (`Eval.scala:201-214`). It gains one
line:

```scala
try
    loop(v.asInstanceOf[Any < Nothing]).asInstanceOf[A]
catch
    case ex: Throwable =>
        // the stack still holds every finalizer whose use had not delivered. Release them
        // innermost first, before Stack.release clears the slots, and never let a failing
        // release replace the original throw. This runs before splice, so a suppressed
        // release failure is in place when the trace is written
        Finalize.unwind(stack, ex)
        EffectTrace.splice(ex)
        throw ex
finally
    Stack.release(stack)
    Safepoint.restore(slot, saved)
```

```scala
// Finalize.scala
private[kyo] def unwind(stack: Stack, primary: Throwable): Unit =
    var i = 0
    val n = stack.size
    while i < n do
        stack.entry(i) match
            case f: Finalizer[?, ?] =>
                try discard(f.run())
                catch case t: Throwable => primary.addSuppressed(t)
            case _ => ()
        i += 1
    end while
end unwind
```

`Stack.entry(i)` is the indexed read the EffectTrace work just added (`Stack.scala:87`, "an indexed read of a slot
without knowing its kind"), so this needs no new `Stack` surface at all.

The boundary is the right home rather than a per-bracket `try`, because `loop` is one flat `@tailrec` method and
there is no per-bracket Java frame to hang a `try` on. That is what `3a95636fa8:Eval.scala:43-47` used and it does
not exist here. The `catch` runs before the `finally`, which matters: `Stack.release` calls `clear()`, and clearing
a stack that still holds finalizers would drop them silently.

`Eval.partial` carries the same `catch`, shown in 4.3.

### 5.6 `<.finalizeResources`

```scala
// Pending.scala, inside the existing extension block. Deliberately NOT inline: this is a boundary
// entry point a scheduler calls, never a user-composition site
extension [A, S](self: A < S)
    private[kyo] def finalizeResources: Unit < Any = Finalize.compose(self)
```

`A < S` conforms to `Any < Nothing` by variance (`<` is covariant in `A` and contravariant in `S`), so the call
needs no cast.

```scala
// Finalize.scala
/** The releases the value still owes, innermost bracket first. `()` when it owes none, which is
  * the ordinary case for a value that is not a park.
  */
private[kyo] def compose(v: Any < Nothing): Unit < Any =
    val fs = collect(v, Chunk.empty)
    if fs.isEmpty then () else Effect.defer(runAll(fs))

/** Runs every finalizer, so one failure cannot strand the outer resources. The first failure is
  * the one that propagates and the rest are suppressed onto it, matching the drive's unwind.
  */
private def runAll(fs: Chunk[Finalizer[?, ?]]): Unit < Any =
    var primary: Throwable = null
    fs.foreach { f =>
        try discard(f.run())
        catch
            case t: Throwable =>
                if primary eq null then primary = t else primary.addSuppressed(t)
    }
    if primary ne null then throw primary
end runAll

private def collect(v: Any < Nothing, acc: Chunk[Finalizer[?, ?]]): Chunk[Finalizer[?, ?]] =
    v match
        case k: Kyo.Defer[?, ?, ?, ?] =>
            // a park caught between acquire settling and use starting: the resource is the
            // Defer's own value and the Acquire arrow is at the head of contA. See 8.5
            fromArrow(k.contB, fromArrow(k.contA, acc, k.value), ())
        case k: Kyo.Suspend[?, ?, ?, ?, ?, ?] => fromArrow(k.cont, acc, ())
        case k: Kyo.Handle[?, ?, ?, ?, ?]     => fromArrow(k.cont, collect(k.value, acc), ())
        case _                                => acc

private def fromArrow(a: Arrow[?, ?, ?], acc: Chunk[Finalizer[?, ?]], pending: Any): Chunk[Finalizer[?, ?]] =
    a match
        case c: Arrow.Chain[?, ?, ?, ?] => fromArrow(c.b, fromArrow(c.a, acc, pending), ())
        case f: Finalizer[?, ?]         => acc.append(f)
        case q: Acquire[?, ?, ?]        => acc.append(new Finalizer(q.release, pending))
        case _                          => acc
```

Answers to the brief's question 2:

- **Signature `Unit < Any`.** The releases are values, not actions the kernel performs, so the caller drives them.
  Row `Any` rather than `Unit < S` because the release's own row is `Any`: there is nothing for `S` to add and
  claiming `S` would be an assertion the type system does not need.
- **Order is innermost first.** `collect` accumulates in traversal order and the traversal reaches the innermost
  bracket first, because `dump` placed the innermost entry at the head of the chain
  (`entries(0).chain(entries(1).chain(...))`, `Stack.scala:118-123`).
- **Safe twice**, and what makes it safe is the `Finalizer`'s CAS, not the walk. The walk is pure: it collects and
  mutates nothing, so composing twice is free, and the second composition holds the same `Finalizer` instances,
  already flipped, so every `run()` returns false without doing anything. The same mechanism covers the harder
  case: a park value resumed on one thread and finalized on another. Exactly one side runs each release, and the
  CAS decides which.
- **On a value that is not a park:** `()`, with no allocation.

**Visibility (needs sign-off).** The brief calls this "the public extension on the pending type". The prior art was
`private[kyo] def finalizeBracket(outcome: Maybe[Result.Error[Any]]): Unit`
(`3a95636fa8:kyo-kernel2/shared/src/main/scala/kyo/kernel/Pending.scala:38`) with the comment "runtime machinery,
not user surface: runs the finalizers a parked computation's brackets carry. The scheduler calls it when dropping a
continuation that will never be resumed." I recommend `private[kyo]` for the same reason: a user holding a value
has no way to know whether it is a park, and calling this on a live value spends its releases. If it goes public it
needs scaladoc saying exactly that.

---

## 6. Every touched file, and whether it summons the lift

The rule: a file that summons `CanLift`, that is, writes a *bare value* into a `<`-typed position and lets the
implicit conversion fire, is suspended to a retry run, and new summons in core inlined-from files deepen the
cascade into the dotty crash. The check applied to every line below is mechanical: **every `<`-typed expression in
the new code is produced by `Effect.defer`, by an `Arrow` application, or is a value the drive already holds at `<`
type. No bare value is written into a `<` position anywhere.**

| file | change | lift summon |
|---|---|---|
| `kyo/kernel/Effect.scala` | add `def bracket[A, B, S](acquire: A < S)(release: A => Unit < Any)(use: A => B < S)(using Frame): B < S`, non-inline, building `Effect.defer(acquire, new Acquire(Arrow(release), Arrow(use)), Arrow.id[B])`. `Arrow(f)` is `Arrow.scala:33`, inline, taking `inline f: A => B < S`; `release` and `use` arrive already at `<` type, so no conversion fires | **no** |
| `kyo/kernel/internal/Finalize.scala` | new file: `Acquire`, `Finalizer`, `compose`, `runAll`, `collect`, `fromArrow`, `unwind`. Every `<` value is a parameter or an `Effect.defer` result | **no**, conditional on the `Eval.drive` extraction below |
| `kyo/kernel/internal/Eval.scala` | extract the loop into `private[kernel] def drive(stack: Stack, v: Any < Nothing, stop: () => Boolean): Any < Nothing` plus a `drive(v: Any < Nothing)` convenience; add `partial` (two overloads) and `reify`; add the poll at the top of `loop`; replace the two `truncate(pos + 1)` calls with `compact(pos + 1)`; extend the boundary `catch` with `Finalize.unwind`; add the unhandled-operation park in the `pos < 0` branch | **no new one**. The file already carries one and this change adds none |
| `kyo/kernel/internal/Stack.scala` | add `def compact(n: Int): Unit`; change `dump()`'s `boundary` test from `Handler` to `Barrier`. No `<` value appears in `Stack` at all | **no** |
| `kyo/kernel/Pending.scala` | add `private[kyo] def finalizeResources: Unit < Any` to the existing extension block, non-inline, body `Finalize.compose(self)` | **no** |
| `kyo/Arrow.scala` | add `private[kyo] sealed trait Barrier` (or put it in `Stack.scala`; the owner's call) | **no** |
| `kyo/kernel/internal/Handler.scala` | `Handler` also extends `Barrier` | **no** |
| `kyo/kernel/internal/KyoInternal.scala` | **unchanged** under the recommendation. Under the node fork, add `Bracket` | n/a |

**The `Eval.drive` extraction is a hard requirement, not a preference.** `Eval.apply` is `inline`
(`Eval.scala:32`), and the in-flight EffectTrace work records why that matters:

```
// EffectTrace.scala:35-37
The class and its two entry points are public because `Eval.apply` is `inline`: its body is
re-typechecked at every expansion site
```

`Eval.apply`'s body contains lift-summoning positions (for example `next(apply(o.unsafeGet), Arrow.id)` at
`Eval.scala:89` and `:127`, where a raw `BX` lands in a `BX < S2` slot). Calling the inline `Eval.apply` from
`Finalize.scala` would re-typecheck that body there and summon the lift **in a new kernel file**, which is exactly
the move the skill says deepens the cascade. `Finalizer.run` therefore calls the non-inline `Eval.drive`.

The extraction has a second benefit and a measurement obligation: today the 150-line loop expands at every `<.eval`
site (`Pending.scala:280-281`). Making it a real method is almost certainly right, and per the skill it changes the
drive's shape, so it needs the full benchmark class on both variants before it lands. That measurement is not in
this document.

---

## 7. Casts introduced, each categorized

Against the skill's closed set. Nothing here needs a new category, so no cast sign-off is required, but the
enumeration is the obligation.

| # | site | cast | category |
|---|---|---|---|
| 1 | `Eval.reify` | `curr.asInstanceOf[Any < Any]`, result back to `Any < Nothing` | erasure-forced. Same kind as `Eval.scala:204` (`v.asInstanceOf[Any < Nothing]`): the drive's currency sits at the top of the row lattice and every constructor names a concrete row |
| 2 | `Eval.partial` | `v.asInstanceOf[Any < Nothing]` in, `.asInstanceOf[A < S]` out | erasure-forced. Byte-identical to the prior implementation at `1cf05637e8:33` |

That is the whole list, and it is two rather than revision 1's five. Row `Any` deleted the other three:

- `Finalize.unwind` no longer casts a release into a closed row, because the release *is* at row `Any`. Revision 1
  reproduced the prior art's one cast here (`3a95636fa8:Finalize.scala:100`, "the walk is structural, so the release
  row is existential here; running it through eval demands the closed row the bracket's construction guaranteed").
  With the correction, the construction guarantees it in the type and the comment has nothing left to justify.
- `finalizeResources` no longer casts, because `Finalize.compose` already returns `Unit < Any` and `A < S` conforms
  to `Any < Nothing` by variance.
- `Stack.compact` no longer casts, because it moves entries within the array without re-typing them, and `unwind`
  reads them through the existing `Stack.entry` with a typed pattern.

Casts checked for and found unnecessary, recorded so a later reader does not add them back:

- `Finalizer.run` needs none: `release` and `resource` are its own fields at its own type parameters.
- `Finalizer.apply` needs none: `Arrow.Transform[B, B, Any]` requires `C < (Any & S2)`, which is `C < S2`, and
  `next(v, Arrow.id)` produces exactly that.
- The bare-operation rebuild in the unhandled-operation park needs none: `IX, OX, EX, CX` are bound by the
  `Suspend` arm's pattern at `Eval.scala:41`.
- `Acquire.apply` needs none: `v.unsafeGet` is the existing settled reader (`Pending.scala:289-292`), and the row
  arithmetic (`Arrow[B, C, Any & S2] <: Arrow[B, C, S & S2]`) is contravariance.

---

## 8. The open questions

### 8.1 Does `release` run on abort, and should it see the outcome?

**Confirming the owner's reading: kernel release is unconditional and outcome-blind, and `Result`-aware bracketing
is built above.** Beyond the directive, there is a technical argument for it that I want on the record, because it
means the reading is not merely a scoping preference.

**Kernel-level outcome detection is position-dependent, and therefore unreliable.** The kernel has no `Abort`; a
failed computation reaches the drive as a settled value that happens to be a `Result.Error`. The prior design read
it with a type test:

```scala
// 3a95636fa8:KyoInternal.scala:33-36
private[kyo] def outcomeOf(v: Any): Maybe[Result.Error[Any]] =
    unnest(v) match
        case e: Result.Error[Any] @unchecked => Maybe(e)
        case _                               => Maybe.Absent
```

That works only when the error settles *through* the bracket. Put the bracket inside `Abort.run`'s region and it
does not: the abort is a suspension the region's handler answers with `Loop.done`, which discards the interior, and
the release then runs from `compact` with no value to inspect at all. Same program, same failure, two different
outcomes depending on where the handler sits relative to the bracket. An outcome parameter that is right only for
one of those positions is worse than no outcome parameter, because it looks total and is not.

So the kernel releases on every path and tells the release nothing:

| path | release runs | what it is told |
|---|---|---|
| `use` completes normally | yes, at the bracket's stack slot | nothing |
| `use` settles to a `Result.Error` | yes, same slot, indistinguishable from success | nothing |
| something throws | yes, from `Finalize.unwind` at the drive boundary | nothing |
| a `Loop.done` discards the enclosing region | yes, from the entries `compact` kept | nothing |
| the park is abandoned | yes, from `finalizeResources` | nothing |

**The consequence for `kyo-core`, stated as a routing note rather than a design.** `Sync.ensure`'s outcome-aware
overload (`Sync.scala:107-115`) and `Scope.ensure`'s (`Scope.scala:66`) cannot read the outcome from the kernel any
more. The outcome has to be captured where it is unambiguous, which is above the kernel and inside the `use`
computation: wrap the use in `Abort.run`, thread the `Result` out, and let the release read it. That is a
`kyo-core` port task, it is not blocked by anything here, and it is worth flagging to whoever ports `Sync`.

### 8.2 `finalizeResources`: signature and semantics

**Recommendation:** `private[kyo] def finalizeResources: Unit < Any`, specified in 5.6. In summary: row `Any`
because the release's row is `Any`; innermost first; safe any number of times and safe against a concurrent resume,
by the `Finalizer`'s CAS; `()` on a value that is not a park; `private[kyo]` unless the owner wants it public with
a warning in the scaladoc.

**No `outcome` parameter**, following 8.1.

### 8.3 Resume then park again, and resume on a foreign thread

**Park, resume, park again.** The second park re-folds the stack, and the `Finalizer` that came in from the first
park's chain goes back out in the second park's chain. It is the same object throughout, so the CAS is the same
CAS, and exactly-once spans any number of cycles. Stateful-region state crosses the cycles through `Stack`'s
existing `dump`/`put` pair (`Stack.scala:107-111` and `:23-31`), unchanged by any of this.

**Resume on a different thread.** Three things are per-thread and all three are handled:

- The `Stack` pool (`Stack.local`, `Stack.scala:193-197`): the park value holds no stack, so the resuming thread
  borrows its own, cleared.
- The `Safepoint` slot: `partial` calls `Safepoint.get()` on the resuming thread and arms *that* slot; the previous
  slice's arming was undone by `Safepoint.restore` in its `finally`.
- The trace machinery, which is already per-drive.

**What the kernel does not promise** is memory visibility of the resource object itself. The `Finalizer`'s
`AtomicBoolean` gives a happens-before for the release *decision*, not for the resource's fields. Whoever publishes
the park value supplies that. Same contract as the CPS kernel; it belongs in the scaladoc.

**A resume that is not the only resume.** A park value is a complete value, so it can be driven twice, and both
drives run `use`'s remainder against the same resource. Exactly one releases (whichever reaches the `Finalizer`
first); the other passes through. That matches the CPS kernel's multi-shot `Ensure`, and the alternative (release
per shot) would need a re-acquire the kernel cannot perform. Pinned in section 9.

### 8.4 A finalizer that suspends, aborts, or throws

**Suspends: it cannot, and the type is what stops it.** `release: Arrow[A, Unit, Any]` produces a `Unit < Any`.
Because `<` is contravariant in `S`, a release written against any effect (`a => Sync.defer(close(a))` producing
`Unit < Sync`) does not conform to `A => Unit < Any` and does not compile. There is nothing to design for this case
and no machinery is specified for it. The residual, stated for completeness: a value at row `Any` that nonetheless
contains a `Kyo.Suspend` can only be produced by a cast or by erasure, and driving it reaches
`bug("unhandled suspension")`, which is what any out-of-scope operation gets.

**Aborts: they reduce to throws.** There is no `Abort` at row `Any`, so a release cannot abort. At the layer above,
an "aborting" finalizer is one whose `Result.Error` the release itself has to handle, which is what `Sync.ensure`
already does (`Abort.run[Throwable](f(outcome).unit).map(_.getOrThrow)`, `Sync.scala:115`): it converts the error
into a throw. So the abort case *is* the throw case.

**Throws: the one genuinely open behavior, and the answer is run-all-and-aggregate.**

The design **runs every finalizer, propagates the first failure, and suppresses the later ones onto it**. Precisely:

1. `Finalizer.run()` does not catch. A throw from a release propagates out of `run()`, and out of `apply` if the
   drive was applying the entry.
2. **On the normal path**, that throw unwinds `loop` and reaches the drive's boundary `catch`, which calls
   `Finalize.unwind(stack, ex)`. `unwind` walks the remaining stack entries and calls `run()` on each `Finalizer`,
   catching each individually and doing `ex.addSuppressed(t)`. So the outer resources are still released, and every
   secondary failure is attached to the primary. Then `EffectTrace.splice(ex)` and rethrow. The value that `use`
   produced is abandoned, which is correct: the release is part of the bracket's contract and it failed.
3. **On the region-discard path**, `compact` left the survivors on the stack rather than folding them into an
   arrow, so a throw from one of them behaves exactly as in (2). This is why 5.4 uses `compact` and not the
   fold-into-a-chain shape revision 1 had: a chain in flight is not reachable from the boundary `catch`, so a throw
   partway down it would strand every finalizer after the failure.
4. **On the abandonment path**, `Finalize.runAll` (5.6) does the same aggregation locally, because there is no
   stack to fall back on: per-item `try`, first failure kept, later ones suppressed onto it, and the primary thrown
   after all of them have had their turn.
5. **A finalizer that threw is not retried.** Its CAS flipped before the release body ran, so `unwind` and
   `finalizeResources` both skip it. That is deliberate: a release that threw has run, and running it again would
   be a double release on whatever part of it succeeded before the throw.

The shape is the prior art's, generalized. `3a95636fa8:Finalize.scala:91-106` collected failures into a
`Chunk[Throwable]` and `Pending.finalizeBracket` did `errors.headMaybe ... errors.dropLeft(1).foreach(t.addSuppressed)`.
The alternatives considered and rejected:

- **Propagate immediately, stop the chain.** Leaks every outer resource because one inner release failed. Rejected.
- **Aggregate into a fresh composite exception.** Loses the identity of the original throw, which on the normal
  path is the user's exception and the one they need to see. Suppression keeps both and is what the JVM has for
  exactly this. Rejected.
- **Swallow and log.** The kernel has no logger and should not acquire one. `Scope` above does log
  (`Scope.scala:184-187`), which is the right layer for a policy decision. Rejected here.

**Needs sign-off**, since it is a behavior the owner named as open.

### 8.5 Interaction with `Stack.dump`

**The positive half is already true for the capture path.** `dump(pos)` folds *every* entry in `[0, pos)` into the
returned arrow and nulls the slots (`Stack.scala:102-126`); no branch drops one. A `Finalizer` between the
operation and its region's handler travels into the continuation the clause receives, and that continuation is a
complete value: applying it runs `use`'s remainder, then the release, then whatever was below.

**The negative half is the bounded fold**, and 5.2 closes it: `dump()` must stop at a `Finalizer` the way it stops
at a `Handler`, or a finalizer can leave the stack inside a value in flight and become unreachable from the
boundary `catch`. That is the `Barrier` marker.

Two more details worth pinning rather than reasoning about:

- **Entry boundaries must survive the round trip.** `dump`'s `wrap` handling (`Stack.scala:114-121`) exists so an
  entry that is itself an `Arrow.Chain` is not re-flattened into two entries when the fold is pushed back
  (`Stack.push`'s `count`/`fill`, `:47-64`, descend into chains). The park's whole-stack fold uses `wrap = true`
  (the public `dump(pos)` at `:100`), which is the right one.
- **`dump` must be the only way a finalizer leaves the stack alive.** The other exits, `truncate` and `clear`,
  destroy entries. `compact` replaces `truncate` at the two sites that can see a finalizer, and the boundary
  `catch` runs before `Stack.release`. Those are the only three sites; the invariant is checkable by grepping for
  `truncate` and `clear`.

**A hole that cannot be closed at the kernel, and should not be.** A `handleCont` clause receives its continuation
as a plain function:

```scala
// Handler.scala:29
def run[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)
// built by the drive at Eval.scala:53-60 (working tree)
val k = stack.dump[OX[CX], AX, EX & S](pos)
val next = h.run(kyo.input, k(_))
```

- **Called once:** correct; the release runs at its place in the continuation.
- **Called many times:** the release runs on the first shot and is transparent on the rest, by the CAS.
- **Called zero times:** the resource is never released. The clause dropped the only reference, and it dropped it
  as a `Function1`, so it could not have finalized it even if it wanted to.

That is inherent to a first-class continuation and I do not recommend closing it in the kernel. Two apparent
closures are rejected with reasons in section 10 (a sweep at region completion, and changing the clause signature
to hand over an `Arrow`). The correct home for a lifetime that must not depend on continuation usage is the layer
above, which is what `Scope` is: its finalizers live in a queue owned by `Scope.run` (`Scope.scala:157-191`), not
in the continuation.

### 8.6 Interaction with regions

**Recommendation: the discarded interior's finalizers run, innermost first, after the region's handler is gone.**
The mechanism is `compact` (5.4) and the one-line change at the two `Loop.done` sites.

With row `Any` this is unconditionally correct, which it was not before: a release cannot name the discarded
region's effect, so there is nothing for the missing handler to fail to answer. Revision 1 had to document a
failure mode here and no longer does.

**Second-order case:** the interior may contain nested regions with their own brackets. `compact` walks the whole
range in stack order, so nested finalizers come out innermost first regardless of how many region boundaries they
cross, and the handler entries in the range are simply discarded, which is what `truncate` did.

### 8.7 The concession table

Every concession this design makes, in the required shape.

| concession | justification | minimal scope | protection | pinning test |
|---|---|---|---|---|
| `release` is at row `Any`, so a finalizer cannot perform effects | a finalizer has to be runnable with no drive, no handlers, and no row obligations, because the abandonment path has none of the three | one field's row; every other row in the node is `S` | **type-level**: `<` is contravariant in `S`, so an effectful release does not conform and does not compile, while `Arrow[A, Unit, Any]` still conforms at every use site. This is what makes "the parked value is the only owner of these resources" compiler-checked rather than evaluator-maintained | tests 27, 30, 32 (abandonment releases with no drive and no handlers); the non-conformance is a type property, not a test |
| `Finalizer` runs its release eagerly inside `apply`, entering a nested drive when the release value is a node | deferring it opens a window between the CAS flip and the release running, in which a park produces a value with a spent guard and unrun work. Row `Any` is what makes eager possible | one method, `Finalizer.run` | the nested drive is entered only for a release that built a node; the ordinary side-effecting release has already run by the time `release(resource)` returns and takes the `case _` arm with no drive | tests 26, 30; the cold-drive claim needs a number (section 11) |
| `Finalizer` is a `Barrier`, so `dump()` stops folding at it | a folded finalizer leaves the stack and is unreachable from the boundary `catch` | one condition in `Stack.dump()`'s `boundary` | the test count per entry is unchanged (`isInstanceOf[Barrier]` replaces `isInstanceOf[Handler]`); `Stack.find` still tests `Handler`, so dispatch is untouched | tests 8, 9, 37 |
| the drive polls a `stop` function at the top of `loop` | partial evaluation needs a park point, and every arm must be covered | one reference comparison, `null` for `Eval.apply` | `reify` is a separate method, so no cold code enters `loop`'s bytecode budget | test 23 (the live consumer), plus the numbers in section 11 |
| a `handleCont` clause that drops its continuation leaks | closing it needs either a sweep that breaks legitimate capture, or a surface change to the clause | documented, not mechanised | the layer above owns lifetimes that must not depend on continuation usage | test 34, named so it reads as a specification |

---

## 9. Pinning tests

Placement follows the module's rule that a test file shares a prefix with its source: `Eval.partial` and the park in
`EvalTest.scala`, stack behavior in `StackTest.scala`, `Effect.bracket` in `EffectTest.scala`, the walk in a new
`FinalizeTest.scala`, region interactions in `ArrowEffectTest.scala`.

### Bracket, normal path (`EffectTest.scala`)

1. acquire, use, release run in that order, each exactly once, on a settled acquire
2. same on a pending acquire (acquire suspends on an operation a region answers)
3. release runs before anything composed onto the bracket: `bracket(...).map(f)` records release before `f`
4. release runs before an effectful downstream, under a `handleCont` region and under a `handleLoopState` region
   (two cases; the state the handler reached at the boundary is the state the downstream sees)
5. nested brackets release innermost first
6. a bracket whose `use` settles immediately still releases
7. a release built out of `Effect.defer` runs to completion (this is the arm that enters the nested drive)

### Bracket, abnormal paths (`EffectTest.scala`)

8. a throw inside `use` releases, then propagates; the escaping exception is the original one
9. a throw inside `use` with two nested brackets releases both, innermost first
10. a throw inside a *release* still runs the outer releases, and is suppressed onto the primary
11. two releases both throwing: the first is the primary, the second is on its suppressed list
12. a `Loop.done` that discards a region containing a bracket releases it, and the answer still flows
13. same with the bracket nested two regions deep
14. a bracket whose `use` settles to a `Result.Error` releases (and is told nothing, per 8.1)

### Park and partial (`EvalTest.scala`, restoring the group at `:193-262` plus new cases)

15. a preemption stop reifies mid-computation and the resumed value completes with handler state intact (existing)
16. the `stop` function ends the slice (existing)
17. `partial` completes when nothing stops (existing)
18. an unhandled operation parks for a handler installed later (existing), and the operation is performed exactly
    once by the resumed computation, which is the `kyo.cont` double-stacking guard
19. an unhandled operation parks with the regions above it intact (existing)
20. a stop delivered between slices short-circuits by identity (existing)
21. **park in a foreign drive:** a parked value evaluated on a different thread completes
22. **replay:** a parked value evaluated twice produces the same answer both times
23. **double nesting:** `partial` over a value that is already a park resumes correctly
24. **budget park mid-path:** `SafepointConcurrencyTest.scala:77-102` unchanged, the live consumer
25. `Eval.apply` still reports `bug("unhandled suspension")`; parking is `partial`-only
26. a park whose stack is empty returns the residual by identity, with no `Defer` wrapper allocated

### Resources across parks and captures (`FinalizeTest.scala`)

27. **release exactly once:** a value parked mid-`use` then resumed releases once, not twice
28. **release on abandonment:** the same value, not resumed, releases when `finalizeResources` is driven
29. **twice is safe:** `finalizeResources` driven twice releases once
30. **resume and finalize race:** resume on one thread and `finalizeResources` on another; the release count is
    exactly one (loop the scenario until deterministic, per the repository's concurrency-reproduction rule)
31. **the acquiring drive never resumes:** park between acquire settling and `use` starting; `finalizeResources`
    still releases. This is the `Acquire`-in-the-walk case of 5.6 and the one a naive walk misses
32. **ordering on abandonment:** nested brackets in a park release innermost first
33. **park, resume, park again, abandon:** releases once
34. **abandonment with no drive and no handlers:** `finalizeResources` on a park taken from inside two regions
    releases without `bug`ing. This is the pin on the row-`Any` protection
35. **a throwing release during abandonment:** the remaining releases still run and the failures aggregate
36. **multi-shot capture:** a `handleCont` clause calling its continuation twice releases once, and both shots run
    to completion
37. **zero-shot capture:** a `handleCont` clause dropping its continuation does not release; named so it reads as a
    specification, not a bug

### Stack (`StackTest.scala`)

38. `compact(n)` with no finalizer in the range is `truncate(n)`: same `size`, same `head`, same remaining entries
39. `compact(n)` keeps the finalizers in the range, in the same relative order, at the top, and drops the rest
40. `compact(n)` where every entry in the range is a finalizer keeps all of them and moves nothing
41. `dump()` stops at a `Finalizer` the way it stops at a `Handler`: the finalizer is still on the stack afterwards
42. whole-stack `dump(size)` followed by `push` restores the entries in order, including a chain entry that must
    not be re-flattened, and including a `HandlerLoopState` at its live state
43. `clear()` on a stack that was fully dumped is a no-op

---

## 10. Rejected alternatives

**`Bracket` as a `Kyo` node kind.** `Kyo.Defer` already reifies "value, then contA, then contB", which is exactly
"acquire, then register-and-use, then continue" (3.4). The node adds one `instanceof` to the drive's fall-through,
which is the settled arm and the hottest arm there is, and buys nothing the `Acquire` arrow does not. Specified in
full in 3.1 to 3.3 in case the owner keeps it.

**`Park` as a `Kyo` node kind.** Rejected on the recorded ruling at `d4e59ffa56`, which deleted `Kyo.Park`,
`Stack.copy*`, `Stack.pushAll`, `Stack.regionAbove`, and `Eval.fold/park/restore` in one change. The one thing it
would buy is a bulk push instead of a fold-and-flatten, a cold-path performance question with no measurement.

**A `Parked` carrier in the drive's value channel.** The `1cf05637e8` design, `final private class Parked(val v: Any)`,
withdrawn at `c2d5db5072`. Section 2.2 supplies the equation it lacked; the carrier stays deleted because
`Effect.defer(residual, fold)` needs none.

**Deferring the release behind `Effect.defer` instead of running it eagerly.** This was revision 1 of this document
and it is wrong: it opens a window between the CAS flip and the drive reaching the release node, and a park landing
in that window produces a value whose `Finalizer` is gone from the stack and whose release has not run, so
`finalizeResources` finds nothing and the resource leaks. Row `Any` is what makes the eager form possible, which is
the strongest argument for the owner's correction.

**Folding a discarded region's finalizers into a chain (`detach`) instead of keeping them on the stack
(`compact`).** A chain in flight is not reachable from the boundary `catch`, so a throw partway down it strands
every finalizer after the failure. `compact` keeps them where `unwind` can find them and is the same amount of
code.

**Finalizers as a parallel `Stack` array beside `states`.** The parked value must own the finalizers, so `dump`
would have to attach them to the folded arrow anyway; the array is then a second copy that `ensure`, `pop`,
`truncate`, and `clear` all have to maintain, plus a second type test in `put`, which is on the push path. The
entry array already is the collection.

**A `Finalizer` the drive recognizes by class, with the release logic in the drive.** It would not be a complete
value: the same entry has to work when a `dump` folds it into a continuation a clause replays in a foreign drive,
and drive-resident logic cannot follow it there. Keeping the semantics in `Finalizer.run` also leaves `Eval$::loop`
untouched, which section 6 needs.

**A region-exit crossing (`Kyo.exitStep`), as in `3a95636fa8`.** Not needed. That design needed it because
`Bracket.map` extended the *use* chain (`KyoInternal.scala:118-127` at that commit), so the region boundary was
invisible in the structure. Here `map` wraps rather than fuses (`Pending.scala:27-28`), so composition lands below
the bracket on the stack and runs after the release by stack order.

**An outcome parameter on `release`.** Rejected on the owner's directive and, independently, on the argument in
8.1: kernel-level outcome detection is position-dependent, so the parameter would be right for a bracket outside an
error region and silently wrong for one inside it.

**Running a discarded region's finalizers under a rebuilt region.** Moot under row `Any` (the release cannot need
the region), and it was ill-typed anyway: a `HandlerCont` clause answers at the region's `A` and a `HandlerLoop`
clause at the region's `B` (`Handler.scala:29-36`), so neither can be installed over a `Unit`-returning body. Kept
in this list because it is the first thing a reader will reach for if the row constraint is ever loosened.

**A `Finalize` `ArrowEffect` handled at the boundary instead of a stack entry.** Fails on the two paths that
motivate the feature: a `Loop.done` that discards a region discards its handler with it, and an abandoned park has
no drive and therefore no handler at all. An effect can only be interpreted where an interpreter is running, and
the resource paths are exactly the paths where none is.

**A sweep at region completion, releasing anything a clause did not use.** Cannot distinguish a clause that dropped
a continuation from one that legitimately captured it to resume later. The capture case is already pinned in this
repository (the "captured continuation is a value" group at `d4e59ffa56`), and sweeping would turn a leak into a
use-after-free.

**Changing the `handleCont` clause to receive an `Arrow` instead of a `Function1`.** A surface change the owner has
not asked for, and it only relocates the obligation onto every clause author rather than discharging it.

**Stopping the LIFO chain at the first throwing release.** Leaks every outer resource because one inner release
failed. See 8.4 for the three rejected throw policies and why suppression-onto-the-primary wins.

---

## 11. What is not settled, and what to check first

- **The `Eval.drive` extraction is a prerequisite** (section 6), and per the skill it changes the drive's shape, so
  it needs the full benchmark class on both variants, same session, back to back, before it lands. That measurement
  is not in this document.
- **No number here is measured.** Every performance statement is a shape argument. The claims that need numbers:
  the `stop ne null` poll on the hot rows; the `Barrier` test in `dump()`'s boundary (which should be free, since
  it replaces an equal-cost test, but "should be" is a hypothesis); the `compact` type test on the region-completion
  rows; the nested-drive frequency in `Finalizer.run` under a realistic `Sync.ensure` shape; and the `Defer`
  plus `Acquire` bracket against the node form.
- **`Eval.drive` returning `Any < Nothing` rather than `Any`** is a change from today's `apply`, which returns the
  settled `A`. `partial` needs the pending form and `apply` needs the settled one, so one of them unwraps. Which
  side pays is a small design choice I did not settle; it interacts with the `unsafeGet` at `Eval.scala:157`.
- **The working tree is moving.** `Eval.scala`, `Stack.scala`, and the tests were being edited by the EffectTrace
  work while this was written. The new `Finalize.unwind` call composes with the per-arm `catch`es that work added
  (`Eval.scala:44-49`, `:56-59`, `:64-67`, `:164-167`, `:173-176`), which attach trace information and rethrow, so
  the boundary sees the same exception; and `Stack.entry` (`:87`), which that work added for the trace sweep, is
  exactly the accessor `unwind` needs. That is a reading of code that was still changing.
