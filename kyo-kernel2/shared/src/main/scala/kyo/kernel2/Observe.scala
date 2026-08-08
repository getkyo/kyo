package kyo.kernel2

import kyo.Frame
import kyo.kernel2.internal.Context
import kyo.kernel2.internal.Kyo
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
                // the observer's own effect row is erased into the interceptor and restored in the result type
                kyo.prepend(new Step(observer.asInstanceOf[(Frame, Any) => Any < Any]))
            case _ =>
                v

    final private class Step(observer: (Frame, Any) => Any < Any) extends Arrow.Interceptor:
        def frame = Frame.internal

        def run[C, S2](v: Any, context: Context, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            cont match
                case o: Arrow.Offset[Any, Any, Any, Any] @unchecked =>
                    step(o, v, context).asInstanceOf[C < (Any & S2)]
                case _ =>
                    cont(Kyo.lift(v), context)

        // one observed transform per observer completion; recursion goes through the
        // continuation arrow, so the depth guard keeps arbitrarily long observed
        // chains stack safe. The post-observer step lives in a minted transform:
        // when the observer suspends, the step runs under the resuming drive's
        // context, never a stale capture.
        private def step(o0: Arrow.Offset[Any, Any, Any, Any], cur: Any, context: Context): Any < Any =
            @tailrec def target(o: Arrow.Offset[Any, Any, Any, Any]): Arrow.Offset[Any, Any, Any, Any] =
                o.head match
                    case jump: Arrow.Offset[Any, Any, Any, Any] @unchecked if Arrow.isEmpty(o.next) => target(jump)
                    case _                                                                          => o
            val o = target(o0)
            val t = o.head
            val afterObserver = new Arrow.Transform[Any, Any, Any]:
                def frame = Frame.internal
                def run[C2, S3](x: Any, context: Context, cont2: Arrow[Any, C2, S3]): C2 < (Any & S3) =
                    val w = t.run(cur, context, Arrow[Any])
                    val next =
                        if w.isInstanceOf[Kyo[?, ?]] then
                            // re-arm across the park so later steps stay observed
                            val rest: Arrow[Any, Any, Any] =
                                if Arrow.isEmpty(o.next) then Step.this
                                else Arrow.map(Step.this)(o.next)
                            w.asInstanceOf[Kyo[Any, Any]].map(rest)
                        else
                            o.next match
                                case n: Arrow.Offset[Any, Any, Any, Any] @unchecked => step(n, Kyo.unnest(w), context)
                                case _                                              => Kyo.lift(Kyo.unnest(w))
                    cont2(next, context)
                end run
            afterObserver(observer(t.frame, cur), context)
        end step
    end Step
end Observe
