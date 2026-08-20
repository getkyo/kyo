# Bracket, Park, and finalizers

Final implementation design. No source was changed, nothing was built, nothing was committed.

Every `file:line` was reopened against the worktree as it stands now (`Eval.scala` is 233 lines, `Pending.scala` 316,
`ArrowEffect.scala` 297, after the unqualified `Arrow.Transform` / `Arrow.Chain` imports landed). Line numbers from
the two prior documents are not reused.

The owner's ruling is binding: **new node types.** A `Kyo.Defer` owes nothing, so nothing downstream can honor an
obligation encoded as one. Identity lives in the node type.

---

## 1. The three node types

| node | what it holds | what it guarantees |
|---|---|---|
| `Kyo.Bracket[A, B, C, S]` | `acquire`, `use`, `release`, `cont` | **Owes nothing, and cannot reach `use` without minting.** It is its own `contA`, so the only way to get past `acquire` is through `Bracket.apply`, which is the only `new Finalizer` in the kernel |
| `Kyo.Ensure[A, B, C, S]` | `acquired`, `use`, `finalizer`, `cont` | **Owes exactly one release, named by a field.** Produced only by `Bracket.apply`. It exists the instant `acquire` settles and before `use` is applied |
| `Kyo.Park[A, B, S]` | `value`, `cont`, `owed` | **Owes exactly `owed`, plus whatever `value` owes.** `owed` is collected from the stack in the same pass that folds it, so the abandonment path never depends on the fold's internal shape |

All three extend `Kyo.Defer` (`KyoInternal.scala:16-22`), with `value`/`contA`/`contB` defined so the drive's
existing `Defer` arm (`Eval.scala:41-44`) drives each one correctly and unchanged. **No new drive arm, no new type
test on the hot path.** The subclass is not a shortcut: it is exactly the split the owner named. Drive semantics are
`Defer`'s; obligation semantics are not, and the class is what carries the difference to `Finalize`.

`Bracket` is additionally an `Arrow.Transform`, the shape `Kyo.Suspend with Transform` already uses
(`ArrowEffect.scala:38`) and the drive's own clause dispatchers use (`Eval.scala:77-78`).

### Why `Ensure` is a node and not `Effect.defer(use(a), finalizer.chain(next))`

Two consequences, both structural:

1. **`use` cannot throw before the finalizer is registered.** `Ensure.value` is the settled resource and `contA` is
   `use`, so the drive pushes `contB` (which flattens to `[finalizer, cont...]`, `Stack.scala:37-41`, `:53-64`) and
   `contA` before it ever applies `use` (`Eval.scala:42-44`). `use` then runs from the settled arm's guarded call
   (`Eval.scala:192-198`), with the finalizer already an entry. The `Effect.defer(use(a), ...)` form evaluates
   `use(a)` as an argument, before the finalizer exists.
2. **No by-name re-execution.** `Effect.deferInline`'s `value` is a by-name method (`Effect.scala:20-24`), and
   `EffectTrace.Builder.drain` reads `d.value` (`EffectTrace.scala:281`). `Ensure.value` is a plain field read, so a
   trace taken over a live bracket cannot re-run `use`.

---

## 2. The one mechanism

**`Finalizer` extends `AtomicBoolean`; `run()` compare-and-sets `false -> true` and completes the release inside the
winning call; `Kyo.Bracket.apply` is the only site in the kernel that constructs one.**

Everything else in this document exists to make that one object *reachable* from every place the obligation can be
observed. Replication is free (the same object may sit on the stack, inside a fold, inside `Park.owed`, and inside an
`Ensure` node at once) because the CAS decides who runs it, not the position it was read from. Minting is not free,
which is why it is confined to one arm.

Row `Any` on `release` is what makes the eager form possible: only a total release can run at the moment of the flip,
so there is no window in which the guard is spent and the work has not happened.

---

## 3. Change list

### 3.1 `kyo/kernel/internal/KyoInternal.scala`: the three node types

Added inside `object Kyo`, after `Handle` (`:34-40`) and before `render` (`:46-57`). Imports gain `kyo.Span`.

```scala
    /** A bracket that has not acquired yet.
      *
      * The node is its own `contA`, so the drive pushes it, drives `acquire`, and applies it from the settled arm
      * (`Eval.scala:191-199`). That application is the only site in the kernel that constructs a `Finalizer`: a value
      * holding an unconsumed `Bracket` has acquired nothing and therefore owes nothing.
      */
    abstract class Bracket[A, B, C, S] extends Defer[A, B, C, S] with Transform[A, B, S]:
        def acquire: Kyo[A, S]
        def use: Arrow[A, B, S]
        def release: Arrow[A, Unit, Any]
        def cont: Arrow[B, C, S]

        final def value = acquire
        final def contA = this
        final def contB = cont

        def frame = use.frame

        final override def apply[D, S2](v: A < S2, next: Arrow[B, D, S2]): D < (S & S2) =
            v match
                case kyo: Kyo[A, S2] @unchecked =>
                    // acquire is still running: stay a node, nothing is owned yet
                    Effect.defer(kyo, this, next)
                case _ =>
                    val bracket = this
                    new Ensure[A, B, D, S & S2]:
                        // the single delivery unnest for the resource (Pending.scala:298-301); the
                        // node keeps the union form for the drive and the finalizer the raw form
                        val finalizer = new Finalizer[A, B](release, v.unsafeGet)
                        def acquired  = v
                        def use       = bracket.use
                        def cont      = next

        override def toString: String = render(acquire)
    end Bracket

    /** A bracket that has acquired. `finalizer` is the live, exactly-once obligation.
      *
      * `contB` puts the finalizer above `cont`, so the pop order is `use`, then the release, then whatever the caller
      * composed onto the bracket. `Arrow.chain` drops an identity right half (`Arrow.scala:19-21`), so the common
      * case allocates nothing.
      */
    abstract class Ensure[A, B, +C, -S] extends Defer[A, B, C, S]:
        def acquired: A < S
        def use: Arrow[A, B, S]
        def finalizer: Finalizer[A, B]
        def cont: Arrow[B, C, S]

        final def value = acquired
        final def contA = use
        final def contB = finalizer.chain(cont)

        override def toString: String = s"Kyo(ensure, ${finalizer.frame.position.show})"
    end Ensure

    /** A slice a partial drive stopped on.
      *
      * `owed` is the finalizers the drive's stack held when the slice ended, innermost first, collected by
      * `Eval.reify` in the same pass that folds those entries into `cont`. It is the authority for the abandonment
      * path: `Finalize` reads it and does not walk `cont`, so honoring the obligation does not depend on the fold's
      * internal shape. On resume the fold is pushed back and the same objects become entries again.
      */
    abstract class Park[A, B, -S] extends Defer[A, B, B, S]:
        def value: A < S
        def cont: Arrow[A, B, S]
        def owed: Span[Finalizer[?, ?]]

        final def contA = cont
        final def contB = Arrow.id[B]
    end Park
```

