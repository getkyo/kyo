package kyo.kernel2

import kyo.Frame
import kyo.kernel2.internal.Context
import kyo.kernel2.internal.Handlers
import kyo.kernel2.internal.Kyo
import kyo.kernel2.internal.LiftMacro.defaultLift
import scala.annotation.tailrec

/** Instrumentation hook: observes every step of a computation with an effectful observer.
  *
  * The observer receives each step's frame and input value before the step runs, and its own computation is sequenced in front of the
  * step, outside the observed computation, so observation never observes itself. The observation is contained: it wraps the observed
  * computation's own steps, rotating with each suspended remainder so steps that run after an out-of-band resume are observed too, and
  * steps appended after this call are outside it.
  */
private[kyo] object Observe:

    def apply[A, S, S2](observer: (Frame, Any) => Any < S2)(v: A < S): A < (S & S2) =
        // the observer's own effect row is erased into the step and restored in the result type
        val obs = observer.asInstanceOf[(Frame, Any) => Any < Any]
        def wrap(w: Any < Any): Any < Any =
            w match
                case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                    s.continue(new Segment(obs, s.cont.asInstanceOf[Arrow[Any, Any, Any]]))
                case d: Kyo.Defer[?, ?, ?] =>
                    new Kyo.Defer[Any, Any, Any](
                        d.value.asInstanceOf[Any < Any],
                        new Segment(obs, d.cont.asInstanceOf[Arrow[Any, Any, Any]])
                    )
                case b: Kyo.Bracket[Any, Any, Any] @unchecked =>
                    new Kyo.Bracket[Any, Any, Any]:
                        def acquire         = wrap(b.acquire)
                        def release(r: Any) = wrap(b.release(r)).asInstanceOf[Unit < Any]
                        def cont            = new Segment(obs, b.cont.asInstanceOf[Arrow[Any, Any, Any]])
                        def frame           = b.frame
                case w => w
            end match
        end wrap
        wrap(v.asInstanceOf[Any < Any]).asInstanceOf[A < (S & S2)]
    end apply

    /** The observed segment: contains the chain it observes and walks it one step at a time, sequencing the observer in front of each
      * step. On a suspension the remaining segment is wrapped again, so the observation travels with the computation.
      */
    final private class Segment(observer: (Frame, Any) => Any < Any, inner: Arrow[Any, Any, Any])
        extends Arrow.Transform[Any, Any, Any]:
        def frame = Frame.internal

        def run[C, S2](v: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            inner.optimize match
                case o: Arrow.Step[Any, Any, Any, Any] @unchecked =>
                    cont(step(o, v, context, handlers), context, handlers)
                case _ =>
                    cont(defaultLift(v), context, handlers)

        // one observed transform per observer completion; recursion goes through the
        // continuation arrow, so the depth guard keeps arbitrarily long observed
        // chains stack safe. The post-observer step lives in a minted transform:
        // when the observer suspends, the step runs under the resuming drive's
        // context, never a stale capture.
        private def step(o0: Arrow.Step[Any, Any, Any, Any], cur: Any, context: Context, handlers: Handlers): Any < Any =
            @tailrec def target(o: Arrow.Step[Any, Any, Any, Any]): Arrow.Step[Any, Any, Any, Any] =
                o.head match
                    case jump: Arrow.Step[Any, Any, Any, Any] @unchecked if Arrow.isEmpty(o.next) => target(jump)
                    case _                                                                        => o
            val o = target(o0)
            val t = o.head
            val afterObserver = new Arrow.Transform[Any, Any, Any]:
                def frame = Frame.internal
                def run[C2, S3](x: Any, context: Context, handlers: Handlers, cont2: Arrow[Any, C2, S3]): C2 < (Any & S3) =
                    val w = t.run(cur, context, handlers, Arrow[Any])
                    val next =
                        if w.isInstanceOf[Kyo[?, ?]] then
                            // the remaining observed segment wraps again across the park
                            w.asInstanceOf[Kyo[Any, Any]].map(new Segment(observer, o.next))
                        else
                            o.next match
                                case n: Arrow.Step[Any, Any, Any, Any] @unchecked => step(n, Kyo.unnest(w), context, handlers)
                                case _                                            => defaultLift(Kyo.unnest(w))
                    cont2(next, context, handlers)
                end run
            afterObserver(observer(t.frame, cur), context, handlers)
        end step
    end Segment
end Observe
