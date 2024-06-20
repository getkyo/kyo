package kyo2

import kyo.Tag
import kyo2.kernel.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

sealed trait IO extends Effect[Const[Unit], Const[Unit]]

object IO:

    inline def apply[T, S](inline f: => T < S): T < (IO & S) =
        Effect.suspendMap[Any](Tag[IO], ())(_ => f)

    inline def defer[T](inline f: => T): T < IO =
        Effect.suspendMap[Any](Tag[IO], ())(_ => f)

    def run[T, S](v: T < IO)(using Frame): T < Any =
        runLazy(v)

    def runLazy[T, S](v: T < (IO & S))(using Frame): T < S =
        Effect.handle(Tag[IO], v) {
            [C] => (_, cont) => cont(())
        }

    def fail(ex: Throwable)(using Frame): Nothing < IO =
        IO(throw ex)

    def fail(msg: String)(using Frame): Nothing < IO =
        IO(throw new Exception(msg))

    def fromTry[T](v: Try[T])(using Frame): T < IO =
        v match
            case Success(v)  => v
            case Failure(ex) => fail(ex)

    def toTry[T, S](v: => T < S)(using Frame): Try[T] < (IO & S) =
        catching(v.map(Success(_): Try[T]))(Failure(_))

    def catching[T, S, U >: T, S2](v: => T < S)(
        pf: PartialFunction[Throwable, U < S2]
    )(using Frame): U < (IO & S & S2) =
        IO(Effect.catching(v)(pf))

end IO
