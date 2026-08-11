package kyo.kernel

import scala.annotation.tailrec

private[kernel] object Eval:

    def apply[A, S](v: A < S): A < S =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        val res   = evalLoop(v, Handlers.empty, slot)._1
        Safepoint.restore(slot, saved)
        res
    end apply

    def partial[A, S](v: A < S, stop: () => Boolean): A < S =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        @tailrec def loop(v: A < S): A < S =
            if stop() then v
            else
                (v: @unchecked) match
                    case kyo: Kyo.Defer[?, ?, ?] =>
                        Safepoint.restore(slot, 0L)
                        val step = kyo.cont.step
                        loop(step.head(kyo.value, step.tail).asInstanceOf[A < S])
                    case v =>
                        v
        val res = loop(v)
        Safepoint.restore(slot, saved)
        res
    end partial

    private def evalLoop[A, S](v0: A < S, handlers: Handlers, slot: Safepoint.Slot): (A < S, Handlers) =
        @tailrec def loop(v: A < S, hs: Handlers): (A < S, Handlers) =
            (v: @unchecked) match
                case kyo: Kyo.Handled[i, o, ?, a, b, s] @unchecked =>
                    val res     = evalLoop(kyo.value, hs.add(kyo.handler), slot)
                    val hsExit  = res._2
                    val hsAfter = hsExit.take(hsExit.size - 1)
                    res._1 match
                        // ownership is positional: the scope's entry is always
                        // last in its own collection and successors replace it
                        // in place, so the current occupant is the owner even
                        // after state advances
                        case halt: Kyo.Halt[?] if halt.owner eq hsExit(hsExit.size - 1) =>
                            val step = kyo.cont.step
                            loop(step.head(Nested.lift(halt.outcome.asInstanceOf[a]), step.tail).asInstanceOf[A < S], hsAfter)
                        case halt: Kyo.Halt[?] =>
                            (halt, hsAfter)
                        case settled =>
                            val step = kyo.cont.step
                            loop(step.head(settled, step.tail).asInstanceOf[A < S], hsAfter)
                    end match
                case kyo: Kyo.Suspend[i, o, e, x, ?, ?] @unchecked =>
                    val idx = hs.indexOf(kyo.tag)
                    if idx < 0 then throw new IllegalStateException(s"unhandled suspension: $kyo")
                    else
                        hs(idx) match
                            case h: Handler.Loop[?, ?, ?, ?, ?] =>
                                val outcome = h.asInstanceOf[Handler.Loop[i, o, Nothing, Any, Any]][x](kyo.input)
                                (outcome: Any) match
                                    case pending: Kyo[?, ?] =>
                                        val res = settle(pending, idx, hs, slot)
                                        Nested.unnest[Any](res._1) match
                                            case c: Handler.Loop.Continue[?] =>
                                                (c._1: Any) match
                                                    case p: Kyo[?, ?] =>
                                                        val res2 = settle(p, idx, res._2, slot)
                                                        val step = kyo.cont.step
                                                        loop(
                                                            step.head(res2._1.asInstanceOf[o[x] < Any], step.tail).asInstanceOf[A < S],
                                                            res2._2
                                                        )
                                                    case answer =>
                                                        val step = kyo.cont.step
                                                        loop(
                                                            step.head(answer.asInstanceOf[o[x] < Any], step.tail).asInstanceOf[A < S],
                                                            res._2
                                                        )
                                            case done =>
                                                (new Kyo.Halt(h, done), res._2)
                                        end match
                                    case outcome =>
                                        Nested.unnest[Any](outcome) match
                                            case c: Handler.Loop.Continue[?] =>
                                                (c._1: Any) match
                                                    case p: Kyo[?, ?] =>
                                                        val res2 = settle(p, idx, hs, slot)
                                                        val step = kyo.cont.step
                                                        loop(
                                                            step.head(res2._1.asInstanceOf[o[x] < Any], step.tail).asInstanceOf[A < S],
                                                            res2._2
                                                        )
                                                    case answer =>
                                                        val step = kyo.cont.step
                                                        loop(step.head(answer.asInstanceOf[o[x] < Any], step.tail).asInstanceOf[A < S], hs)
                                            case done =>
                                                (new Kyo.Halt(h, done), hs)
                                end match
                            case h: Handler.LoopState[?, ?, ?, ?, ?] =>
                                val outcome = h.asInstanceOf[Handler.LoopState[i, o, Nothing, Any, Any]][x](kyo.input)
                                (outcome: Any) match
                                    case pending: Kyo[?, ?] =>
                                        val res = settle(pending, idx, hs, slot)
                                        Nested.unnest[Any](res._1) match
                                            case c: Handler.Loop.Continue2[?, ?] =>
                                                val hs3 =
                                                    c._1.asInstanceOf[Handler[?, ?, ?]] match
                                                        case next if next eq h => res._2
                                                        case next              => res._2.updated(idx, next)
                                                (c._2: Any) match
                                                    case p: Kyo[?, ?] =>
                                                        val res2 = settle(p, idx, hs3, slot)
                                                        val step = kyo.cont.step
                                                        loop(
                                                            step.head(res2._1.asInstanceOf[o[x] < Any], step.tail).asInstanceOf[A < S],
                                                            res2._2
                                                        )
                                                    case answer =>
                                                        val step = kyo.cont.step
                                                        loop(step.head(answer.asInstanceOf[o[x] < Any], step.tail).asInstanceOf[A < S], hs3)
                                                end match
                                            case done =>
                                                (new Kyo.Halt(h, done), res._2)
                                        end match
                                    case outcome =>
                                        Nested.unnest[Any](outcome) match
                                            case c: Handler.Loop.Continue2[?, ?] =>
                                                val hs3 =
                                                    c._1.asInstanceOf[Handler[?, ?, ?]] match
                                                        case next if next eq h => hs
                                                        case next              => hs.updated(idx, next)
                                                (c._2: Any) match
                                                    case p: Kyo[?, ?] =>
                                                        val res2 = settle(p, idx, hs3, slot)
                                                        val step = kyo.cont.step
                                                        loop(
                                                            step.head(res2._1.asInstanceOf[o[x] < Any], step.tail).asInstanceOf[A < S],
                                                            res2._2
                                                        )
                                                    case answer =>
                                                        val step = kyo.cont.step
                                                        loop(step.head(answer.asInstanceOf[o[x] < Any], step.tail).asInstanceOf[A < S], hs3)
                                                end match
                                            case done =>
                                                (new Kyo.Halt(h, done), hs)
                                end match
                            case other =>
                                throw new IllegalStateException(s"cannot handle: $other")
                    end if
                case kyo: Kyo.Defer[?, ?, ?] =>
                    Safepoint.restore(slot, 0L)
                    val step = kyo.cont.step
                    loop(step.head(kyo.value, step.tail).asInstanceOf[A < S], hs)
                case v =>
                    (v, hs)
        loop(v0, handlers)
    end evalLoop

    // settles an effectful clause result under the prefix through the handler,
    // which is the clause scope, merging a possibly updated prefix back into
    // the site collection; called only with pending values, so the answering
    // hot path allocates nothing beyond the outcome box
    private def settle(pending: Kyo[?, ?], idx: Int, hs: Handlers, slot: Safepoint.Slot): (Any, Handlers) =
        val res = evalLoop(pending.asInstanceOf[Any < Any], hs.take(idx + 1), slot)
        (res._1, res._2.concat(hs.drop(idx + 1)))
end Eval