**Why:** these are the guarantee. `Bracket` makes minting unavoidable and unique; `Ensure` makes an acquired
obligation a field on the value; `Park` makes a folded obligation a field on the value that leaves the drive.

**Lift:** none. `Bracket.apply` returns a `Kyo`, which reaches its `D < (S & S2)` slot through
`<.fromKyo` (`Pending.scala:17`), an implicit conversion and not the `CanLift` lift. No bare value is written into a
`<` position.

**Note on `toString`:** `Defer.toString` (`KyoInternal.scala:21`) and `Transform.toString` (`Arrow.scala:78`) are both
concrete, so `Bracket` must state which it wants; `Ensure` overrides so a debugger read does not print through a
resource.

---

### 3.2 `kyo/kernel/internal/Stack.scala`: `Barrier`, `Captured`, and the fold boundary

Two markers, beside their only reader, and one changed condition.

```scala
/** A stack entry the bounded fold must not cross.
  *
  * `Handler` and `Finalizer` are the only implementors: a region because a fold across it is a region crossing, a
  * finalizer because `dump()` hands its fold to an arrow that can throw (`Eval.scala:176`, `:185`, `:194`), and a
  * finalizer inside that fold is reachable from nothing when the throw reaches the drive's boundary.
  */
private[kernel] trait Barrier

/** An entry holding a folded continuation it has not pushed back. Its finalizers are still owed, so the recovery
  * paths have to see them; without this they are a closure capture and structurally invisible. The two implementors
  * are the drive's clause dispatchers (`Eval.scala:77-94`, `:120-139`).
  */
private[kernel] trait Captured:
    def captured: Arrow[?, ?, ?]
```

```scala
    def dump[A, B, S](): Arrow[A, B, S] =
        @tailrec def boundary(i: Int): Int =
            if i == size || i == reach || entries((head + i) & mask).isInstanceOf[Barrier] then i
            else boundary(i + 1)
        dump[A, B, S](boundary(0), false)
    end dump
```

**Why:** the no-argument `dump` (`Stack.scala:128-133`) is the one fold that can be prevented, and preventing it is
what keeps a finalizer on the stack where the boundary catch can find it.

Not `sealed`: Scala 3 permits direct extension of a `sealed` trait only from its own file, and `Handler`
(`Handler.scala:9`) and `Finalizer` (new file) are two other files. `private[kernel]` plus the comment carries the
closure claim, the same shape `Arrow.Transform` already has (`Arrow.scala:71`, extended from `Handler.scala:9` and
`ArrowEffect.scala:38`).

`Stack.find` is unchanged (`Stack.scala:89-98`): it tests `Handler` by tag, and a finalizer answers no tag.

**Lift:** none. `Stack.scala` contains no `<`-typed expression.

---

### 3.3 `kyo/kernel/internal/Handler.scala`: one word

```scala
sealed abstract class Handler[E <: ArrowEffect[?, ?], A, B, -S] extends Arrow.Transform[A, B, S] with Barrier:
```

**Why:** `boundary` now tests one type instead of two, and a region is a barrier for the same reason it always was.

**Lift:** none.

---

### 3.4 `kyo/kernel/internal/Finalize.scala`: new file

```scala
package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.Span
import kyo.discard
import kyo.kernel.*
import scala.annotation.tailrec
import scala.collection.mutable.ArrayDeque

/** The exactly-once release obligation for one acquired resource.
  *
  * Constructed only by `Kyo.Bracket.apply`. The constructor is `private[internal]` so nothing above the kernel's
  * internals can mint a second identity for one resource; within the package the rule is one site, grepable as
  * `new Finalizer`.
  */
final class Finalizer[A, B] private[internal] (val release: Arrow[A, Unit, Any], val resource: A)
    extends java.util.concurrent.atomic.AtomicBoolean
    with Arrow.Transform[B, B, Any]
    with Barrier:

    def frame: Frame = release.frame

    /** Runs the release exactly once, to completion, and reports whether this call ran it.
      *
      * The flag flips before the body runs, so `false` means another caller owns the release, not that it has
      * finished. Completing the work inside the winning call is what leaves no window in which the guard is spent
      * and the release has not happened; row `Any` is what permits it.
      */
    def run(): Boolean =
        if !compareAndSet(false, true) then false
        else
            release(resource) match
                case k: Kyo[?, ?] => discard(Eval.settle(k)) // cold: the release built a node
                case _            => ()                      // ordinary: the body already ran
            true
        end if
    end run

    /** A spent finalizer does nothing and still passes the value through. The CPS kernel's `Ensure` is the same
      * shape, a CAS-guarded body a second call skips
      * (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:148-149`).
      */
    def apply[C, S2](v: B < S2, next: Arrow[B, C, S2]): C < S2 =
        discard(run())
        next(v, Arrow.id)

    override def toString: String = s"Arrow(release, ${frame.position.show})"
end Finalizer

