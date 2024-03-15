package kyo

import kyo.core.*
import scala.reflect.ClassTag

object Aborts:

    type Abort[E] = {
        type Value[T] = Either[E, T]
    }

    def apply[E](using tag: Tag[E]): Aborts[E] =
        new Aborts(tag)

    def apply[T, E](ex: E)(using tag: Tag[E]): T < Aborts[E] =
        Aborts[E].get(Left(ex))
end Aborts

import Aborts.*

final case class Aborts[E] private[Aborts] (private val tag: Tag[E])
    extends Effect[Abort[E]#Value, Aborts[E]]:
    self =>

    private given Tag[E] = tag

    def fail[T, S](e: E < S): T < (Aborts[E] & S) =
        e.map(e => this.suspend(Left(e)))

    def run[T, S](
        v: T < (Aborts[E] & S)
    )(using
        flat: Flat[T < (Aborts[E] & S)],
        ct: ClassTag[E]
    ): Either[E, T] < S =
        this.handle(v)

    def get[T, S](v: Either[E, T] < S): T < (Aborts[E] & S) =
        v.map {
            case Right(value) => value
            case e            => this.suspend(e)
        }

    def catching[T, S](f: => T < S)(using ClassTag[E]): T < (Aborts[E] & S) =
        IOs.handle(f) {
            case ex: E =>
                fail(ex.asInstanceOf[E])
        }

    given handler[E](using tag: Tag[E], ct: ClassTag[E]): Handler[Abort[E]#Value, Aborts[E], Any] =
        new Handler[Abort[E]#Value, Aborts[E], Any]:

            val aborts = Aborts[E]

            def pure[U: Flat](v: U) = Right(v)

            override def handle(ex: Throwable): Nothing < Aborts[E] =
                ex match
                    case ex: E =>
                        aborts.fail(ex.asInstanceOf[E])
                    case _ =>
                        throw ex

            def apply[U, V: Flat, S2](
                m: Either[E, U],
                f: U => V < (Aborts[E] & S2)
            ): V < (S2 & Aborts[E]) =
                m match
                    case left: Left[?, ?] =>
                        aborts.get(left.asInstanceOf[Left[E, V]])
                    case Right(v) =>
                        f(v)

    def layer[Se](handle: E => Nothing < Se)(using ClassTag[E]): Layer[Aborts[E], Se] =
        new Layer[Aborts[E], Se]:
            override def run[T, S](
                effect: T < (Aborts[E] & S)
            )(
                using flat: Flat[T < (Aborts[E] & S)]
            ): T < (S & Se) =
                self.run[T, S](effect).map {
                    case Left(err) => handle(err)
                    case Right(t)  => t
                }
end Aborts
