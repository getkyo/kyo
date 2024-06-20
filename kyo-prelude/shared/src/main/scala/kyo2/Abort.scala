package kyo2

import kernel.<
import kernel.Const
import kernel.Effect
import kernel.Frame
import kyo.Tag
import kyo2.kernel.Reducible
import scala.annotation.targetName
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Success
import scala.util.Try

sealed trait Abort[-E] extends Effect[Const[E], Const[Unit]]

object Abort:

    given eliminateAbort: Reducible.Eliminable[Abort[Nothing]] with {}
    private inline def erasedTag[E]: Tag[Abort[E]] = Tag[Abort[Any]].asInstanceOf[Tag[Abort[E]]]

    inline def failure[E](inline value: E): Nothing < Abort[E] =
        Effect.suspendMap[Any](erasedTag[E], value)(_ => ???)

    inline def when[E](b: Boolean)(inline value: E)(
        using inline tag: Tag[Abort[E]]
    ): Unit < Abort[E] =
        if b then failure(value)
        else ()

    final class GetOps[E >: Nothing](dummy: Unit) extends AnyVal:
        inline def apply[A](either: Either[E, A]): A < Abort[E] =
            either match
                case Right(value) => value
                case Left(value)  => failure(value)

        inline def apply[A](opt: Option[A]): A < Abort[None.type] =
            opt match
                case None    => failure(None)
                case Some(v) => v

        inline def apply[A](e: Try[A]): A < Abort[Throwable] =
            e match
                case Success(t) => t
                case Failure(v) => failure(v)

        inline def apply[E, A](r: Result[E, A]): A < Abort[E] =
            r.fold(Abort.failure(_), throw _, identity)

        @targetName("maybe")
        inline def apply[A](m: Maybe[A]): A < Abort[Maybe.Empty] =
            m.fold(failure(Maybe.Empty))(identity)
    end GetOps

    inline def get[E >: Nothing]: GetOps[E] = GetOps(())

    final class RunOps[E >: Nothing](dummy: Unit) extends AnyVal:
        def apply[A, S, ES, ER](v: A < (Abort[E | ER] & S))(
            using
            ct: ClassTag[E],
            frame: Frame,
            reduce: Reducible[Abort[ER]]
        ): Result[E, A] < (S & reduce.SReduced) =
            Effect.catching {
                reduce {
                    Effect.handle[Const[E], Const[Unit], Abort[E], Result[E, A], Result[E, A], Abort[ER] & S, Abort[ER] & S, Any](
                        erasedTag[E],
                        v.map(Result.success)
                    )(
                        accept = [C] =>
                            (input, _) =>
                                (input.asInstanceOf[Any]) match
                                    case input: E => true
                                    case _        => false,
                        handle = [C] => (input, _) => Result.failure(input)
                    )
                }
            } {
                case fail: E => Result.failure(fail)
            }
    end RunOps

    inline def run[E >: Nothing]: RunOps[E] = RunOps(())

    final class CatchingOps[E <: Throwable](dummy: Unit) extends AnyVal:
        def apply[A, B](v: => A < B)(
            using
            ct: ClassTag[E],
            frame: Frame
        ): A < (Abort[E] & B) =
            Effect.catching(v) {
                case ex: E => Abort.failure(ex)
            }
    end CatchingOps

    inline def catching[E <: Throwable]: CatchingOps[E] = CatchingOps(())
end Abort
