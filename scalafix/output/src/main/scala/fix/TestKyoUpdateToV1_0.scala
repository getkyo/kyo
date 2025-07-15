package fix

import kyo.*

object TestKyoUpdateToV1_0:
    val init: Unit < Sync = Sync.defer(println("hello")).andThen(Sync.defer(println(" world")))

    val prg = direct:
        init.now
        Console.printLine("hello").now

    val async: Fiber[Nothing, Unit] < Async = /* Consider using Fiber.init or Fiber.use to guarantee termination */ Fiber.initUnscoped(Async.defer(Async.defer(Kyo.unit)))

    val forked: Fiber[Nothing, Unit] < Async = Kyo.unit.forkUnscoped

    val resource: Any < (Scope & Sync) = Scope.acquireRelease(prg)(Unit => Kyo.unit)

    val forkedResource: Fiber[Nothing, Unit] < (Async & Scope) = Kyo.unit.fork
end TestKyoUpdateToV1_0
