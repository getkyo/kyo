package kyo.kernel

import internal.*
import kyo.Frame
import scala.annotation.nowarn
import scala.util.control.NonFatal

/** The base trait for all effects in the Kyo effect system.
  *
  * When code performs an effectful operation, instead of executing immediately, effects create a suspended computation that captures what
  * needs to be done. These suspended computations can then be interpreted in different ways through effect handlers.
  *
  * This suspension mechanism is the foundation of Kyo's effect system. It allows effectful code to be pure and composable - rather than
  * performing operations directly, code builds up a description of what operations should occur. This description can then be interpreted
  * by handlers that determine how the operations are actually executed.
  *
  * There are two kinds of effects:
  *   - [[ArrowEffect]] for suspended computations involving input/output transformations.
  *   - [[ContextEffect]] for suspended computations requiring contextual values.
  */
abstract class Effect private[kernel] ()

object Effect:

    /** Wraps a computation with error handling.
      *
      * This method allows you to catch and handle exceptions that might occur during the execution of a computation. The error handler `f`
      * will be called if a non-fatal exception occurs either during the initial evaluation or during any subsequent effect operations.
      *
      * @param v
      *   the effect computation to protect
      * @param f
      *   the error handler function that takes a Throwable and returns a new effect
      * @return
      *   a new effect that will either complete normally or handle exceptions using the provided handler
      */
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

    private[kyo] def defer[A, S](f: Safepoint ?=> A < S)(using _frame: Frame): A < S =
        new KyoDefer[A, S]:
            def frame = _frame
            def apply(v: Unit, context: Context)(using Safepoint) =
                f
end Effect
