package kyo

import kyo.core.*
import scala.reflect.ClassTag
import scala.util.control.NonFatal

// Can't use an opaque type because
// kyo-direct tests crash the compiler.
type Aborts[-V] //= DoAbort

object Aborts:

    import internal.*

    def fail[V](v: V): Nothing < Aborts[V] =
        DoAbort.suspend(v).asInstanceOf[Nothing < Aborts[V]]

    def when[V](b: Boolean)(value: => V): Unit < Aborts[V] =
        if b then fail(value)
        else ()

    def get[V, T](e: Either[V, T]): T < Aborts[V] =
        e match
            case Right(v) => v
            case Left(v)  => fail(v)

    class RunDsl[V]:
        def apply[V0 <: V, T: Flat, S, VS, VR](v: T < (Aborts[VS] & S))(
            using
            h: HasAborts[V0, VS] { type Remainder = VR },
            ct: ClassTag[V0]
        ): Either[V, T] < (VR & S) =
            DoAbort.handle(handler)(ct, v).asInstanceOf[Either[V, T] < (VR & S)]
    end RunDsl

    def run[V]: RunDsl[V] = RunDsl[V]

    class CatchingDsl[V <: Throwable]:
        def apply[T: Flat, S](v: => T < S)(
            using ct: ClassTag[V]
        ): T < (Aborts[V] & S) =
            IOs.catching(v) {
                case ex: V => Aborts.fail(ex)
            }
    end CatchingDsl

    def catching[V <: Throwable]: CatchingDsl[V] = CatchingDsl[V]

    private object internal:

        val handler =
            new ResultHandler[ClassTag[?], Const[Any], DoAbort, [T] =>> Either[Any, T], Any]:
                def done[T](st: ClassTag[?], v: T) = Right(v)

                override def failed(st: ClassTag[?], ex: Throwable) =
                    type V
                    given ClassTag[V] = st.asInstanceOf[ClassTag[V]]
                    ex match
                        case ex: V => DoAbort.suspend(ex)
                        case _     => throw ex
                end failed

                override def accepts[T](st: ClassTag[?], command: Any) =
                    type V
                    given ClassTag[V] = st.asInstanceOf[ClassTag[V]]
                    command match
                        case v: V => true
                        case _    => false
                end accepts

                def resume[T, U: Flat, S2](st: ClassTag[?], command: Any, k: T => U < (DoAbort & S2)) =
                    Left(command)
        end handler

        class DoAbort extends Effect[DoAbort]:
            type Command[T] = Any
        object DoAbort extends DoAbort

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
    end internal
end Aborts
