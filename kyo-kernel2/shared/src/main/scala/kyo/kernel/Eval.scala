package kyo.kernel

import scala.annotation.tailrec

private[kernel] object Eval:

    def apply[A, S](v0: A < S, handlers: Handlers, slot: Safepoint.Slot): A < S =
        @tailrec def loop(v: A < S): A < S =
            (v: @unchecked) match
                case kyo: Kyo.Handled[?, ?, ?, ?, ?, ?] =>
                    val settled = Eval(kyo.value, handlers.add(kyo.handler), slot)
                    val step    = kyo.cont.step
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
                                        case pending: Kyo[?, ?] => Eval(answer, handlers.take(idx + 1), slot)
                                        case _                  => answer
                                val step = kyo.cont.step
                                loop(step.head(settled, step.tail).asInstanceOf[A < S])
                            case other =>
                                throw new IllegalStateException(s"cannot drive handler: $other")
                    end if
                case kyo: Kyo.Defer[?, ?, ?] =>
                    Safepoint.restore(slot, 0L)
                    val step = kyo.cont.step
                    loop(step.head(kyo.value, step.tail).asInstanceOf[A < S])
                case v =>
                    v
        loop(v0)
    end apply
end Eval
