package kyo2

import kernel.<
import kernel.Const
import kernel.Effect
import kernel.Frame
import kyo.Tag
import kyo2.Result.*
import kyo2.kernel.Reducible
import scala.annotation.targetName
import scala.reflect.ClassTag

sealed trait Abort[-E] extends Effect[Const[Failure[E]], Const[Unit]]

object Abort:

    given eliminateAbort: Reducible.Eliminable[Abort[Nothing]] with {}
    private inline def erasedTag[E]: Tag[Abort[E]] = Tag[Abort[Any]].asInstanceOf[Tag[Abort[E]]]

    inline def error[E](inline value: E): Nothing < Abort[E] =
        Effect.suspendMap[Any](erasedTag[E], Error(value))(_ => ???)

    inline def panic[E](ex: Throwable): Nothing < Abort[E] =
        Effect.suspendMap[Any](erasedTag[E], Panic(ex))(_ => ???)

    inline def when[E](b: Boolean)(inline value: => E): Unit < Abort[E] =
        if b then error(value)
        else ()

    final class GetOps[E >: Nothing](dummy: Unit) extends AnyVal:
        inline def apply[A](either: Either[E, A]): A < Abort[E] =
            either match
                case Right(value) => value
                case Left(value)  => error(value)

        inline def apply[A](opt: Option[A]): A < Abort[Maybe.Empty] =
            opt match
                case None    => error(Maybe.Empty)
                case Some(v) => v

        inline def apply[A](e: scala.util.Try[A]): A < Abort[Throwable] =
            e match
                case scala.util.Success(t) => t
                case scala.util.Failure(v) => error(v)

        inline def apply[E, A](r: Result[E, A]): A < Abort[E] =
            r.fold {
                case e: Error[E] => error(e.error)
                case Panic(ex)   => Abort.panic(ex)
            }(identity)

        @targetName("maybe")
        inline def apply[A](m: Maybe[A]): A < Abort[Maybe.Empty] =
            m.fold(error(Maybe.Empty))(identity)
    end GetOps

    inline def get[E >: Nothing]: GetOps[E] = GetOps(())

    final class RunOps[E >: Nothing](dummy: Unit) extends AnyVal:
        def apply[A, S, ES, ER](v: => A < (Abort[E | ER] & S))(
            using
            ct: ClassTag[E],
            tag: Tag[E], // TODO Used only to ensure E isn't a type union. There should be a more lightweight solution for this.
            frame: Frame,
            reduce: Reducible[Abort[ER]]
        ): Result[E, A] < (S & reduce.SReduced) =
            Effect.catching {
                reduce {
                    Effect.handle[Const[Failure[E]], Const[Unit], Abort[E], Result[E, A], Result[E, A], Abort[ER] & S, Abort[ER] & S, Any](
                        erasedTag[E],
                        v.map(Result.success[E, A](_))
                    )(
                        accept = [C] =>
                            input =>
                                input.isPanic ||
                                    input.asInstanceOf[Failure[Any]].error.exists {
                                        case e: E => true
                                        case _    => false
                                },
                        handle = [C] =>
                            (input, _) => input
                    )
                }
            } {
                case fail: E => Result.error(fail)
                case fail    => Result.panic(fail)
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
                case ex: E => Abort.error(ex)
            }
    end CatchingOps

    inline def catching[E <: Throwable]: CatchingOps[E] = CatchingOps(())
end Abort
