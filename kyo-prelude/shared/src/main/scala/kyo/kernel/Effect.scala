package kyo.kernel

import internal.*
import kyo.Frame
import kyo.Tag
import scala.annotation.nowarn
import scala.util.control.NonFatal

abstract class Effect private[kernel] ()

object Effect:

    def defer[A, S](f: Safepoint ?=> A < S)(using _frame: Frame): A < S =
        new KyoDefer[A, S]:
            def frame = _frame
            def apply(v: Unit, context: Context)(using Safepoint) =
                f

    inline def catching[A, S, B >: A, S2](inline v: => A < S)(
        inline f: Throwable => B < S2
    )(using inline _frame: Frame, safepoint: Safepoint): B < (S & S2) =
        @nowarn("msg=anonymous")
        def catchingLoop(v: B < (S & S2))(using Safepoint): B < (S & S2) =
            (v: @unchecked) match
                case kyo: KyoSuspend[IX, OX, EX, Any, B, S & S2] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, B, S & S2](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            try catchingLoop(kyo(v, context))
                            catch
                                case ex: Throwable if NonFatal(ex) =>
                                    Safepoint.enrich(ex)
                                    f(ex)
                            end try
                        end apply
                case _ =>
                    v
        try catchingLoop(v)
        catch
            case ex: Throwable if NonFatal(ex) =>
                Safepoint.enrich(ex)
                f(ex)
        end try
    end catching
end Effect
