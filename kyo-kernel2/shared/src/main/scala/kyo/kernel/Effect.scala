package kyo.kernel

import kyo.Arrow
import kyo.Frame
import kyo.kernel.`<`.fromKyo
import kyo.kernel.Implicits.liftInternal
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.util.control.NonFatal

abstract class Effect private[kernel] ()

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

    abstract private[kyo] class Guard[In, B, S] extends Arrow.Transform[In, B, S]:
        def wrapped: Arrow[In, B, S]

    @nowarn("msg=anonymous")
    private def guarded[B, S](v: B < S, f: Throwable => B < S, _frame: Frame): B < S =
        def guard[In](cont: Arrow[In, B, S]): Guard[In, B, S] =
            new Guard[In, B, S]:
                def frame   = _frame
                def wrapped = cont
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
                // the drive applies each layer's continuation directly, so the
                // guard covers the whole value spine, one guard per layer,
                // innermost first
                @tailrec def collect(cur: Any, acc: List[Arrow[Any, B, S]]): (Any, List[Arrow[Any, B, S]]) =
                    cur match
                        case d: Kyo.Defer[Any, Any, S] @unchecked =>
                            collect(d.value, d.cont.asInstanceOf[Arrow[Any, B, S]] :: acc)
                        case base =>
                            (base, acc)
                val (base, conts) = collect(kyo, Nil)
                conts
                    .foldLeft(base) { (acc, cont) =>
                        Kyo.defer(acc.asInstanceOf[Any < S], guard(cont))
                    }
                    .asInstanceOf[B < S]
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
