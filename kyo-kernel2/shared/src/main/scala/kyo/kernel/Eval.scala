package kyo.kernel

import scala.annotation.tailrec

private[kernel] object Eval:

    def apply[A, S](v: A < S): A < S =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        val res   = evalLoop(v, Handlers.empty, slot)
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

    private def evalLoop[A, S](v0: A < S, handlers: Handlers, slot: Safepoint.Slot): A < S =
        @tailrec def loop(v: A < S): A < S =
            (v: @unchecked) match
                case kyo: Kyo.Handled[i, o, ?, a, b, s] @unchecked =>
                    enter(kyo.asInstanceOf[Kyo.Handled[i, o, Nothing, a, b, s]], handlers, slot) match
                        case halt: Kyo.Halt[?, ?, ?, ?] =>
                            halt
                        case settled =>
                            val step = kyo.cont.step
                            loop(step.head(settled, step.tail).asInstanceOf[A < S])
                case kyo: Kyo.Suspend[i, o, e, x, ?, ?] @unchecked =>
                    val idx = handlers.indexOf(kyo.tag)
                    if idx < 0 then throw new IllegalStateException(s"unhandled suspension: $kyo")
                    else
                        handlers(idx) match
                            case h: Handler.Resume[?, ?, ?, ?] =>
                                val answer = h.asInstanceOf[Handler.Resume[i, o, Nothing, Any]][x](kyo.input)
                                val settled =
                                    (answer: Any) match
                                        case pending: Kyo[?, ?] => evalLoop(answer, handlers.take(idx + 1), slot)
                                        case _                  => answer
                                val step = kyo.cont.step
                                loop(step.head(settled, step.tail).asInstanceOf[A < S])
                            case h: Handler.Stop[?, ?, ?, ?, ?] =>
                                new Kyo.Halt(h.asInstanceOf[Handler.Stop[i, o, Nothing, Any, Any]], kyo.input)
                            case other =>
                                throw new IllegalStateException(s"cannot handle: $other")
                    end if
                case kyo: Kyo.Defer[?, ?, ?] =>
                    Safepoint.restore(slot, 0L)
                    val step = kyo.cont.step
                    loop(step.head(kyo.value, step.tail).asInstanceOf[A < S])
                case v =>
                    v
        loop(v0)
    end evalLoop

    // runs a region: evaluates its computation under the extended collection and
    // answers halts addressed to its own handler at the boundary, where a stop
    // clause that raises the region's effect again stops again
    private def enter[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        kyo: Kyo.Handled[I, O, E, A, B, S],
        handlers: Handlers,
        slot: Safepoint.Slot
    ): A < (E & S) =
        @tailrec def go(result: A < (E & S)): A < (E & S) =
            (result: @unchecked) match
                case halt: Kyo.Halt[?, ?, ?, ?] if halt.owner eq kyo.handler =>
                    val stop   = kyo.handler.asInstanceOf[Handler.Stop[I, O, E, Any, Any]]
                    val answer = stop[Any](halt.input.asInstanceOf[I[Any]])
                    go(evalLoop(answer, handlers.add(kyo.handler), slot).asInstanceOf[A < (E & S)])
                case out =>
                    out
        go(evalLoop(kyo.value, handlers.add(kyo.handler), slot))
    end enter
end Eval
