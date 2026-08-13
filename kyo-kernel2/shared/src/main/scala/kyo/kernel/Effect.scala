package kyo.kernel

import kyo.Arrow
import kyo.Frame
import kyo.kernel.`<`.fromKyo
import kyo.kernel.Implicits.liftInternal
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
            case ex: Throwable if NonFatal(ex) => recover(ex, f, _frame)

    // the guarded computation has already been consumed here, so the catching
    // site's own frame is all this boundary can contribute; the enrichment
    // happens before f runs, so a user's recovery sees the enriched exception
    private def recover[B, S](ex: Throwable, f: Throwable => B < S, _frame: Frame): B < S =
        EffectTrace.attach(ex, _frame)
        EffectTrace.splice(ex)
        f(ex)
    end recover

    // the guard lives in the continuation arrow: each resumed step applies
    // the original continuation under the handler and re-wraps what it
    // produces, so later steps stay guarded. A region node's internals
    // evaluate at eval and are not covered; its exit steps are
    @nowarn("msg=anonymous")
    // TODO this seems an expensive workaround for something that should be handled in Eval or Arrow?
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
                                // cont holds the steps that were running inside the
                                // guard, next the steps that follow it: the failing
                                // step's own frame is in cont
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
            case k: Kyo.Suspend[[Z] =>> Any, [Z] =>> Any, Nothing, Any, B, S] @unchecked =>
                new Kyo.Suspend[[Z] =>> Any, [Z] =>> Any, Nothing, Any, B, S]:
                    override val root = k.root
                    def tag           = root.tag
                    def input         = root.input
                    def frame         = root.frame
                    val cont          = guard(k.cont)
                end new
            case kyo: Kyo.Defer[Any, B, S] @unchecked =>
                Kyo.Defer[Any, B, S](kyo.value, guard(kyo.cont))
            case kyo: Kyo.Handled[[Z] =>> Any, [Z] =>> Any, Nothing, Any, B, S, Any] @unchecked =>
                Kyo.Handled[[Z] =>> Any, [Z] =>> Any, Nothing, Any, B, S, Any](kyo.value, kyo.handler, guard(kyo.exit))
            case kyo: Kyo.HandledFirst[[Z] =>> Any, [Z] =>> Any, Nothing, Any, Any, B, S, Any, Any] @unchecked =>
                Kyo.HandledFirst[[Z] =>> Any, [Z] =>> Any, Nothing, Any, Any, B, S, Any, Any](kyo.value, kyo.handler, guard(kyo.exit))
            case kyo: Kyo.HandledState[[Z] =>> Any, [Z] =>> Any, Nothing, Any, B, S, Any, Any] @unchecked =>
                Kyo.HandledState[[Z] =>> Any, [Z] =>> Any, Nothing, Any, B, S, Any, Any](
                    kyo.value,
                    kyo.handler,
                    guard(kyo.exit),
                    kyo.state
                )
            case v =>
                v
        end match
    end guarded

    @nowarn("msg=anonymous")
    private[kyo] inline def defer[A, S](inline f: => A < S)(using inline _frame: Frame): A < S =
        Kyo.Defer[Unit, A, S](
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
