package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.kernel.*
import kyo.kernel.`<`.fromKyo
import kyo.kernel.Implicits.liftInternal
import kyo.kernel.internal.Handlers.Empty
import scala.annotation.tailrec

object Eval:

    // the restore is owed whether the drive returns or throws: save resets the
    // slot to its initial state, so an escaping throw would otherwise leave the
    // caller's budget and armed bit as the aborted drive left them and every
    // later drive on this thread would run on the wrong budget
    def apply[A, S](v: A < S): A < S =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        try evalLoop(v, slot, partial = false)
        catch
            case ex: Throwable =>
                EffectTrace.splice(ex)
                throw ex
        finally Safepoint.restore(slot, saved)
        end try
    end apply

    def partial[A, S](v: A < S): A < S =
        val slot = Safepoint.get()
        if Safepoint.consumeStopped(slot) then v
        else
            val saved = Safepoint.save(slot)
            Safepoint.arm(slot)
            try evalLoop(v, slot, partial = true)
            catch
                case ex: Throwable =>
                    EffectTrace.splice(ex)
                    throw ex
            finally Safepoint.restore(slot, saved)
            end try
        end if
    end partial

    // the enrichment handler of every guarded arm: the walk reads the node and
    // the region stack the loop already holds, and the rethrow is unconditional,
    // so which exceptions propagate is unchanged (the fatal test is inside attach)
    private def enrich(ex: Throwable, v: Any < Nothing, hs: Handlers): Nothing =
        EffectTrace.attach(ex, v, hs)
        throw ex
    end enrich

    // the continuation a capturing clause receives: resuming rebuilds the crossed
    // regions around the resumption, so the value re-enters exactly the stack it
    // left. Shared by the Cont and First arms
    private def resumer(hs: Handlers, cell: Handlers, kCont: Arrow[Any, Any, Any]): Any => Any < Nothing =
        o => rebuild(hs, cell, resume(kCont, Nested.lift(o)))

    private def evalLoop[A, S](v0: A < S, slot: Safepoint.Slot, partial: Boolean): A < S =
        // arms ordered by expected frequency: a suspension per answered
        // operation, a defer per budget rescue or deferred effect, region
        // nodes per handler entry or crossing
        @tailrec def loop(v: Any < Nothing, hs: Handlers): Any < Nothing =
            (v: @unchecked) match
                case kyo: Kyo.Suspend[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                    val cell = hs.find(kyo.tag)
                    if cell eq Empty then
                        if !partial then
                            val ex = new IllegalStateException(s"unhandled suspension: $kyo")
                            EffectTrace.attach(ex, v, hs)
                            throw ex
                        else rebuild(hs, Empty, v)
                    else
                        // the answering region dispatches on its handler's kind, which
                        // is the one place the kinds genuinely differ: different clause
                        // protocols, different spine effects, different continuations
                        cell.handler match
                            case h: Handler.LoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] =>
                                val clause =
                                    try h(kyo.input, cell.state)
                                    catch case ex: Throwable => enrich(ex, v, hs)
                                clause match
                                    case pending: Kyo[Loop.Outcome2[Any, Any < Nothing, Any], Any] @unchecked =>
                                        loop(statePending(pending, h, cell, hs, kyo.cont), cell.prev)
                                    case outcome =>
                                        Nested.unnest[Loop.Outcome2[Any, Any < Nothing, Any]](outcome) match
                                            case c: Loop.Continue2[Any, Any < Nothing] @unchecked =>
                                                val updated =
                                                    if c._1.asInstanceOf[AnyRef] eq cell.state.asInstanceOf[AnyRef] then cell
                                                    else cell.withState(c._1)
                                                val hs2 = if updated eq cell then hs else hs.replace(cell, updated)
                                                (c._2: Any) match
                                                    case p: Kyo[Any, Any] @unchecked =>
                                                        loop(continuePending(p, updated, hs2, kyo.cont), updated)
                                                    case answer =>
                                                        val next =
                                                            try resume(kyo.cont, answer.asInstanceOf[Any < Nothing])
                                                            catch case ex: Throwable => enrich(ex, v, hs2)
                                                        loop(next, hs2)
                                                end match
                                            case done =>
                                                val next =
                                                    try resume(cell.exit, Nested.lift(done))
                                                    catch case ex: Throwable => enrich(ex, v, hs)
                                                loop(next, cell.prev)
                                end match
                            case h: Handler.Loop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] =>
                                val clause =
                                    try h(kyo.input)
                                    catch case ex: Throwable => enrich(ex, v, hs)
                                clause match
                                    case pending: Kyo[Loop.Outcome[Any < Nothing, Any], Any] @unchecked =>
                                        loop(loopPending(pending, cell, hs, kyo.cont), cell.prev)
                                    case outcome =>
                                        Nested.unnest[Loop.Outcome[Any < Nothing, Any]](outcome) match
                                            case c: Loop.Continue[Any < Nothing] @unchecked =>
                                                (c._1: Any) match
                                                    case p: Kyo[Any, Any] @unchecked =>
                                                        loop(continuePending(p, cell, hs, kyo.cont), cell)
                                                    case answer =>
                                                        val next =
                                                            try resume(kyo.cont, answer.asInstanceOf[Any < Nothing])
                                                            catch case ex: Throwable => enrich(ex, v, hs)
                                                        loop(next, hs)
                                            case done =>
                                                val next =
                                                    try resume(cell.exit, Nested.lift(done))
                                                    catch case ex: Throwable => enrich(ex, v, hs)
                                                loop(next, cell.prev)
                                end match
                            case h: Handler.Cont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] =>
                                val next =
                                    try h(kyo.input, resumer(hs, cell, kyo.cont))
                                    catch case ex: Throwable => enrich(ex, v, hs)
                                loop(next, cell)
                            case h: Handler.First[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] =>
                                // the region answers once: the clause's result runs at
                                // cell.prev, so the cell is off the spine before the
                                // continuation it was handed can raise the effect again
                                val next =
                                    try walk(cell.exit, h(kyo.input, resumer(hs, cell, kyo.cont)))
                                    catch case ex: Throwable => enrich(ex, v, hs)
                                loop(next, cell.prev)
                        end match
                    end if
                case kyo: Kyo.Defer[Any, Any, Any] @unchecked =>
                    if partial && Safepoint.consumeStopped(slot) then
                        rebuild(hs, Empty, v)
                    else
                        Safepoint.reset(slot)
                        val next =
                            try walk(kyo.cont, kyo.value)
                            catch case ex: Throwable => enrich(ex, v, hs)
                        loop(next, hs)
                case kyo: Kyo.Handled[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
                    loop(kyo.value, hs.push(kyo))
                case kyo: Kyo.HandledState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any, Any] @unchecked =>
                    loop(kyo.value, hs.push(kyo))
                case kyo: Kyo.HandledFirst[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any, Any] @unchecked =>
                    loop(kyo.value, hs.push(kyo))
                case v =>
                    if hs eq Empty then v
                    else
                        hs.handler match
                            case h: Handler.First[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] =>
                                // no operation reached the region: the done clause
                                // produces the value the exit consumes, taking it out
                                // of the currency because it crosses to a function
                                val next =
                                    try walk(hs.exit, h(Nested.unnest(v)))
                                    catch case ex: Throwable => enrich(ex, v, hs)
                                loop(next, hs.prev)
                            case h: Handler.LoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] =>
                                // normal completion of a stateful region: the done
                                // transform observes the final state; a clause's
                                // Loop.done reaches the exit directly and skips it
                                val next =
                                    try walk(hs.exit, h.applyDone(hs.state, Nested.unnest(v)))
                                    catch case ex: Throwable => enrich(ex, v, hs)
                                loop(next, hs.prev)
                            case _ =>
                                val next =
                                    try resume(hs.exit, v)
                                    catch case ex: Throwable => enrich(ex, v, hs)
                                loop(next, hs.prev)
                    end if
        loop(v0, Empty).asInstanceOf[A < S]
    end evalLoop

    // the pending-outcome re-entries: a handler that itself suspends chains
    // its region's reconstruction after the pending outcome. Kept out of the
    // evaluation loop so its hot arms stay small
    private def statePending(
        pending: Loop.Outcome2[Any, Any < Nothing, Any] < Any,
        handler: Handler.LoopState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any],
        cell: Handlers,
        hsAll: Handlers,
        kCont: Arrow[Any, Any, Any]
    ): Any < Nothing =
        pending.map {
            case c: Loop.Continue2[Any, Any < Nothing] @unchecked =>
                Kyo.HandledState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any, Any](
                    rebuild(hsAll, cell, walk(kCont, c._2)),
                    handler,
                    cell.exit,
                    c._1
                )
            case done =>
                resume(cell.exit, Nested.lift(done))
        }(using Frame.internal)

    // shared by the stateless and stateful regions: rebuild only walks cells
    private def continuePending(
        p: Any < Any,
        node: Handlers,
        hsAll: Handlers,
        kCont: Arrow[Any, Any, Any]
    ): Any < Nothing =
        p.map { a =>
            rebuild(hsAll, node, resume(kCont, Nested.lift(a)))
        }(using Frame.internal)

    private def loopPending(
        pending: Loop.Outcome[Any < Nothing, Any] < Any,
        cell: Handlers,
        hsAll: Handlers,
        kCont: Arrow[Any, Any, Any]
    ): Any < Nothing =
        pending.map {
            case c: Loop.Continue[Any < Nothing] @unchecked =>
                rebuild(hsAll, cell.prev, walk(kCont, c._1))
            case done =>
                resume(cell.exit, Nested.lift(done))
        }(using Frame.internal)

    // a suspension travelling up the chain walk: the unfinished right sides compose into
    // rest one chain node per frame; created at the suspension point, consumed by the walk
    // root, never escapes
    final private class Suspended(val kyo: Kyo[Any, Nothing], var rest: Arrow[Any, Any, Any])

    private def walk(cont: Arrow[Any, Any, Any], v: Any < Nothing): Any < Nothing =
        v match
            case kyo: Kyo[Any, Nothing] @unchecked => kyo.map(cont)
            case _                                 => resume(cont, v)

    // the settled half of walk: callers use it directly when the value is
    // proven not to be a computation (lifted, or matched apart already)
    private def resume(cont: Arrow[Any, Any, Any], v: Any < Nothing): Any < Nothing =
        cont match
            case cont: Arrow.AndThen[Any, Any, Any, Any] @unchecked =>
                evalChain(cont, v)
            case cont =>
                val step = cont.step
                step.head(v, step.tail)

    private def evalChain(root: Arrow.AndThen[Any, Any, Any, Any], v0: Any < Nothing): Any < Nothing =
        val slot = Safepoint.get()
        def run(u: Arrow[Any, Any, Any], v: Any < Nothing): Any < Nothing =
            u match
                case at: Arrow.AndThen[Any, Any, Any, Any] @unchecked if Safepoint.enter(slot) =>
                    val out =
                        run(at.a, v) match
                            case sus: Suspended =>
                                sus.rest = sus.rest.chain(at.b)
                                sus
                            case r =>
                                run(at.b, r)
                    Safepoint.exit(slot)
                    out
                case u =>
                    // a unit executes fused through its own sites; past the budget the
                    // subtree flattens and executes as a linked chain that defers with
                    // progress under the transform budget
                    val step = u.step
                    step.head(v, step.tail) match
                        case kyo: Kyo[Any, Nothing] @unchecked => new Suspended(kyo, Arrow[Any])
                        case r                                 => r

        run(root, v0) match
            case sus: Suspended =>
                // the remainder links once at the suspension boundary, so the resumed part
                // executes fused as well
                sus.rest match
                    case rest if rest eq Arrow[Any] => sus.kyo
                    case rest                       => sus.kyo.map(rest.step)
            case r =>
                r
        end match
    end evalChain

    // the crossed regions are restored as region nodes around the resumed
    // computation, one per cell, and what this produces the next loop iterations
    // simply re-enter
    @tailrec private def rebuild(top: Handlers, stop: Handlers, acc: Any < Nothing): Any < Nothing =
        if (top eq stop) || (top eq Empty) then acc
        else rebuild(top.prev, stop, top.rebuilt(acc))

end Eval
