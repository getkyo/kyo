package fix

import kyo.*

object TestKyoUpdateToV1_0:
    val init: Unit < Sync = Sync.defer(println("hello")).andThen(Sync.defer(println(" world")))

    val prg = direct:
        init.now
        Console.printLine("hello").now

    val async: Fiber[Nothing, Unit] < Async = Fiber.initUnscoped(Async.defer(Async.defer(Kyo.unit)))

    val resource: Any < (Scope & Sync) = Scope.acquireRelease(prg)(Unit => Kyo.unit)
end TestKyoUpdateToV1_0
