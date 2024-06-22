package kyo2

import kernel.<
import kernel.Const
import kernel.Effect
import kernel.Frame
import kyo.Tag
import scala.annotation.targetName
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Success
import scala.util.Try

sealed trait Abort[-E] extends Effect[Const[E], Const[Unit]]

object Abort:

    private inline def erasedTag[E]: Tag[Abort[E]] = Tag[Abort[Any]].asInstanceOf[Tag[Abort[E]]]

    inline def fail[E](inline value: E): Nothing < Abort[E] =
        Effect.suspendMap[Any](erasedTag[E], value)(_ => ???)

    inline def when[E](b: Boolean)(inline value: E)(
        using inline tag: Tag[Abort[E]]
    ): Unit < Abort[E] =
        if b then fail(value)
        else ()

    class GetOps[E >: Nothing](dummy: Unit) extends AnyVal:
        inline def apply[A](either: Either[E, A]): A < Abort[E] =
            either match
                case Right(value) => value
                case Left(value)  => fail(value)

        inline def apply[A](opt: Option[A]): A < Abort[None.type] =
            opt match
                case None    => fail(None)
                case Some(v) => v

        inline def apply[A](e: Try[A]): A < Abort[Throwable] =
            e match
                case Success(t) => t
                case Failure(v) => fail(v)

        inline def apply[A](r: Result[A]): A < Abort[Throwable] =
            r.fold(fail)(identity)

        @targetName("maybe")
        inline def apply[A](m: Maybe[A]): A < Abort[Maybe.Empty] =
            m.fold(fail(Maybe.Empty))(identity)
    end GetOps

    inline def get[E >: Nothing]: GetOps[E] = GetOps(())

    class RunOps[E >: Nothing](dummy: Unit) extends AnyVal:
        def apply[E0 <: E, A, B, ES, ER](v: A < (Abort[ES] & B))(
            using
            h: HasAbort[E0, ES] { type Remainder = ER },
            ct: ClassTag[E],
            frame: Frame
        ): Either[E, A] < (ER & B) =
            Effect.catching {
                Effect.handle(erasedTag[E], v.map(Right(_): Either[E, A]))(
                    accept = [C] =>
                        (input, _) =>
                            (input.asInstanceOf[Any]) match
                                case input: E => true
                                case _        => false,
                    handle = [C] => (input, _) => Left(input)
                ).asInstanceOf[Either[E, A] < ER & B]
            } {
                case fail: E => Left(fail)
            }
    end RunOps

    inline def run[E >: Nothing]: RunOps[E] = RunOps(())

    class CatchingOps[E <: Throwable](dummy: Unit) extends AnyVal:
        def apply[A, B](v: => A < B)(
            using
            ct: ClassTag[E],
            frame: Frame
        ): A < (Abort[E] & B) =
            Effect.catching(v) {
                case ex: E => Abort.fail(ex)
            }
    end CatchingOps

    inline def catching[E <: Throwable]: CatchingOps[E] = CatchingOps(())

    sealed trait HasAbort[E, ES]:
        type Remainder

    trait LowPriorityHasAbort:
        given hasAbort[E, ER]: HasAbort[E, E | ER] with
            type Remainder = Abort[ER]

    object HasAbort extends LowPriorityHasAbort:
        given isAbort[E]: HasAbort[E, E] with
            type Remainder = Any
end Abort
