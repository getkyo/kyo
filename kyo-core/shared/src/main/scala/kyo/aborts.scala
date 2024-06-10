package kyo

import kyo.core.*
import kyo.internal.Trace
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

type Aborts[-V] >: Aborts.Effects[V] <: Aborts.Effects[V]

object Aborts:

    import internal.*

    opaque type Effects[-V] = DoAbort

    inline def fail[V](inline v: V): Nothing < Aborts[V] =
        suspend[Any](Tag[DoAbort], Left(v), _ => ???)

    inline def when[V](b: Boolean)(inline value: => V): Unit < Aborts[V] =
        if b then fail(value)
        else ()

    inline def get[V, T](inline e: Either[V, T]): T < Aborts[V] =
        e match
            case Right(t) => t
            case Left(v)  => fail(v)

    inline def get[T](inline e: Try[T]): T < Aborts[Throwable] =
        e match
            case Success(t) => t
            case Failure(v) => fail(v)

    class RunDsl[V](ign: Unit) extends AnyVal:
        def apply[V0 <: V, T, S, VS, VR](v: T < (Aborts[VS] & S))(
            using
            h: HasAborts[V0, VS] { type Remainder = VR },
            ct: ClassTag[V0],
            trace: Trace
        ): Either[V, T] < (VR & S) =
            IOs.catching {
                handle[Const[Left[Any, Any]], Const[Unit], DoAbort, Either[V, T], S, Any](
                    Tag[DoAbort],
                    v.map(Right(_))
                )(
                    accept = [C] =>
                        input =>
                            input match
                                case v0: V0 => true
                                case _      => false,
                    handle = [C] =>
                        (input, _) =>
                            Left(input.asInstanceOf[V])
                )
            } {
                case ex: V0 => Left(ex)
            }.asInstanceOf[Either[V, T] < (VR & S)]
        end apply
    end RunDsl

    inline def run[V]: RunDsl[V] = RunDsl(())

    class CatchingDsl[V <: Throwable](ign: Unit) extends AnyVal:
        def apply[T, S](v: => T < S)(
            using
            ct: ClassTag[V],
            trace: Trace
        ): T < (Aborts[V] & S) =
            IOs.catching(v) {
                case ex: V => Aborts.fail(ex)
            }
    end CatchingDsl

    inline def catching[V <: Throwable]: CatchingDsl[V] = CatchingDsl(())

    private object internal:
        sealed trait DoAbort extends Effect[Const[Left[Any, Any]], Const[Unit]]

    /** An effect `Aborts[VS]` includes a failure type `V`, and once `V` has been handled, `Aborts[VS]` should be replaced by `Out`
      *
      * @tparam V
      *   the failure type included in `VS`
      * @tparam VS
      *   all of the `Aborts` failure types represented by type union
      */
    sealed trait HasAborts[V, VS]:
        /** Remaining effect type, once failures of type `V` have been handled
          */
        type Remainder
    end HasAborts

    trait LowPriorityHasAborts:
        given hasAborts[V, VR]: HasAborts[V, V | VR] with
            type Remainder = Aborts[VR]

    object HasAborts extends LowPriorityHasAborts:
        given isAborts[V]: HasAborts[V, V] with
            type Remainder = Any
end Aborts
