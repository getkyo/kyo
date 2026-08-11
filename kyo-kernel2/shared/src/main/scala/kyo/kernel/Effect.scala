package kyo.kernel

import kyo.Frame
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.util.control.NonFatal

abstract class Effect private[kernel] ()

object Effect:

    inline def catching[A, S, B >: A, S2](inline v: => A < S)(
        inline f: Throwable => B < S2
    )(using inline _frame: Frame): B < (S & S2) =
        try guarded(v: B < (S & S2), f, _frame)
        catch
            case ex: Throwable if NonFatal(ex) => f(ex)

    // the guard lives in the continuation arrow: each resumed step applies
    // the original continuation under the handler and re-wraps what it
    // produces, so later steps stay guarded. A region node's internals
    // evaluate at eval and are not covered; its exit steps are
    @nowarn("msg=anonymous")
    private def guarded[B, S](v: B < S, f: Throwable => B < S, _frame: Frame): B < S =
        def guard[In](cont: Arrow[In, B, S]): Arrow.Transform[In, B, S] =
            new Arrow.Transform[In, B, S]:
                def frame = _frame
                def apply[C, S3](v2: In < S3, next: Arrow[B, C, S3]) =
                    val w =
                        try
                            guarded(
                                {
                                    val step = cont.step
                                    step.head(v2.asInstanceOf[In < S], step.tail)
                                },
                                f,
                                _frame
                            )
                        catch
                            case ex: Throwable if NonFatal(ex) => f(ex)
                    (w: @unchecked) match
                        case kyo: Kyo[?, ?] =>
                            kyo.asInstanceOf[Kyo[B, S]].map(next.asInstanceOf[Arrow[B, C, S]]).asInstanceOf[C < S3]
                        case w =>
                            val step = next.step
                            step.head(w.asInstanceOf[B < S3], step.tail)
                    end match
                end apply
        (v: @unchecked) match
            case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                val k = kyo.asInstanceOf[Kyo.Suspend[[Z] =>> Any, [Z] =>> Any, Nothing, Any, B, S]]
                new Kyo.Suspend[[Z] =>> Any, [Z] =>> Any, Nothing, Any, B, S]:
                    override val root = k.root
                    def tag           = root.tag
                    def input         = root.input
                    def frame         = root.frame
                    val cont          = guard(k.cont)
                end new
            case kyo: Kyo.Defer[?, ?, ?] =>
                val defer = kyo.asInstanceOf[Kyo.Defer[Any, B, S]]
                new Kyo.Defer[Any, B, S](defer.value, guard(defer.cont))
            case kyo: Kyo.Handled[?, ?, ?, ?, ?, ?] =>
                kyo.asInstanceOf[Kyo[B, S]].map(guard(Arrow[B]))
            case v =>
                v
        end match
    end guarded

    // TODO not sure why you changed this but it makes no sense to use map here. Suspend with the proper arrow direclty
    private[kyo] inline def defer[A, S](inline f: => A < S)(using inline frame: Frame): A < S =
        (new Kyo.Defer((), Arrow[Unit]): Unit < Any).map(_ => f)

end Effect