object Finalize:

    private val none: Span[Finalizer[?, ?]] = Span.empty

    /** A value position. The same object can occupy a value position and an arrow position at once: a `Bracket` is
      * its own `contA` and the drive's clause dispatchers are their own. The wrapper keeps the two visits apart, the
      * way `EffectTrace.Builder.Node` does (`EffectTrace.scala:161`).
      */
    final private class Node(val kyo: Kyo[?, ?])

    /** Collects the finalizers reachable from what has been pushed, innermost first.
      *
      * A worklist rather than recursion in both roles: a park over a long map chain is a `Defer` as deep as the
      * chain, and `Stack.dump`'s fold is a `Chain` as deep as the stack. The kernel's three existing walkers are
      * loops for the same reason (`KyoInternal.scala:46-57`, `Arrow.scala:98-119`, `EffectTrace.scala:259-294`).
      * No fuel cap: a dropped finalizer is a leak, not a truncated rendering.
      */
    final private class Walk:
        private val work = new ArrayDeque[Any]
        private var out  = new Array[Finalizer[?, ?]](4)
        private var size = 0

        private def add(f: Finalizer[?, ?]): Unit =
            if size == out.length then out = Array.copyOf(out, size * 2)
            out(size) = f
            size += 1
        end add

        def push(item: Any): Unit =
            if !((item: AnyRef) eq Arrow.Id) then discard(work.prepend(item))

        def pushValue(v: Any): Unit =
            v match
                case n: Nested[?] => pushValue(n.value)
                case k: Kyo[?, ?] => push(new Node(k))
                case _            => ()

        // push prepends, so the last item pushed drains first: each arm pushes in reverse run order and the
        // collected order is the order the releases would have run, innermost first
        @tailrec def drain(): Unit =
            if !work.isEmpty then
                work.removeHead() match
                    case f: Finalizer[?, ?] =>
                        add(f)
                    case c: Arrow.Chain[?, ?, ?, ?] =>
                        push(c.b); push(c.a)
                    case c: Captured =>
                        push(c.captured)
                    case n: Node =>
                        n.kyo match
                            case k: Kyo.Ensure[?, ?, ?, ?] =>
                                // the field, so the walk does not depend on contB being a chain
                                push(k.cont); push(k.finalizer); pushValue(k.value)
                            case k: Kyo.Park[?, ?, ?] =>
                                // `owed` is the authority for the fold; `cont` is deliberately not walked
                                var i = k.owed.size - 1
                                while i >= 0 do
                                    push(k.owed(i)); i -= 1
                                pushValue(k.value)
                            case k: Kyo.Defer[?, ?, ?, ?] =>
                                // also the Bracket arm: contA is the node in arrow position, which owes
                                // nothing and terminates at the default arm below
                                push(k.contB); push(k.contA); pushValue(k.value)
                            case k: Kyo.Handle[?, ?, ?, ?, ?] =>
                                push(k.cont); pushValue(k.value)
                            case k: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                                push(k.cont)
                    case _ => ()
                end match
                drain()
            end if
        end drain

        def result: Span[Finalizer[?, ?]] =
            if size == 0 then none else Span.fromUnsafe(Array.copyOf(out, size))
    end Walk

    /** True for an entry that can hold a finalizer. Keeps the ordinary throw path, where no bracket is live,
      * allocation-free.
      */
    private def interesting(a: Arrow[?, ?, ?]): Boolean =
        a.isInstanceOf[Finalizer[?, ?]] || a.isInstanceOf[Arrow.Chain[?, ?, ?, ?]] || a.isInstanceOf[Captured]

    private def scan(stack: Stack, n: Int): Span[Finalizer[?, ?]] =
        @tailrec def any(i: Int): Boolean =
            i < n && (interesting(stack.entry(i)) || any(i + 1))
        if !any(0) then none
        else
            val w = new Walk
            var i = n - 1
            while i >= 0 do
                w.push(stack.entry(i)); i -= 1
            w.drain()
            w.result
        end if
    end scan

    /** Runs every finalizer, so one failure cannot strand the outer resources. The first failure propagates and the
      * rest are suppressed onto it; with a `primary` already unwinding, every failure is suppressed onto it and
      * nothing new is thrown. A finalizer that threw is not retried: its flag flipped before the body ran, and a
      * retry would double-release whatever part succeeded.
      */
    private def runAll(fs: Span[Finalizer[?, ?]], primary: Throwable): Unit =
        var first = primary
        var i     = 0
        while i < fs.size do
            try discard(fs(i).run())
            catch case t: Throwable => if first eq null then first = t else first.addSuppressed(t)
            i += 1
        end while
        if (first ne null) && (first ne primary) then throw first
    end runAll

    /** The finalizers the drive's stack holds, innermost first. `Eval.reify` writes this into `Kyo.Park.owed`. */
    private[kernel] def owed(stack: Stack): Span[Finalizer[?, ?]] = scan(stack, stack.size)

    /** The releases owed by the bottom `n` entries, run now. The throw path passes the whole stack and the
      * exception that is unwinding; a discarded region passes its interior and `null`.
      */
    private[kernel] def unwind(stack: Stack, n: Int, primary: Throwable): Unit =
        runAll(scan(stack, n), primary)

    /** The releases owed by a continuation nobody will apply. */
    private[kernel] def unwind(a: Arrow[?, ?, ?], primary: Throwable): Unit =
        if interesting(a) then
            val w = new Walk
            w.push(a)
            w.drain()
            runAll(w.result, primary)
        end if

    /** The releases a value still owes, innermost bracket first, as a value the caller drives. */
    private[kyo] def compose(v: Any < Nothing): Unit < Any =
        val w = new Walk
        w.pushValue(v)
        w.drain()
        val fs = w.result
        if fs.isEmpty then () else Effect.defer(runAll(fs, null))
    end compose

end Finalize
```

**Why:** one walker with two seed shapes, used by every recovery path, so there is no second traversal that can fall
behind the first.

**Lift:** yes, one shape. `compose` returns `Unit < Any` and both of its branches put a `Unit` in that slot (the bare
`()` and `runAll`'s result inside `Effect.defer`). `Unit` is not a `Singleton`, so the evidence resolves through
`CanLift.derived` (`CanLift.scala:30`), a plain `inline given` returning `null`; only `derivedSingleton` (`:34`)
splices a macro. `Implicits.lift` (`Implicits.scala:10-15`) then takes the primitive arm for `Unit` and compiles to a
cast. No macro expands, so this file does not suspend a compilation unit.

---

### 3.5 `kyo/kernel/internal/Eval.scala`

Ten changes. The first is structural; the rest are localized.

#### (a) `drive`, and the three entry points

`Eval.apply` stays `inline` and its expansion is unchanged. The loop moves into a private `inline def drive` that
`apply` expands as today, plus one private non-inline `driveTo` that expands the loop exactly once inside
`Eval.scala` for the two entry points that must not expand it at their call sites.

```scala
    @nowarn("msg=anonymous")
    inline def apply[A, S](v: A < S): A =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        try drive(v.asInstanceOf[Any < Nothing], null).unsafeGet.asInstanceOf[A]
        finally Safepoint.restore(slot, saved)
    end apply

    /** The one non-inline copy of the drive, for the entry points that must not expand it at their call sites.
      * `apply` is unchanged and still expands `drive` wherever it is called.
      */
    private def driveTo(v: Any < Nothing, stop: () => Boolean): Any < Nothing = drive(v, stop)

    /** Settles a value with its own stack, for `Finalizer.run`. Frames from this copy carry class
      * `kyo.kernel.internal.Eval$`, which `EffectTrace.isPlumbing` filters (`EffectTrace.scala:127`), so a release
      * that throws keeps its own frame and loses the nested drive's.
      */
    private[kernel] def settle(v: Any < Nothing): Any =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        try driveTo(v, null).unsafeGet
        finally Safepoint.restore(slot, saved)
    end settle

    private val neverStop: () => Boolean = () => false

    private[kyo] def partial[A, S](v: A < S): A < S = partial(v, neverStop)

    /** Drives until the computation parks, handing back a value that resumes on a later drive. */
    private[kyo] def partial[A, S](v: A < S, stop: () => Boolean): A < S =
        val slot = Safepoint.get()
        // a stop delivered between slices short-circuits: the request is consumed here and the input comes back
        // by identity, so the caller can reschedule with no drive at all
        if Safepoint.consumeStopped(slot) then v
        else
            val saved = Safepoint.save(slot)
            Safepoint.arm(slot)
            try driveTo(v.asInstanceOf[Any < Nothing], () => Safepoint.consumeStopped(slot) || stop())
                    .asInstanceOf[A < S]
            finally Safepoint.restore(slot, saved)
        end if
    end partial

    @nowarn("msg=anonymous")
    private inline def drive(v: Any < Nothing, stop: () => Boolean): Any < Nothing =
        val stack = Stack.borrow()

        @tailrec def loop(curr: Any < Nothing): Any < Nothing =
            if (stop ne null) && stop() then reify(stack, curr)
            else
                curr match
                    ... // today's arms, with the changes below
        end loop

        try loop(v)
        catch
            case ex: Throwable =>
                Finalize.unwind(stack, stack.size, ex)
                EffectTrace.splice(ex)
                throw ex
        finally
            Stack.release(stack)
        end try
    end drive
