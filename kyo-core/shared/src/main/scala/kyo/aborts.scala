package kyo

import kyo.core.*
import scala.reflect.ClassTag

object Aborts:
    case object aborts extends Aborts[Any]
    def apply[V]: Aborts[V] = aborts.asInstanceOf[Aborts[V]]

class Aborts[V] extends Effect[Aborts[V]]:
    self =>
    override type Command[T] = Left[V, Nothing]

    def fail(value: V)(using Tag[Aborts[V]]): Nothing < Aborts[V] =
        suspend(Aborts[V])(Left(value))

    def when(b: Boolean)(value: V)(using Tag[Aborts[V]]): Unit < Aborts[V] =
        if b then fail(value)
        else ()

    def get[T](e: Either[V, T])(using Tag[Aborts[V]]): T < Aborts[V] =
        e match
            case Right(value) => value
            case Left(e)      => fail(e)

    def catching[T, S](v: => T < S)(using ClassTag[V], Tag[Aborts[V]]): T < (Aborts[V] & S) =
        IOs.handle(v) {
            case ex: V =>
                fail(ex.asInstanceOf[V])
        }

    def run[T, S](v: T < (Aborts[V] & S))(
        using
        Tag[Aborts[V]],
        ClassTag[V],
        Flat[T < (Aborts[V] & S)]
    ): Either[V, T] < S =
        handle(handler, v)

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
                self.run[T, S](effect).map {
                    case Left(err) => handle(err)
                    case Right(t)  => t
                }

    private def handler(using ClassTag[V]) =
        new ResultHandler[Const[Left[V, Nothing]], [T] =>> Either[V, T], Aborts[V], Any]:
            def pure[T](v: T) = Right(v)

            override def fail(ex: Throwable): Nothing < Aborts[V] =
                ex match
                    case ex: V =>
                        self.fail(ex)
                    case _ =>
                        throw ex

            def resume[T, U: Flat, S](
                command: Left[V, Nothing],
                k: T => U < (Aborts[V] & S)
            ) = command
end Aborts
