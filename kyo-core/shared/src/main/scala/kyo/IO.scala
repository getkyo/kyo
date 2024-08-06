package kyo

import java.util.concurrent.atomic.AtomicReference
import kyo.Tag
import kyo.kernel.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

sealed trait IO extends Effect[Const[Unit], Const[Unit]]

object IO:

    inline def unit: Unit < IO = ()

    inline def apply[T, S](inline f: => T < S)(using inline frame: Frame): T < (IO & S) =
        Effect.suspendMap[Any](Tag[IO], ())(_ => f)

    inline def defer[T](inline f: => T)(using inline frame: Frame): T < IO =
        Effect.suspendMap[Any](Tag[IO], ())(_ => f)

    inline def ensure[T, S](inline f: => Unit < IO)(v: T < S)(using inline frame: Frame): T < (IO & S) =
        IO(Safepoint.ensure(IO.run(f).eval)(v))

    def run[T, S](v: T < IO)(using Frame): T < Any =
        runLazy(v)

    def runLazy[T, S](v: T < (IO & S))(using Frame): T < S =
        Effect.handle(Tag[IO], v) {
            [C] => (_, cont) => cont(())
        }
end IO