```

**Why:** `partial` is the surface the park exists for, and `settle` is how a release that built a node runs. Neither
may expand a 1500-byte loop at its call sites; `apply`'s expansion, which the owner decided on, is untouched.

`save`/`arm` in that order preserves the caller's armed state in `saved` and restores it in the `finally`, matching
`1cf05637e8:31-32`. The identity short-circuit is what `EvalTest.scala:1021` asserts.

**Note:** the `finally` at `Eval.scala:217` calls `Stack.release`, which calls `clear()` (`Stack.scala:186`,
`:145-149`). Clearing a stack that still held finalizers would drop them silently, so `unwind` runs in the `catch`,
which is before the `finally`. On the normal exits the stack is already empty: `loop` returns from the settled arm
only when `stack.isEmpty` (`Eval.scala:162`, `:201`), and `reify` consumes every slot.

#### (b) `reify`

```scala
    /** The parked slice as a value. Cold, and a separate method so nothing of it enters `loop`'s bytecode budget. */
    private def reify(stack: Stack, curr: Any < Nothing): Any < Nothing =
        if stack.isEmpty then curr
        else
            val fs = Finalize.owed(stack)
            val k  = stack.dump[Any, Any, Nothing](stack.size)
            new Kyo.Park[Any, Any, Nothing]:
                def value = curr
                def cont  = k
                def owed  = fs
        end if
    end reify
```

**Why:** this is where `Park.owed` is written, in the same pass over the same entries that `dump` is about to fold, so
the ledger and the fold cannot drift.

**Cast-free**: `dump[Any, Any, Nothing]` gives `Arrow[Any, Any, Nothing]`, `curr` is already `Any < Nothing`, and
`Kyo.Park[Any, Any, Nothing] <: Kyo[Any, Nothing]` reaches `Any < Nothing` through `<.fromKyo` (`Pending.scala:17`).

#### (c) The unhandled-operation park, at `Eval.scala:48-53`

```scala
                    if pos < 0 then
                        if stop eq null then
                            try bug(s"unhandled suspension: ${kyo.tag}")
                            catch
                                case ex: Throwable =>
                                    EffectTrace.attach(ex, kyo, Arrow.id[Any], stack)
                                    throw ex
                        else
                            // kyo.cont is already on the stack (:46), so the parked value carries the bare
                            // operation and the resume does not stack the continuation twice
                            reify(
                                stack,
                                new Kyo.Suspend[IX, OX, EX, CX, OX[CX], Any]:
                                    def frame = kyo.frame
                                    def tag   = kyo.tag
                                    def input = kyo.input
                                    def cont  = Arrow.id[OX[CX]]
                            )
```

**Why:** a full drive still reports the bug; a partial drive parks. `IX, OX, EX, CX` are bound by the arm's pattern
(`Eval.scala:45`), so this is a construction and not a cast.

#### (d) and (e) The two settled-clause continue arms, `Eval.scala:100-101` and `:146-147`

```scala
                                                        val k = stack.dump(pos)
                                                        loop(Effect.defer(r._1, k))    // was loop(r._1.map(k))
```
```scala
                                                        val k = stack.dump(pos)
                                                        loop(Effect.defer(r._2, k))    // was loop(r._2.map(k))
```

**Why:** `map` on a pending value builds `Effect.defer(kyo, arrow, next)` where `arrow` is a fresh anonymous
`Transform` closing over `k` (`Pending.scala:30-37`), so today the interior's finalizers sit inside a closure for the
whole evaluation of the answer, which is arbitrary user code. `Effect.defer(r._1, k)` is the same value with `k` as
the node's `contA`, so the drive flattens it into entries before the answer runs and `Finalize.scan` can see them. It
is also one node and one anonymous class fewer per region continue.

#### (f) and (g) The two suspended-clause dispatchers, `Eval.scala:77-94` and `:120-139`

```scala
                                            new Kyo.Defer[Loop.Outcome[OX[CX] < (EX & S), BX], BX, BX, EX & S]
                                                with Transform[Loop.Outcome[OX[CX] < (EX & S), BX], BX, EX & S]
                                                with Captured:
                                                def frame    = Frame.internal
                                                def value    = clause
                                                def contA    = this
                                                def contB    = Arrow.id[BX]
                                                def captured = k
                                                override def apply(o: Loop.Outcome[OX[CX] < (EX & S), BX]) =
                                                    o match
                                                        case r: Loop.Continue[OX[CX] < (EX & S)] @unchecked =>
                                                            Effect.defer(r._1, k, h)
                                                        case v =>
                                                            // the region is done and the interior is discarded
                                                            Finalize.unwind(k, null)
                                                            v.asInstanceOf[BX]
```

The `HandlerLoopState` dispatcher takes the same two changes, with `Effect.defer(r._2, k, HandlerLoopState(h, r._1))`
at `:129` and the same `case v` arm at `:130`.

**Why, `Captured`:** `k` is dumped at `:74` / `:117` and the handler popped at `:75` / `:118`, then the clause is
driven. For that whole window the interior's finalizers exist only in this closure. The dispatcher is a stack entry
(`contA = this`), so exposing `captured` is what makes them reachable to a park taken or a throw raised in that
window.

**Why, the `case v` arm:** this is a region-discard site the previous document did not have. On `Loop.done` from a
*suspended* clause the interior `k` is dropped and never applied, so its releases run here or not at all.

**Why, the three-argument `defer`:** `Effect.defer(v, a, b)` (`Effect.scala:32-39`) makes the drive push `h`, flatten
`k` above it, then loop the answer. That is the same stack the two-node form reaches one turn later, minus a node and
a closure, and with the finalizers structural for the whole answer.

#### (h) The two settled-clause done arms, `Eval.scala:103-105` and `:150-152`

```scala
                                            case _ =>
                                                Finalize.unwind(stack, pos + 1, null)
                                                stack.truncate(pos + 1)
                                                loop(o)
```

**Why:** `truncate` drops the handler at `pos` and the whole interior below it. The interior is abandoned, so its
resources are released rather than dropped. Row `Any` makes it unconditionally correct that these releases run after
the region's handler is gone: the release could not have named that region's effect in the first place.

#### (i) The `handleCont` fold, `Eval.scala:57-64`

```scala
                                val k = stack.dump[OX[CX], AX, EX & S](pos)
                                val next =
                                    try h.run(kyo.input, k(_))
                                    catch
                                        case ex: Throwable =>
                                            // the interior left the stack inside `k`. A throw out of the clause
                                            // means nobody will apply it, so its releases run here or not at all
                                            Finalize.unwind(k, ex)
                                            EffectTrace.attach(ex, kyo, k, stack)
                                            throw ex
                                loop(next)
