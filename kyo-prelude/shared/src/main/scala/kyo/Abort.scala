package kyo

import kernel.ArrowEffect
import kernel.Const
import kernel.Frame
import kyo.Result.*
import kyo.Tag
import kyo.kernel.Effect
import kyo.kernel.Reducible
import scala.annotation.targetName
import scala.reflect.ClassTag

sealed trait Abort[-E] extends ArrowEffect[Const[Error[E]], Const[Unit]]

object Abort:

    given eliminateAbort: Reducible.Eliminable[Abort[Nothing]] with {}
    private inline def erasedTag[E]: Tag[Abort[E]] = Tag[Abort[Any]].asInstanceOf[Tag[Abort[E]]]

    inline def fail[E](inline value: E)(using inline frame: Frame): Nothing < Abort[E] = error(Fail(value))

    inline def panic[E](inline ex: Throwable)(using inline frame: Frame): Nothing < Abort[E] = error(Panic(ex))

    private[kyo] inline def error[E](inline e: Error[E])(using inline frame: Frame): Nothing < Abort[E] =
        ArrowEffect.suspendMap[Any](erasedTag[E], e)(_ => ???)

    inline def when[E](b: Boolean)(inline value: => E)(using inline frame: Frame): Unit < Abort[E] =
        if b then fail(value)
        else ()

    final class GetOps[E >: Nothing](dummy: Unit) extends AnyVal:
        inline def apply[A](either: Either[E, A])(using inline frame: Frame): A < Abort[E] =
            either match
                case Right(value) => value
                case Left(value)  => fail(value)

        inline def apply[A](opt: Option[A])(using inline frame: Frame): A < Abort[Maybe.Empty] =
            opt match
                case None    => fail(Maybe.Empty)
                case Some(v) => v

        inline def apply[A](e: scala.util.Try[A])(using inline frame: Frame): A < Abort[Throwable] =
            e match
                case scala.util.Success(t) => t
                case scala.util.Failure(v) => fail(v)

        inline def apply[E, A](r: Result[E, A])(using inline frame: Frame): A < Abort[E] =
            r.fold {
                case e: Fail[E] => fail(e.error)
                case Panic(ex)  => Abort.panic(ex)
            }(identity)

        @targetName("maybe")
        inline def apply[A](m: Maybe[A])(using inline frame: Frame): A < Abort[Maybe.Empty] =
            m.fold(fail(Maybe.Empty))(identity)
    end GetOps

    inline def get[E >: Nothing]: GetOps[E] = GetOps(())

    final class RunOps[E >: Nothing](dummy: Unit) extends AnyVal:
        def apply[A: Flat, S, ES, ER](v: => A < (Abort[E | ER] & S))(
            using
            ct: ClassTag[E],
            tag: Tag[E], // TODO Used only to ensure E isn't a type union. There should be a more lightweight solution for this.
            frame: Frame,
            reduce: Reducible[Abort[ER]]
        ): Result[E, A] < (S & reduce.SReduced) =
            reduce {
                ArrowEffect.handle.catching[
                    Const[Error[E]],
                    Const[Unit],
                    Abort[E],
                    Result[E, A],
                    Result[E, A],
                    Abort[ER] & S,
                    Abort[ER] & S,
                    Any
                ](
                    erasedTag[E],
                    v.map(Result.success[E, A](_))
                )(
                    accept = [C] =>
                        input =>
                            input.isPanic ||
                                input.asInstanceOf[Error[Any]].failure.exists {
                                    case e: E => true
                                    case _    => false
                            },
                    handle = [C] => (input, _) => input,
                    recover =
                        case fail: E if classOf[Throwable].isAssignableFrom(ct.runtimeClass) =>
                            Result.fail(fail)
                        case fail => Result.panic(fail)
                )
            }
    end RunOps

    inline def run[E >: Nothing]: RunOps[E] = RunOps(())

    final class CatchingOps[E <: Throwable](dummy: Unit) extends AnyVal:
        def apply[A, S](v: => A < S)(
            using
            ct: ClassTag[E],
            frame: Frame
        ): A < (Abort[E] & S) =
            Effect.catching(v) {
                case ex: E => Abort.fail(ex)
                case ex    => Abort.panic(ex)
            }
    end CatchingOps

    inline def catching[E <: Throwable]: CatchingOps[E] = CatchingOps(())

end Abort
