package kyo

import kyo.core.*
import scala.reflect.ClassTag
import scala.util.control.NonFatal

class Aborts[-V] extends Effect[Aborts[V]]:
    opaque type Command[T] >: Left[V, Nothing] = Left[V, Nothing]

object Aborts:
    private case object aborts extends Aborts[Any]
    def apply[V]: Aborts[V] = aborts.asInstanceOf[Aborts[V]]

    extension [V](self: Aborts[V])

        def fail[T](value: T)(using ev: T => V, t: Tag[Aborts[V]]): Nothing < Aborts[V] =
            self.suspend[Nothing](Left(ev(value)))

        def when(b: Boolean)(value: V)(using Tag[Aborts[V]]): Unit < Aborts[V] =
            if b then fail(value)
            else ()

        def get[T](e: Either[V, T])(using Tag[Aborts[V]]): T < Aborts[V] =
            e match
                case Right(value) => value
                case Left(e)      => fail(e)

        def catching[T, S](v: => T < S)(
            using
            ClassTag[V],
            Tag[Aborts[V]]
        ): T < (Aborts[V] & S) =
            IOs.handle(v) {
                case ex: V =>
                    fail(ex.asInstanceOf[V])
            }

        def run[T: Flat, S, VS, VR](v: T < (Aborts[VS] & S))(
            using
            HasAborts[V, VS] { type Remainder = VR },
            Tag[Aborts[V]],
            ClassTag[V]
        ): Either[V, T] < (S & VR) =
            self.handle(handler)((), v).asInstanceOf[Either[V, T] < (S & VR)]

        private def handler(using ClassTag[V], Tag[Aborts[V]]) =
            new ResultHandler[Unit, self.Command, Aborts[V], [T] =>> Either[V, T], Any]:
                def done[T](st: Unit, v: T) = Right(v)

                override def failed(st: Unit, ex: Throwable) =
                    ex match
                        case ex: V =>
                            self.fail(ex)
                        case _ =>
                            throw ex

                def resume[T, U: Flat, S](
                    st: Unit,
                    command: self.Command[T],
                    k: T => U < (Aborts[V] & S)
                ) =
                    command.asInstanceOf[Either[V, U]]
    end extension

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