```

**Why:** `k` becomes the `O[X] => A < (E & S)` the clause receives as a plain function (`Handler.scala:29`). There is
no structure to expose, because the clause owns it. If the clause applies `k` and returns, `k(o)` is
`Arrow.Chain.apply(v) = Effect.defer(v, a, b)` (`Arrow.scala:91-92`) and the drive pushes the finalizers back; if it
throws, this catch runs them. See section 5 for what stays uncovered.

#### (j) The unnest move, `Eval.scala:160-201`

```scala
                case _ =>
                    if !stack.isEmpty then
                        val s = stack.state[StateX](0)
                        stack.pop() match
                            case h: HandlerLoopState[IX, OX, EX, AX, BX, S, StateX] @unchecked =>
                                val next =
                                    try h.apply(s.getOrElse(h.initialState), curr.unsafeGet.asInstanceOf[AX])
                                    ...
                            ...
                    else curr
```

`val r = curr.unsafeGet` at `:161` goes; its two readers were the `HandlerLoopState` delivery at `:167` and the
empty-stack return at `:201`. The delivery keeps the unnest; the return hands back the union.

**Why:** `partial` must return the pending form. Its consumers prove it: `EvalTest.scala:993` asserts
`parked.evalNow == Maybe.Absent`, and `evalNow` (`Pending.scala:292-295`) performs the unnest itself. A drive that
already unnested would hand a once-unnested value into a `<`-typed position and let the next reader unnest it again;
when `A` is itself a computation, that reads the user's data as a node. This is the prior shape,
`Nested.unnest[A](loop(...))` at `1cf05637e8:20` with `partial` at `:33` returning the union.

Side effect worth naming: one type test leaves every settled turn whose head is not a `HandlerLoopState`, the hottest
arm in the drive. A shape claim, unmeasured (section 8).

**Lift, whole file:** no new summon. `Eval.scala` already summons at `:93` and `:138`
(`next(apply(o.unsafeGet), Arrow.id)`, a raw `BX` into a `BX < S2` slot; `BX` is an abstract type member at `:30`, so
`CanLift.derived` resolves and no macro expands). None of the new code writes a bare value into a `<` position.

---

### 3.6 `kyo/kernel/Effect.scala`: `bracket`

Replaces the commented signature at `:75`.

```scala
    /** Acquires a resource, uses it, and releases it. If `acquire` completes, `release` runs exactly once, on every
      * path: `use` returning, `use` throwing, the region around the bracket being discarded, and the computation
      * being parked and then abandoned through `finalizeResources`.
      *
      * `release` is at row `Any` and is told nothing about how `use` ended. The row is what lets it run with no
      * drive and no handlers installed, which is the abandonment path's situation. Outcome detection is
      * position-dependent here: a failure that settles through the bracket is a value, while the same failure under
      * a region that answers it is a `Loop.done` discarding the interior with nothing to inspect, so a parameter
      * right for one of those looks total and is not. A caller that needs the outcome captures it inside `use`.
      *
      * A continuation captured below the release and applied more than once releases on the first shot and runs the
      * later shots against the released resource. A `handleCont` clause that drops its continuation never releases:
      * it holds the only reference, as a function. Lifetimes that must not depend on continuation usage belong to
      * `Scope`.
      */
    def bracket[A, B, S](acquire: A < S)(release: A => Unit < Any)(use: A => B < S)(using Frame): B < S =
        val a = node(acquire)
        val r = Arrow(release)
        val u = Arrow(use)
        new Kyo.Bracket[A, B, B, S]:
            def acquire = a
            def release = r
            def use     = u
            def cont    = Arrow.id[B]
    end bracket

    // `Kyo.Bracket.acquire` is a node, so a settled acquire is wrapped, the same way `Kyo.Handle.value` forces
    // `ArrowEffect.handleCont` to split on the settled case (`ArrowEffect.scala:59-70`)
    private def node[A, S](v: A < S): Kyo[A, S] =
        v match
            case k: Kyo[A, S] @unchecked => k
            case _ =>
                new Kyo.Defer[A, A, A, S]:
                    def value = v
                    def contA = Arrow.id[A]
                    def contB = Arrow.id[A]
```

**Why:** the public surface, and the only place a `Kyo.Bracket` is built.

**Non-inline, and this is a decision the owner should confirm** (section 9). The prior art was
`private[kyo] inline def bracket` (`3a95636fa8:Effect.scala:56`, `:88`). An inline `bracket` would expand
`new Kyo.Bracket` at every call site, and a call site outside package `kyo` cannot select it, the same failure the
unqualified `Arrow.Transform` imports were added for (`Pending.scala:24-29`). Making it inline therefore *requires*
`Kyo.Bracket` to be nameable from an expansion site outside package `kyo`. Non-inline avoids that question entirely
and mints the two arrows once, inside this file; the cost is that `use` and `release` are called through a `Function1`
rather than fused into `override def apply(v: A) = f(v)` (`Arrow.scala:37`).

**`release: A => Unit < Any` rather than `A => Unit`** makes the row-`Any` concession compiler-checked at the surface:
`<` is contravariant in `S`, so an effectful release does not conform. A pure `a => close(a)` still passes through
`Implicits.liftPureFunction1` (`Implicits.scala:21-24`) at the caller's site.

**Lift:** none in this file. Every `<`-typed expression here is either an existing `A < S` parameter or a `Kyo` node
reaching its slot through `<.fromKyo`. This matters more here than elsewhere: `Effect.scala` is an inlined-from file
(`deferInline`, `:20-24`), and a new macro-summoning position in it would deepen the suspension cascade the skill
describes. The `CanLift[Unit]` summon that `A => Unit` would have introduced lands at the caller instead.

---

### 3.7 `kyo/kernel/Pending.scala`: `finalizeResources`

A second, non-inline extension block. The existing one is `extension [A, S](inline self: A < S)` (`Pending.scala:19`)
and every member of it is inline (`:22`, `:51`, `:73`, `:95`, `:117`, `:289`, `:292`, `:298`); an `inline` parameter is
legal only on an `inline` method, so a non-inline `def` cannot join it.

```scala
    extension [A, S](self: A < S)
        /** The releases this value still owes, innermost bracket first, as a value the caller drives.
          *
          * Runtime machinery, not user surface. A caller holding a value has no way to know whether it is a park,
          * and calling this on a live computation spends its releases. The scheduler calls it when it drops a
          * continuation it will never resume.
          */
        private[kyo] def finalizeResources: Unit < Any = Finalize.compose(self)
    end extension
