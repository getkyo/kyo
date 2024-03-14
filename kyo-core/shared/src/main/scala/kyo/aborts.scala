package kyo

import izumi.reflect.*
import kyo.core.*

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

final class Aborts[E] private[Aborts] (private val tag: Tag[E])
    extends Effect[Abort[E]#Value, Aborts[E]]:
    self =>

    private given Tag[E] = tag

    def fail[T, S](e: E < S): T < (Aborts[E] & S) =
        e.map(e => this.suspend(Left(e)))

    def run[T, S](
        v: T < (Aborts[E] & S)
    )(implicit
        flat: Flat[T < (Aborts[E] & S)]
    ): Either[E, T] < S =
        this.handle[T, S, Any](v)

    def get[T, S](v: => Either[E, T] < S): T < (Aborts[E] & S) =
        catching(v).map {
            case Right(value) => value
            case e            => this.suspend(e)
        }

    def catching[T, S](f: => T < S): T < (Aborts[E] & S) =
        IOs.handle(f) {
            case ex if (tag.closestClass.isAssignableFrom(ex.getClass)) =>
                fail(ex.asInstanceOf[E])
        }

    override def accepts[M2[_], E2 <: Effect[M2, E2]](other: Effect[M2, E2]) =
        other match
            case other: Aborts[?] =>
                other.tag.tag == tag.tag
            case _ =>
                false

    given handler[E](using tag: Tag[E]): Handler[Abort[E]#Value, Aborts[E], Any] =
        new Handler[Abort[E]#Value, Aborts[E], Any]:

            val aborts = Aborts[E]

            def pure[U: Flat](v: U) = Right(v)

            override def handle(ex: Throwable): Nothing < Aborts[E] =
                if tag.closestClass.isAssignableFrom(ex.getClass) then
                    aborts.fail(ex.asInstanceOf[E])
                else
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

    override def toString = s"Aborts[${tag.tag.longNameWithPrefix}]"

    def layer[Se](handle: E => Nothing < Se): Layer[Aborts[E], Se] =
        new Layer[Aborts[E], Se]:
            override def run[T, S](
                effect: T < (Aborts[E] & S)
            )(
                using flat: Flat[T < (Aborts[E] & S)]
            ): T < (S & Se) =
                self.run[T, S](effect)(flat).map {
                    case Left(err) => handle(err)
                    case Right(t)  => t
                }
end Aborts
