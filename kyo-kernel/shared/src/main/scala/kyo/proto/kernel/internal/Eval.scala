package kyo.proto.kernel.internal

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.bug
import kyo.discard
import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.Loop.Outcome2
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect
import language.implicitConversions
import scala.annotation.publicInBinary
import scala.annotation.tailrec
import scala.util.control.NonFatal

@publicInBinary private[kyo] object Eval:

    /** Runs the releases a computation still owes, for a holder giving up on resuming it: the abandonment signal reaches every open
      * region, innermost first, and what each region owes drains before the region itself. Open regions are found by inspecting the
      * value spine; arrows are never traversed. Anything that never acquired, or is not a computation at all, owes nothing. A release
      * that throws is suppressed onto the signal and cannot starve the ones after it.
      */
    def release[A, S](v: A < S, ex: Throwable): Unit =
        val collected = scala.collection.mutable.ArrayBuffer.empty[AnyRef]
        @tailrec def collect(v: Any): Unit =
            v match
                case p: Pending[?, ?] =>
                    p match
                        case kyo: Kyo.Defer[?, ?, ?, ?] =>
                            collect(kyo.value)
                        case kyo: Kyo.Handle[?, ?, ?, ?, ?, ?] =>
                            kyo.handler match
                                case hc: Handler.ContextHandler[?, ?, ?, ?] =>
                                    collected += hc
                                    collected += kyo.state.asInstanceOf[AnyRef]
                                case _ => ()
                            end match
                            collect(kyo.value)
                        case kyo: Kyo.Park[?, ?] =>
                            expandOwed(collected, kyo.owed)
                            val entries = kyo.entries
                            var i       = 0
                            while i < entries.regions do
                                entries.handler(i) match
                                    case hc: Handler.ContextHandler[?, ?, ?, ?] =>
                                        collected += hc
                                        collected += entries.state(i).asInstanceOf[AnyRef]
                                    case _ => ()
                                end match
                                expandOwed(collected, entries.owed(i))
                                i += 1
                            end while
                            collect(kyo.value)
                        case _: Kyo.Suspend[?, ?, ?, ?] => ()
                        case _: Kyo.Snapshot[?, ?]      => ()
                case _ => ()
        collect(v)
        releaseCollected(collected, ex)
    end release

    // Expands owed snapshots into the collect buffer as the [handler, state] pairs the
    // walk itself collects. Append order is oldest snapshot and outermost region first,
    // each region before what it owes, so the reversed walk in releaseCollected drains
    // newest and innermost first, obligations before their owner.
    private def expandOwed(collected: scala.collection.mutable.ArrayBuffer[AnyRef], owed: Chunk[Stack.Snapshot]): Unit =
        if !owed.isEmpty then
            val snapshots = owed.toIndexed
            var j         = 0
            while j < snapshots.length do
                val snapshot = snapshots(j)
                var i        = 0
                while i < snapshot.regions do
                    snapshot.handler(i) match
                        case hc: Handler.ContextHandler[?, ?, ?, ?] =>
                            collected += hc
                            collected += snapshot.state(i).asInstanceOf[AnyRef]
                        case _ => ()
                    end match
                    expandOwed(collected, snapshot.owed(i))
                    i += 1
                end while
                j += 1
            end while
    end expandOwed

    private def releaseCollected(collected: scala.collection.mutable.ArrayBuffer[AnyRef], ex: Throwable): Unit =
        var i = collected.length - 2
        while i >= 0 do
            released(collected(i).asInstanceOf[Handler.ContextHandler[?, ?, ?, ?]], collected(i + 1), ex)
            i -= 2
        end while
    end releaseCollected

    // Drains what an exiting entry owes: dumps nobody resumed, abandoned with the exit's
    // signal.
    private def drainOwed(owed: Chunk[Stack.Snapshot], ex: Throwable): Unit =
        val collected = scala.collection.mutable.ArrayBuffer.empty[AnyRef]
        expandOwed(collected, owed)
        releaseCollected(collected, ex)
    end drainOwed

    // The dump for a crossing, with its debug trace out of the eval's hot body.
    private def dumped(stack: Stack, idx: Int, kyo: Kyo.Suspend[?, ?, ?, ?]): Stack.Snapshot =
        val entries = stack.dump(idx + 1)
        Debugger.whenEnabled {
            var i = entries.regions - 1
            while i >= 0 do
                Debugger.onRegionExit(entries.handler(i), kyo)
                i -= 1
        }
        entries
    end dumped

    // The discard drain behind its own empty-check, so an exit site is one call and the
    // signal is minted only when something drains, per drain so suppressed release
    // failures attach to their own signal.
    private def drainDiscarded(owed: Chunk[Stack.Snapshot]): Unit =
        if !owed.isEmpty then drainOwed(owed, new kyo.KyoException("remainder discarded")(using Frame.internal))

    // The context rebound past a dumped run: each dumped binding's tag rebinds to the
    // nearest live entry below, or leaves the context entirely, exactly as the settled
    // pop rebinds one exiting binding.
    private def rebound(stack: Stack, entries: Stack.Snapshot, ctx: Context): Context =
        var c = ctx
        var i = 0
        while i < entries.regions do
            entries.handler(i) match
                case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                    val j = stack.find(hc.tag)
                    c = if j < 0 then c.remove(hc.tag) else c.update(hc.tag, stack.state(j).asInstanceOf[VX])
                case _ => ()
            end match
            i += 1
        end while
        c
    end rebound

    private def released(handler: Handler.ContextHandler[?, ?, ?, ?], state: Any, ex: Throwable): Unit =
        Debugger.onRelease(handler, ex)
        // Erasure-forced at the storage boundary: the handler and its state travel in
        // parallel slots, and the type system cannot carry their pairing.
        try handler.asInstanceOf[Handler.ContextHandler[Any, ContextEffect[Any], Any, Any]].release(state, ex)
        catch
            // A release rethrowing the exception it was told about must not self-suppress.
            case t if NonFatal(t) && (t ne ex) => ex.addSuppressed(t)
            case t if NonFatal(t)              => ()
        end try
    end released

    def apply[A, S](v: A < S): A < S = apply(v, armed = false)

    def partial[A](v: A < Any): A < Any =
        val slot = Safepoint.get()
        if Safepoint.consumeStopped(slot) then v
        else
            try apply(v, armed = true)
            finally discard(Safepoint.consumeStopped(slot))
        end if
    end partial

    private def apply[A, S](v: A < S, armed: Boolean): A < S =

        val stack = Stack.borrow()

        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        if armed then Safepoint.arm(slot)

        def park[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2]): A < S =
            val parked: Any < Any =
                if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then v.asInstanceOf[Any < Any]
                else Effect.defer(v, contA, contB).asInstanceOf[Any < Any]
            val owedNow = stack.takeEvalOwed()
            if stack.isEmpty then
                if owedNow.isEmpty then parked.asInstanceOf[A < S]
                else Kyo.Park[A, S](parked, Stack.Snapshot.empty, owedNow)
            else
                Debugger.whenEnabled {
                    var j = stack.depth - 1
                    while j >= 0 do
                        Debugger.onRegionExit(stack.handler(j), parked)
                        j -= 1
                }
                Kyo.Park[A, S](parked, stack.snapshot(), owedNow)
            end if
        end park

        @tailrec def loop[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2], ctx: Context): A < S =
            Debugger.onLoop(v, contA, contB)
            v match
                case kyo: Kyo.Defer[?, ?, T, S2] @unchecked =>
                    if armed && Safepoint.stopped(slot) then park(v, contA, contB)
                    else
                        loop(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)), ctx)

                case kyo: Kyo.Suspend[?, ?, T, S2] @unchecked =>
                    kyo match
                        case kyo: Kyo.SuspendContext[VX, CX, T, S2] @unchecked =>
                            val state = ctx.get(kyo.tag).orElse(kyo.default).getOrElse(bug(s"unhandled suspension: $kyo"))
                            Debugger.onContext(kyo, ctx)
                            loop(kyo.cont(state, contA.chain(contB)), Arrow.id, Arrow.id, ctx)

                        case kyo: Kyo.SuspendArrow[IX, OX, EX, VX, T, EX & S2] @unchecked =>
                            val idx = stack.find(kyo.tag)
                            if idx < 0 then bug(s"unhandled suspension: $kyo")
                            else if idx == stack.depth - 1 then
                                Debugger.onHandle(kyo, stack.handler(idx), stack.state(idx))
                                stack.handler(idx) match
                                    case handler: Handler.ContHandler[IX, OX, EX, C, Y, S2] @unchecked =>
                                        val result = handler.run(kyo.input, kyo.cont.chain(contA.chain(contB)))
                                        Debugger.onResult(result)
                                        loop(result, Arrow.id, Arrow.id, ctx)
                                    case handler: Handler.ContOpHandler[EX, C, Y, S2] @unchecked =>
                                        val operation: OX[VX] < EX =
                                            new Kyo.SuspendArrow[IX, OX, EX, VX, OX[VX], EX]:
                                                def tag   = kyo.tag
                                                def input = kyo.input
                                                def cont  = Arrow.id
                                        val result = handler.run(operation, kyo.cont.chain(contA.chain(contB)))
                                        Debugger.onResult(result)
                                        loop(result, Arrow.id, Arrow.id, ctx)
                                    case handler: Handler.LoopHandler[VX, IX, OX, EX, C, Y, S2] @unchecked =>
                                        val k    = kyo.cont.chain(contA.chain(contB)).asInstanceOf[Arrow[Any, Any, Any]]
                                        val exit = handler.answers(stack.state(idx).asInstanceOf[VX], kyo.input, k, armed, slot)
                                        Debugger.onResult(exit)
                                        exit match
                                            case e: Loop.Continue2[VX, Any] @unchecked =>
                                                stack.setState(idx, e._1)
                                                loop(e._2.asInstanceOf[Any < S2], Arrow.id, Arrow.id, ctx)
                                            case pending: Pending[Outcome2[VX, OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                type OutT = Outcome2[VX, OX[VX] < (EX & S2), Y < S2]
                                                val reentry = k.asInstanceOf[Arrow[OX[VX], C, EX & S2]]
                                                val dispatch =
                                                    new Arrow.Step[OutT, Y, S2]:
                                                        def frame = Frame.internal
                                                        override def apply[D, S3](out: OutT < S3, cont2: Arrow[Y, D, S3]) =
                                                            out match
                                                                case p: Pending[OutT, S3] @unchecked =>
                                                                    Effect.defer(p, this, cont2)
                                                                case out: Loop.Continue2[VX, OX[VX] < (EX & S2)] @unchecked =>
                                                                    Kyo.handle[VX, EX, C, Y, S2](
                                                                        out._2.chain(reentry),
                                                                        handler,
                                                                        out._1
                                                                    ).chain(cont2)
                                                                case out =>
                                                                    Nested.unnest[Y < S2](out).chain(cont2)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                Debugger.onRegionExit(handler, pending)
                                                // The entry dissolves but its extent continues as the
                                                // dispatch value, so what it owes re-homes below.
                                                stack.oweBelow(idx, stack.pop())
                                                loop[OutT, Y, Any, S2](pending, dispatch, next, ctx)
                                            case done =>
                                                val result = Nested.unnest[Y < S2](done.asInstanceOf[Y < S2])
                                                Debugger.onRegionExit(handler, result)
                                                val next     = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                val owedHere = stack.takeOwed(idx)
                                                stack.truncate(idx)
                                                drainDiscarded(owedHere)
                                                loop(result, next, Arrow.id, ctx)
                                        end match
                                    case handler =>
                                        bug(s"unhandled: $handler")
                                end match
                            else
                                Debugger.onHandle(kyo, stack.handler(idx), stack.state(idx))
                                Debugger.onForeign(kyo, stack.handler(stack.depth - 1))
                                // A crossing vacates the regions above the answering entry: they
                                // leave the stack as the capture's cargo, owed by the entry, and
                                // their bindings leave the context, so the clause and its outcome
                                // read what is below them.
                                val entries = dumped(stack, idx, kyo)
                                val ctx2    = rebound(stack, entries, ctx)
                                def continuation =
                                    val kc = kyo.cont
                                    val ca = contA
                                    val cb = contB
                                    new Arrow.Step[OX[VX], C, EX & S2]:
                                        def frame = Frame.internal
                                        override def apply[D, S3](v: OX[VX] < S3, cont2: Arrow[C, D, S3]) =
                                            v match
                                                case p: Pending[OX[VX], S3] @unchecked => Effect.defer(p, this, cont2)
                                                case _ =>
                                                    cont2(
                                                        Kyo.Park(
                                                            Effect.defer(v, kc, ca, cb).asInstanceOf[Any < Any],
                                                            entries
                                                        ),
                                                        Arrow.id
                                                    )
                                    end new
                                end continuation
                                stack.handler(idx) match
                                    case handler: Handler.ContHandler[IX, OX, EX, C, Y, S2] @unchecked =>
                                        val result = handler.run(kyo.input, continuation)
                                        Debugger.onResult(result)
                                        loop(result, Arrow.id, Arrow.id, ctx2)
                                    case handler: Handler.ContOpHandler[EX, C, Y, S2] @unchecked =>
                                        val operation: OX[VX] < EX =
                                            new Kyo.SuspendArrow[IX, OX, EX, VX, OX[VX], EX]:
                                                def tag   = kyo.tag
                                                def input = kyo.input
                                                def cont  = Arrow.id
                                        val result = handler.run(operation, continuation)
                                        Debugger.onResult(result)
                                        loop(result, Arrow.id, Arrow.id, ctx2)
                                    case handler: Handler.LoopHandler[VX, IX, OX, EX, C, Y, S2] @unchecked =>
                                        val outcome0 = handler.run(stack.state(idx).asInstanceOf[VX], kyo.input)
                                        stack.scratch = outcome0
                                        Debugger.onResult(outcome0)
                                        outcome0 match
                                            case outcome: Loop.Continue2[VX, OX[VX] < (EX & S2)] @unchecked =>
                                                stack.setState(idx, outcome._1)
                                                loop(outcome._2, continuation, Arrow.id, ctx2)
                                            case pending: Pending[Outcome2[VX, OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                type Out = Outcome2[VX, OX[VX] < (EX & S2), Y < S2]
                                                val reentry = continuation
                                                val dispatch =
                                                    new Arrow.Step[Out, Y, S2]:
                                                        def frame = Frame.internal
                                                        override def apply[D, S3](out: Out < S3, cont2: Arrow[Y, D, S3]) =
                                                            out match
                                                                case kyo: Pending[Out, S3] @unchecked =>
                                                                    Effect.defer(kyo, this, cont2)
                                                                case out: Loop.Continue2[VX, OX[VX] < (EX & S2)] @unchecked =>
                                                                    Kyo.handle[VX, EX, C, Y, S2](
                                                                        out._2.chain(reentry),
                                                                        handler,
                                                                        out._1
                                                                    ).chain(cont2)
                                                                case out =>
                                                                    Nested.unnest[Y < S2](out).chain(cont2)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                Debugger.onRegionExit(handler, pending)
                                                stack.oweBelow(idx, stack.pop())
                                                loop(pending, dispatch, next, ctx2)
                                            case outcome =>
                                                val result = Nested.unnest[Y < S2](outcome)
                                                Debugger.onRegionExit(handler, result)
                                                val next     = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                val owedHere = stack.takeOwed(idx)
                                                stack.truncate(idx)
                                                drainDiscarded(owedHere)
                                                loop(result, next, Arrow.id, ctx2)
                                        end match
                                    case handler =>
                                        bug(s"unhandled: $handler")
                                end match
                            end if

                case kyo: Kyo.Handle[?, CX, ?, ?, T, S2] @unchecked =>
                    kyo.handler match
                        case handler: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                            val newState   = handler.derive(ctx.get(handler.tag))
                            val newContext = ctx.update(handler.tag, newState)
                            Debugger.onContext(kyo, newContext)
                            Debugger.onRegionEnter(kyo.handler, newState)
                            stack.push(kyo.handler, newState, kyo.cont.chain(contA.chain(contB)))
                            loop(kyo.value, Arrow.id, Arrow.id, newContext)
                        case _ =>
                            Debugger.onRegionEnter(kyo.handler, kyo.state)
                            stack.push(kyo.handler, kyo.state, kyo.cont.chain(contA.chain(contB)))
                            loop(kyo.value, Arrow.id, Arrow.id, ctx)
                    end match

                case kyo: Kyo.Park[?, ?] if kyo.entries.isEmpty =>
                    stack.oweBelow(stack.depth, kyo.owed)
                    loop(kyo.value.asInstanceOf[T < S2], contA, contB, ctx)

                case kyo: Kyo.Park[?, ?] =>
                    // What the parked eval owed re-homes below the installed run, like any
                    // dissolved extent.
                    stack.oweBelow(stack.depth, kyo.owed)
                    val entries = kyo.entries
                    @tailrec def install(i: Int, c: Context): Context =
                        if i == entries.regions then c
                        else
                            val stored = entries.continuation(i).asInstanceOf[Arrow[Y, Any, Any]]
                            val cont =
                                if i == 0 then stored.chain(contA.chain(contB).asInstanceOf[Arrow[Any, Any, Any]])
                                else stored
                            entries.handler(i) match
                                case hc: Handler.ContextHandler[VX, CX, Y, Any] @unchecked =>
                                    val st = entries.state(i).asInstanceOf[VX]
                                    Debugger.onRegionEnter(hc, st)
                                    stack.push(hc, st, cont)
                                    stack.owe(stack.depth - 1, entries.owed(i))
                                    install(i + 1, c.update(hc.tag, st))
                                case handler0 =>
                                    val handler = handler0.asInstanceOf[Handler[EX, Y, Any]]
                                    val st      = entries.state(i).asInstanceOf[VX]
                                    Debugger.onRegionEnter(handler, st)
                                    stack.push(handler, st, cont)
                                    stack.owe(stack.depth - 1, entries.owed(i))
                                    install(i + 1, c)
                            end match
                    loop(kyo.value, Arrow.id, Arrow.id, install(0, ctx))

                case kyo: Kyo.Snapshot[T, S2] @unchecked =>
                    loop(kyo.cont(stack.contextual(), contA.chain(contB)), Arrow.id, Arrow.id, ctx)

                case res =>
                    if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then
                        if stack.isEmpty then res.asInstanceOf[A < S]
                        else
                            val top  = stack.depth - 1
                            val next = stack.continuation(top).asInstanceOf[Arrow[Y, Any, Any]]
                            stack.handler(top) match
                                case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                                    // A context region binds, it does not transform: the result passes
                                    // through at exit in its union representation. The completion edge
                                    // fires in the same slice as the pop.
                                    Debugger.onRegionExit(hc, res)
                                    hc.done(stack.state(top).asInstanceOf[VX])
                                    drainDiscarded(stack.pop())
                                    val j = stack.find(hc.tag)
                                    val outer =
                                        if j < 0 then ctx.remove(hc.tag)
                                        else ctx.update(hc.tag, stack.state(j).asInstanceOf[VX])
                                    loop(res.asInstanceOf[Y < Any], next, Arrow.id, outer)
                                case handler0 =>
                                    val handler = handler0.asInstanceOf[Handler.ArrowHandler[VX, EX, AX, Y, Any]]
                                    val result  = handler.done(stack.state(top).asInstanceOf[VX], Nested.unnest[AX](res))
                                    Debugger.onRegionExit(handler, result)
                                    drainDiscarded(stack.pop())
                                    loop(result, next, Arrow.id, ctx)
                            end match
                    else
                        contA match
                            case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>
                                loop(res, contA.a, contA.b.chain(contB), ctx)
                            case _ =>
                                loop(contA(res, contB), Arrow.id, Arrow.id, ctx)
            end match
        end loop

        @tailrec def recovered(ex: Throwable): A < S =
            if stack.isEmpty then
                val owedNow = stack.takeEvalOwed()
                if !owedNow.isEmpty then drainOwed(owedNow, ex)
                EffectTrace.splice(ex)
                throw ex
            else
                val top   = stack.depth - 1
                val state = stack.state(top)
                stack.handler(top) match
                    case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                        // A binding cannot answer a failure; it dies without resuming, and
                        // what it owes drains before the binding itself.
                        Debugger.onRegionExit(hc, ex)
                        val owedHere = stack.pop()
                        if !owedHere.isEmpty then drainOwed(owedHere, ex)
                        released(hc, state, ex)
                        recovered(ex)
                    case handler0 =>
                        val handler = handler0.asInstanceOf[Handler.ArrowHandler[VX, EX, AX, Y, Any]]
                        val outcome =
                            try handler.recover(state.asInstanceOf[VX], ex)
                            catch
                                case ex2 if NonFatal(ex2) =>
                                    Debugger.onRegionExit(handler, ex2)
                                    val owedHere = stack.pop()
                                    if !owedHere.isEmpty then drainOwed(owedHere, ex2)
                                    EffectTrace.attach(ex2, stack)
                                    EffectTrace.splice(ex2)
                                    return recovered(ex2)
                        outcome match
                            case Present(r) =>
                                Debugger.onRecover(handler, ex)
                                Debugger.onRegionExit(handler, r)
                                r.chain(stack.continuation(top).asInstanceOf[Arrow[Y, A, S]])
                            case Absent =>
                                Debugger.onRegionExit(handler, ex)
                                val owedHere = stack.pop()
                                if !owedHere.isEmpty then drainOwed(owedHere, ex)
                                recovered(ex)
                        end match
                end match

        @tailrec def guarded(curr: A < S, ctx: Context): A < S =
            val res =
                try loop(curr, Arrow.id, Arrow.id, ctx)
                catch
                    case failure if NonFatal(failure) =>

                        Safepoint.reset(slot)

                        EffectTrace.attach(failure, stack)
                        EffectTrace.splice(failure)
                        val resumed  = recovered(failure)
                        val owedHere = stack.pop()
                        if !owedHere.isEmpty then drainOwed(owedHere, failure)
                        @tailrec def rebuild(i: Int, rebuilt: Context): Context =
                            if i == stack.depth then rebuilt
                            else
                                stack.handler(i) match
                                    case handler: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                                        rebuild(i + 1, rebuilt.update(handler.tag, stack.state(i).asInstanceOf[VX]))
                                    case _ => rebuild(i + 1, rebuilt)
                        return guarded(resumed, rebuild(0, Context.empty))
            res match
                case susp: Kyo.Suspend[?, ?, ?, ?] =>

                    bug(s"unhandled suspension: $susp")
                case res => res
            end match
        end guarded

        try
            val out = guarded(v, Context.empty)
            // A park transferred what it owed into the remainder; anything still rooted
            // on the stack belongs to no surviving extent and drains now.
            drainDiscarded(stack.takeEvalOwed())
            out
        finally
            Safepoint.restore(slot, saved)
            Stack.release(stack)
        end try
    end apply

    type AX
    type Y
    type IX[_]
    type OX[_]
    type EX <: ArrowEffect[IX, OX]
    type VX
    type CX <: ContextEffect[VX]
    type IY[_]
    type OY[_]
    type EY <: ArrowEffect[IY, OY]
    type VY
end Eval