```

**Why:** this is the abandonment path's entry point, and the reason `Kyo.Park` carries `owed`.

`A < S` conforms to `Any < Nothing` by variance (`<[+A, -S]`, `Pending.scala:14`), so the call needs no cast.

**Lift:** none. `Finalize.compose` returns `Unit < Any` and its lift is inside `Finalize.scala`, not here.

---

## 4. The guarantee, path by path

**If `acquire` completes, `release` runs exactly once, on every path.** One `Finalizer` object per completed acquire,
one CAS, one mint site.

| path | mechanism | code |
|---|---|---|
| normal completion of `use` | `Ensure.contB` puts the finalizer above `cont`, so the settled arm pops and applies it when `use`'s value settles | `KyoInternal.scala` `Ensure.contB`; `Eval.scala:191-199`; `Finalizer.apply` |
| a throw from `use` | `use` is applied by the drive with the finalizer already an entry; the throw reaches the drive's boundary, which unwinds the stack before `Stack.release` clears it | `Eval.scala:192-198`; `drive`'s catch (3.5a) |
| a throw from `use` with a `dump()`-sized run of entries above the finalizer | `boundary` stops the bounded fold at a `Barrier`, so the finalizer never leaves the stack | `Stack.scala:129-132` with `Barrier` |
| a throw from a `handleCont` clause holding the interior | the fold is a local the clause owns; the site's own catch runs what it owed | `Eval.scala:57-64` (3.5i) |
| `Loop.done` truncating a region that contains the bracket, settled clause | the interior is walked and run before `truncate` | `Eval.scala:103-105`, `:150-152` (3.5h) |
| `Loop.done` from a suspended clause | the interior `k` is dropped by the dispatcher, which runs it first | `Eval.scala:87`, `:130` (3.5f) |
| park between `acquire` and `release`, later resumed | `reify` folds the finalizer into `Park.cont`; the resume pushes the fold back and `Stack.push` flattens it into entries, so the normal path applies. `owed` is not consulted | `Eval.reify` (3.5b); `Stack.scala:33-45`, `:53-64` |
| park between `acquire` and `release`, abandoned to `finalizeResources` | `Park.owed` was collected from the same entries `reify` folded; the walk reads the field and does not descend `cont` | `Finalize.owed`, `Walk`'s `Park` arm, `Finalize.compose` |
| a continuation captured inside the bracket applied twice | both shots hold the same `Finalizer`; the CAS gives one release and `apply` still runs `next` on the second. The second shot observes a released resource, which is documented, not fixed | `Finalizer.run`, `Finalizer.apply` |
| a continuation captured above the acquire point applied twice | the fold holds the `Bracket` node in arrow position, so each shot re-enters `Bracket.apply`, acquires its own resource and mints its own `Finalizer`. Per-shot acquire, per-shot release | `Kyo.Bracket.apply` settled arm |
| a nested drive | `Finalizer.run` enters `Eval.settle`, which borrows its own `Stack` from the thread pool (`Stack.borrow`) and saves/restores the safepoint state. The outer drive's stack and its finalizers are untouched | `Finalizer.run`; `Eval.settle` (3.5a); `Stack.scala:172-199` |
| nested brackets, innermost first | stack order: the outer finalizer is pushed first and sits deeper, so it is popped last. On every recovery path the walk drains entry 0 upward, which is the same order | `Stack.scala:33-45`; `Finalize.scan` |

**The path that does not hold**, reported rather than hidden: a `handleCont` clause that receives the interior fold
and neither applies it nor throws. Two shapes reach it. The clause drops `k`, or the clause embeds `k` in a closure
inside the value it returns (`something.map(x => k(x))`), which no structural walk can see and which the drive no
longer owns. In both, the resource is never released. This is inherent to a first-class continuation delivered as a
function (`Handler.scala:29`): the clause holds the only reference, and it holds it in a form that cannot be
finalized. It is stated in `Effect.bracket`'s scaladoc and pinned by test 26 as a specification. The correct home for
a lifetime that must not depend on continuation usage is `Scope`, whose finalizers live in a queue owned by
`Scope.run` (`kyo-core/shared/src/main/scala/kyo/Scope.scala:157-191`) rather than in the continuation.

---

## 5. One property `Stack` must keep, unchanged but now load-bearing

**Whole-stack `dump(size)` followed by `push` restores the same entry sequence.** A resumed park depends on it: if one
entry splits into two, `Stack.find` (`Stack.scala:89-98`) returns a position one off and a region answers the wrong
operation, or `Stack.handler(pos)` (`:81-82`) reads a non-handler and its cast fails.

The mechanism is `dump`'s `wrap` guard, `if wrap && !handlers && !(c.b eq Arrow.Id)` (`Stack.scala:116`), against
`push`'s `count`/`fill` descending chains (`:47-64`). `handlers` turns true as soon as a `Handler` is folded (`:122`)
and the fold runs outermost to innermost, so for a whole-stack fold that guard is inactive above the outermost
handler. It does not break, because the arm that wraps a chain-valued entry is the separate one at `:120`, which is
not gated on `handlers`. That is an argument, not a proof, so test 34 pins it with a `Chain` entry both above and
below a handler.

Resources are not at risk either way: `owed` holds the finalizer objects themselves, and a re-split entry is still
the same object.

---

## 6. Casts introduced

| # | site | cast | category |
|---|---|---|---|
| 1 | `Eval.partial` | `v.asInstanceOf[Any < Nothing]` in, `.asInstanceOf[A < S]` out | erasure-forced: the drive's currency sits at the top of the row lattice and `Any < Nothing` does not conform back to `A < S`. The pair is the prior implementation's, `1cf05637e8:33` |

That is the whole list. `Eval.apply`'s in and out casts are today's (`Eval.scala:208`), moved but not changed.

Checked and found unnecessary, so a later reader does not add them back:

- `Eval.reify` (section 3.5b): `Kyo.Park[Any, Any, Nothing]` types every field at the drive's own currency.
- `Kyo.Bracket.apply`: `v.unsafeGet` is the existing settled reader (`Pending.scala:298-301`), and the row arithmetic
  is contravariance, `A < S2 <: A < (S & S2)` and `Arrow[A, B, S] <: Arrow[A, B, S & S2]`.
- `Finalizer.apply`: `Arrow.Transform[B, B, Any]` requires `C < (Any & S2)`, which is `C < S2`, and
  `Arrow.Id.apply(v, Arrow.id)` short-circuits to `v` (`Arrow.scala:130-134`).
- `<.finalizeResources`: `A < S` conforms to `Any < Nothing` by variance.
- The bare-operation rebuild at `pos < 0`: `IX, OX, EX, CX` are bound by the arm's pattern (`Eval.scala:45`).
- `Effect.defer(r._1, k, h)`: `Arrow[AX, BX, S] <: Arrow[AX, BX, EX & S]`.
- The whole `Walk`: every arm binds through a typed pattern, which is step 2 of the ladder, so the runtime test is
  identical to a cast's and the claim is visible.

**Verify at implementation time, one item:** `Eval.apply`'s existing `v.asInstanceOf[Any < Nothing]` looks like it
should conform by variance (`<[+A, -S]` keeps its annotations outside the companion). If it does, both `apply` and
`partial` lose their in-cast. The design does not assume it; removing a cast is verified by the compiler, never
argued.

---

## 7. Concessions

| concession | justification | minimal scope | protection | pinning test |
|---|---|---|---|---|
| `release` is at row `Any`, so a finalizer cannot perform effects | it must run with no drive, no handlers and no row obligations, which is exactly the abandonment path's situation | one field's row; every other row in the node is `S` | type-level: `<` is contravariant in `S`, so an effectful release does not conform to `A => Unit < Any` and does not compile | 21, 23, plus a compile-failure case |
| the three node types subclass `Kyo.Defer` | their drive semantics are `Defer`'s exactly, and a separate arm would add a type test to the hottest dispatch in the kernel | `value`/`contA`/`contB` are `final` on all three, so a subclass cannot change how it is driven | `Finalize` matches `Ensure` and `Park` before `Defer`, so the typed arms cannot be shadowed | 4, 22, 30 |
| `Finalizer` runs its release eagerly inside `apply`, entering a nested drive when the release built a node | deferring opens a window between the CAS flip and the drive reaching the release, in which a park produces a value with a spent guard and unrun work | one method, `Finalizer.run` | the nested drive is entered only for a release built out of `Effect.defer`; an ordinary side-effecting release has already run when `release(resource)` returns (`Arrow.scala:37`) | 6, 21; the cold-drive frequency needs a number (8) |
| `Finalizer` and `Handler` are `Barrier`s, so `dump()` stops folding at them | a folded finalizer is reachable from nothing when the arrow that received the fold throws | one condition, `Stack.scala:130` | `Stack.find` still tests `Handler` by tag, so dispatch is untouched | 8, 33; the equal-cost claim is a hypothesis (8) |
| the drive's clause dispatchers expose their fold through `Captured` | otherwise the fold is a closure capture, invisible to both recovery paths, for the whole evaluation of a clause | one member on two anonymous classes that already exist | the invariant is grepable: every `dump(` in `Eval.scala` either pushes the fold back, hands it to a value that owns it, exposes it through `Captured`, or is followed by a `Finalize.unwind` on the same local | 10, 11 |
| `Kyo.Park` carries `owed`, a second description of what the fold holds | walking the fold instead would make the abandonment path depend on `dump`'s `wrap` and `Chain` shape, which is the dependency the review's B3 punished | one field, written at one site | `reify` collects it in the same pass over the same entries it folds, so drift is impossible by construction; the walk deliberately does not descend `Park.cont` | 24, 25, 28 |
| the drive polls a `stop` at the loop head | partial evaluation needs a park point and every arm must be covered | one reference comparison, `null` under `Eval.apply` | `reify` is a separate method, so nothing cold enters `loop`'s bytecode budget | 15; the poll cost needs numbers (8) |
| `driveTo` is a second, non-inline expansion of the drive inside `Eval.scala` | `partial` and `settle` must not expand a 1500-byte loop at their call sites, and `Eval.apply` stays `inline` | one private method; `apply`'s expansion is byte-unchanged | its frames carry `kyo.kernel.internal.Eval$`, which `EffectTrace.isPlumbing` already filters (`EffectTrace.scala:127`) | 16, 6 |
| the `handleCont` fold is recovered from a `catch`, not from structure | the clause receives a plain function; there is nothing to expose | one `catch` that already exists (`Eval.scala:59-63`) | the uncovered shape is named in the scaladoc and pinned as a specification | 9, 26 |
| a capture taken below the finalizer, applied twice, observes a released resource on the second shot | closing it needs a re-acquire the captured range does not contain | documented, not mechanised | the second `run()` is the CPS kernel's skipped-body shape (`kyo-kernel/.../Safepoint.scala:148-149`), so the shot completes rather than failing unpredictably | 27, which asserts the hazard |

---

## 8. What is not measured

**No number in this document is measured.** Every performance statement is a shape argument, which is a hypothesis.
The rows the change reaches, and therefore the rows that are mandatory on both variants, same session, back to back,
`-f 1` to screen and `-f 3` to confirm anything outside the drift band, in a throwaway worktree:

- **The poll at the loop head** touches the loop head, which makes every row in the class mandatory.
- **The three node types subclassing `Defer`** changes the class hierarchy the hottest arm's type test walks. Every
  row.
- **The two region-continue substitutions** (`:100-101`, `:146-147`) and the two three-argument `defer`s (`:86`,
  `:129`) touch region rebuild and the `HandleLoopState` arms: the emitting row and the stateful row. A small win is
  expected, one node and one anonymous class fewer per region continue, and an expectation is not evidence.
- **The `Barrier` test in `dump()`'s boundary** replaces a test against a `sealed abstract class` with one against a
  trait, which is an interface check.
- **The unnest move** (3.5j) removes a type test from the settled arm.
- **`Finalize.unwind` on the region-discard path** adds a scan of `pos + 1` entries where there was none. The
  `interesting` pre-test keeps it allocation-free when no bracket is live, and the pre-test itself is per-slot on a
  loop `truncate` already runs (`Stack.scala:135-143`).
- **The nested-drive frequency in `Finalizer.run`** under a realistic `Sync.ensure` shape, which is what the
  concession table calls cold.

**Two behaviors change that are not performance.** A spliced trace taken inside a region continue gains the interior's
real user frames, because the entries change from one anonymous arrow whose `frame` is `Frame.internal` (which
`EffectTrace.Builder.frame` skips, `:190`) to the interior's own arrows. And a trace crossing a live bracket gains the
use site and the release site, because `Bracket` and `Finalizer` are `Arrow.Transform`s and reach
`case a: Arrow[?, ?, ?] => frame(a.frame)` (`EffectTrace.scala:289-290`). Both are improvements and both are
user-visible.

`EffectTrace.Builder.drain` does not descend `Captured.captured`, so a fold held by a dispatcher stays invisible to a
trace taken in that window. That would improve trace quality, has no bearing on resource correctness, and belongs to
whoever owns `EffectTrace`.

---

## 9. Pinning tests

Placement follows the module's rule that a test file shares a prefix with its source: `Effect.bracket` in
`EffectTest.scala`, `Eval.partial` and the park in `EvalTest.scala`, the walk in a new `FinalizeTest.scala`, stack
behavior in `StackTest.scala`, region interactions in `ArrowEffectTest.scala`. Each entry states what breaks if the
property it names fails.

**Bracket, normal path (`EffectTest.scala`)**

1. acquire, use, release in that order, each exactly once, on a settled acquire. *Breaks if:* `Ensure.contB` puts the
   finalizer below `cont` and the release runs after downstream code.
2. the same on a pending acquire that suspends on an operation a region answers. *Breaks if:* `Bracket.apply`'s
   pending arm loses `this` and the mint never happens.
3. `bracket(...)(...)(...).map(f)` records the release before `f`. *Breaks if:* composition ever fuses into the use
   chain, which is the failure `3a95636fa8` needed a region-exit crossing for.
4. release runs before an effectful downstream, under a `handleCont` region and under a `handleLoopState` region, and
   the state the handler reached at the boundary is the state the downstream sees. *Breaks if:* the finalizer entry
   lands on the wrong side of a region crossing.
5. nested brackets release innermost first. *Breaks if:* the finalizer is pushed below rather than above `cont`.
6. a release built out of `Effect.defer` runs to completion. *Breaks if:* `Finalizer.run`'s `case k: Kyo` arm or
   `Eval.settle` is missing. The only test that exercises the nested drive.

**Bracket, abnormal paths (`EffectTest.scala`)**

7. a throw inside `use` after it suspended releases, then propagates the original exception with the release's own
   failure, if any, suppressed onto it. *Breaks if:* `Finalize.unwind` is not called before `Stack.release` clears the
   slots.
8. a throw inside `use` with a `dump()`-sized run of entries above the finalizer still releases. *Breaks if:*
   `Barrier` is not wired into `boundary` and the finalizer left the stack inside the fold at `Eval.scala:174`,
   `:183` or `:192`.
9. a throw out of a `handleCont` clause, with a bracket acquired inside the region, releases. *Breaks if:* the
   `Finalize.unwind(k, ex)` call is missing.
10. a throw during the evaluation of the answer on a region continue, with a bracket acquired inside the region,
    releases. *Breaks if:* `:100-101` or `:146-147` still uses `map` and the finalizer is inside the closure.
11. a park taken during the evaluation of a suspended `handleLoop` clause, then abandoned, releases. *Breaks if:* the
    dispatcher does not implement `Captured` or the walk has no `Captured` arm.
12. a `use` that throws synchronously, before it suspends, releases. *Breaks if:* `Ensure` is replaced by an
    `Effect.defer` whose value argument is `use(a)`, which evaluates before the finalizer exists.
13. 100k sequential brackets in one drive complete without a stack overflow. *Breaks if:* `Bracket.apply` ever fuses
    instead of returning a node.
14. a `Loop.done` discarding a region containing a bracket releases it and the answer still flows, from a settled
    clause and from a suspended clause, two cases. *Breaks if:* `Eval.scala:104` / `:151` truncates without the
    `Finalize.unwind` call, or the dispatcher's `case v` arm drops `k` without running it.

**Park and partial (`EvalTest.scala`)**

15. the six restored cases at `EvalTest.scala:949-1024`: a preemption stop reifies and resumes with handler state
    (`:956-970`), the `stop` function ends the slice (`:972-981`), `partial` completes when nothing stops
    (`:983-985`), an unhandled operation parks for a handler installed later (`:987-997`) and with the regions above
    it intact (`:999-1015`), and a stop between slices short-circuits by identity (`:1017-1023`, the assertion at
    `:1021`).
16. `SafepointConcurrencyTest.scala:81-106` restored, the `Eval.partial` call at `:92`. It is a stop-protocol park,
    not a budget park: `Safepoint.enter` is called from the strict arms of the composition sites, not the drive, and
    an exhausted budget produces one more `Kyo.Defer` for the drive to consume rather than a park. The direct evidence
    is that today's drive contains no poll of any kind (`Eval.scala:36-220`).
17. an unhandled `suspendWith` operation parks and is performed exactly once by the resumed computation. *Breaks if:*
    the `pos < 0` branch reifies `kyo` rather than a bare operation and the effect runs twice. Required separately
    from 15 because `ArrowEffect.suspend`'s `cont` is `Arrow.id[O[C]]` (`ArrowEffect.scala:27`) which `Stack.push`
    drops (`Stack.scala:35`), making the two reifications indistinguishable there; `suspendWith`'s `cont = this`
    (`ArrowEffect.scala:42`) is the shape that bites, and `d4e59ffa56` records four red tests all with `suspendWith`.
18. the two commented `ArrowEffectTest` cases: a handler installed after a partial evaluation answers the parked
    operation (`ArrowEffectTest.scala:446-451`), and a parked stateful region resumes with its state and `done`
    (`:590-600`). The third consumer, `:1008-1015`, is inside the block comment at `:879-1054` and is blocked on
    `handleFirst` as well, so it is not restored here.
19. a parked value evaluated on a different thread completes, and evaluated twice produces the same answer both
    times. *Breaks if:* `reify` hands out something that is not a complete value, or the park retained a `Stack`.
20. a park whose result type is itself a computation (`A = Int < Any`), driven through `partial` twice and then
    settled. *Breaks if:* the unnest move (3.5j) goes the other way and the payload is unnested twice and read as a
    node. The corpus has nothing like it.

**Resources across parks and captures (`FinalizeTest.scala`)**

21. parked mid-`use`, then resumed: releases once. *Breaks if:* a second mint site exists.
22. the same value, not resumed: `finalizeResources` releases it. *Breaks if:* `Park.owed` is empty or the walk skips
    the `Park` arm.
23. `finalizeResources` on a park taken from inside two regions releases without reaching `bug`. *Breaks if:* the
    release row is not `Any`. The row-`Any` pin.
24. a park whose fold contains a `Captured` dispatcher whose capture contains a finalizer, abandoned: releases.
    *Breaks if:* `Finalize.scan` does not descend `Captured`, which is the case `owed` exists to make cheap.
25. `finalizeResources` driven twice releases once, and a resume on one thread raced against `finalizeResources` on
    another releases exactly once, looped until the race is reliable.
26. a `handleCont` clause that drops its continuation does not release. Named so it reads as the specification it is.
27. a `handleCont` clause calling its continuation twice releases exactly once, both shots complete, **and the second
    shot observes the resource in its released state**. Asserting only that both shots complete would pass while the
    hazard is present.
28. a capture taken while `acquire` is still suspended, applied twice, acquires and releases once per shot. *Breaks
    if:* the walk regains the ability to mint, or `Bracket.apply`'s settled arm becomes idempotent.
29. a park holding an unconsumed `Bracket` releases nothing, and resuming it acquires and releases exactly once.
30. nested brackets in a park release innermost first; park, resume, park again, abandon releases once.
31. a throwing release during abandonment: the rest still run and the failures aggregate onto the first.
32. `finalizeResources` on a park taken over 100k trailing maps completes. *Breaks if:* the walk recurses instead of
    using a worklist.

**Stack (`StackTest.scala`)**

33. `dump()` stops at a `Finalizer` the way it stops at a `Handler`, and the finalizer is still on the stack
    afterwards. *Breaks if:* `boundary` still tests `Handler`.
34. whole-stack `dump(size)` followed by `push` restores the entries in order, including a `Chain` entry both above
    and below a handler, and including a `HandlerLoopState` at its live state. *Breaks if:* section 5's `wrap`
    interaction does not hold; the failure is described there.

---

## 10. Decisions that need the owner

1. **Is `Effect.bracket` `inline`?** This design ships it non-inline. The prior art was
   `private[kyo] inline def bracket` (`3a95636fa8:Effect.scala:56`, `:88`). Inline is not a free choice here: the
   expansion mints `new Kyo.Bracket` at the call site, so an expansion site outside package `kyo` must be able to
   name `Kyo.Bracket`, which is the same constraint the unqualified `Arrow.Transform` imports were added for
   (`Pending.scala:24-29`). If the answer is inline, that nameability is a requirement on `KyoInternal.scala`, not an
   implementation detail.
2. **`finalizeResources` visibility.** This design ships `private[kyo]`, matching the prior art's
   `private[kyo] def finalizeBracket` and its comment "runtime machinery, not user surface"
   (`3a95636fa8:kyo-kernel2/shared/src/main/scala/kyo/kernel/Pending.scala:38`). The brief called it "the public
   extension on the pending type", which would be a different answer.
