package kyo.kernel3

import kyo.Frame
import kyo.kernel3.`<`.fromKyo
import kyo.kernel3.Implicits.liftInternal
import kyo.kernel3.internal.*
import scala.annotation.nowarn
import scala.util.control.NonFatal

abstract class Effect private[kernel3] ()

object Effect:

    inline def catching[A, S, B >: A, S2](inline v: => A < S)(
        inline f: Throwable => B < S2
    )(using inline _frame: Frame): B < (S & S2) =
        try guarded(v: B < (S & S2), f, _frame)
        catch
            case ex: Throwable if NonFatal(ex) => recover(ex, f, _frame)

    private def recover[B, S](ex: Throwable, f: Throwable => B < S, _frame: Frame): B < S =
        EffectTrace.attach(ex, _frame)
        EffectTrace.splice(ex)
        f(ex)
    end recover

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
                            case ex: Throwable if NonFatal(ex) =>
                                EffectTrace.attach(ex, _frame, cont, next)
                                EffectTrace.splice(ex)
                                f(ex)
                    (w: @unchecked) match
                        case kyo: Kyo[B, S] @unchecked =>
                            kyo.map(next.asInstanceOf[Arrow[B, C, S]]).asInstanceOf[C < S3]
                        case w =>
                            val step = next.step
                            step.head(w.asInstanceOf[B < S3], step.tail)
                    end match
                end apply
        (v: @unchecked) match
            case kyo: Kyo.Defer[Any, B, S] @unchecked =>
                Kyo.defer(kyo.value, guard(kyo.cont))
            case v =>
                v
        end match
    end guarded

    @nowarn("msg=anonymous")
    private[kyo] inline def defer[A, S](inline f: => A < S)(using inline _frame: Frame): A < S =
        Kyo.defer[Unit, A, S](
            (),
            new Arrow.Transform[Unit, A, S]:
                def frame = _frame
                def apply[C, S2](v: Unit < S2, next: Arrow[A, C, S2]) =
                    f match
                        case kyo: Kyo[A, S] @unchecked =>
                            kyo.map(next)
                        case a =>
                            val step = next.step
                            step.head(a.asInstanceOf[A < S2], step.tail)
        )

end Effect
