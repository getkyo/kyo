package kyo

import kyo.Maybe.Absent
import kyo.Maybe.Present

abstract class KyoAppPlatformSpecific extends KyoApp.Base[Async & Resource & Abort[Throwable]]:

    private var maybePreviousAsync: Maybe[Unit < (Async & Abort[Throwable])] = Absent

    final override protected def run[A: Flat](v: => A < (Async & Resource & Abort[Throwable]))(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        val currentAsync: Unit < (Async & Abort[Throwable]) =
            Abort.run(handle(v)).map(result => IO(printResult(result)).andThen(Abort.get(result)).unit)
        maybePreviousAsync = maybePreviousAsync match
            case Absent                 => Present(currentAsync)
            case Present(previousAsync) => Present(previousAsync.map(_ => currentAsync))
        initCode = maybePreviousAsync.map { previousAsync => () =>
            val racedAsyncIO = Clock.repeatWithDelay(1.hour)(()).map { fiber =>
                val race = Async.race(Seq(fiber.get, previousAsync))
                Async.timeout(timeout)(race)
            }
            val _ = IO.Unsafe.evalOrThrow(Async.run(racedAsyncIO))
        }.toList
    end run

end KyoAppPlatformSpecific
