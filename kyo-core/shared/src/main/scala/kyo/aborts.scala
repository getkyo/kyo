package kyo

import kyo.core.*
import scala.reflect.ClassTag
import scala.util.control.NonFatal

class Aborts[-V] extends Effect[Aborts[V]]:
    opaque type Command[T] = Left[V, Nothing]

    private[Aborts] inline def _suspend[B <: V, T](v: V)(
        using tag: Tag[Aborts[B]]
    ): T < Aborts[B] =
        suspend(this)(Left(v))
end Aborts

object Aborts:
    private case object aborts extends Aborts[Any]
    def apply[V]: Aborts[V] = aborts.asInstanceOf[Aborts[V]]

    type Elide[V] =
        V match
            case Nothing => Any
            case V       => Aborts[V]

    extension [V](self: Aborts[V])

        def fail[T](value: T)(using ev: T => V, t: Tag[Aborts[V]]): Nothing < Aborts[V] =
            self._suspend(ev(value))

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

        def run[T, S, V2](v: T < (Aborts[V | V2] & S))(
            using
            Tag[Aborts[V]],
            ClassTag[V],
            Flat[T < (Aborts[V] & S)]
        ): Either[V, T] < (S & Elide[V2]) =
            handle(handler, v).asInstanceOf[Either[V, T] < (S & Elide[V2])]

        def layer[Se](handle: V => Nothing < Se)(
            using
            ClassTag[V],
            Tag[Aborts[V]]
        ): Layer[Aborts[V], Se] =
            new Layer[Aborts[V], Se]:
                override def run[T, S](
                    effect: T < (Aborts[V] & S)
                )(
                    using flat: Flat[T < (Aborts[V] & S)]
                ): T < (S & Se) =
                    self.run(effect).map {
                        case Left(err) => handle(err)
                        case Right(t)  => t
                    }

        private def handler(using ClassTag[V], Tag[Aborts[V]]) =
            new ResultHandler[Const[Left[V, Nothing]], [T] =>> Either[V, T], Aborts[V], Any]:
                def done[T](v: T) = Right(v)

                override def failed(ex: Throwable): Nothing < Aborts[V] =
                    ex match
                        case ex: V =>
                            self.fail(ex)
                        case _ =>
                            throw ex

                def resume[T, U: Flat, S](
                    command: Left[V, Nothing],
                    k: T => U < (Aborts[V] & S)
                ) = command
    end extension
end Aborts
