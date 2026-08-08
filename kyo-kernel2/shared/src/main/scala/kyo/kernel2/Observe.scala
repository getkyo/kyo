package kyo.kernel2

import kyo.Frame
import scala.annotation.tailrec

/** Instrumentation hook: observes every step of a computation with an effectful observer.
  *
  * The observer receives each step's frame and input value before the step runs, and its own computation is sequenced in front of the
  * step, outside the observed region, so observation never observes itself. The interception travels with the computation: it re-arms
  * across parks, so steps run after an out-of-band resume are observed too.
  */
private[kyo] object Observe:

    def apply[A, S, S2](observer: (Frame, Any) => Any < S2)(v: A < S): A < (S & S2) =
        v match
            case kyo: Kyo[A, S] @unchecked =>
                kyo.prepend(new Step(observer.asInstanceOf[(Frame, Any) => Any < Any])).asInstanceOf[A < (S & S2)]
            case _ =>
                v.asInstanceOf[A < (S & S2)]

    final private class Step(observer: (Frame, Any) => Any < Any) extends Arrow.Interceptor:
        def frame = Frame.internal

        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            cont match
                case o: Arrow.Offset[Any, Any, Any, Any] @unchecked =>
                    step(o, v).asInstanceOf[C < (Any & S2)]
                case _ =>
                    cont(v.asInstanceOf[Any < Any])

        // one observed transform per observer completion; recursion goes through map, so
        // the depth guard keeps arbitrarily long observed chains stack safe
        private def step(o0: Arrow.Offset[Any, Any, Any, Any], cur: Any): Any < Any =
            given Frame = Frame.internal
            @tailrec def target(o: Arrow.Offset[Any, Any, Any, Any]): Arrow.Offset[Any, Any, Any, Any] =
                o.head match
                    case jump: Arrow.Offset[Any, Any, Any, Any] @unchecked if Arrow.isEmpty(o.next) => target(jump)
                    case _                                                                          => o
            val o = target(o0)
            val t = o.head
            observer(t.frame, cur).map { _ =>
                val w = t.run(cur, Arrow[Any])
                if w.isInstanceOf[Kyo[?, ?]] then
                    // re-arm across the park so later steps stay observed
                    val rest: Arrow[Any, Any, Any] =
                        if Arrow.isEmpty(o.next) then this
                        else Arrow.map(this)(o.next)
                    w.asInstanceOf[Kyo[Any, Any]].map(rest)
                else
                    o.next match
                        case n: Arrow.Offset[Any, Any, Any, Any] @unchecked => step(n, Kyo.unnest(w))
                        case _                                              => Kyo.lift(Kyo.unnest(w))
                end if
            }
        end step
    end Step
end Observe
