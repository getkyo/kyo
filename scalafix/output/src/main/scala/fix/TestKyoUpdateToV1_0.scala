package fix

import kyo.*

object TestKyoUpdateToV1_0:
    val init: Unit < Sync = Sync.defer(println("hello")).andThen(Sync.defer(println(" world")))

    val prg = direct:
        init.now
        Console.printLine("hello").now

    val pureAsync: Unit < Async = Async.defer(())

    val async = /* Consider using Fiber.init or Fiber.use to guarantee termination */ Fiber.initUnscoped(Async.defer(pureAsync))

    val forked = pureAsync.forkUnscoped

    val resource: Any < (Scope & Sync) = Scope.acquireRelease(prg)(Unit => Kyo.unit)

    val forkedResource = pureAsync.fork
end TestKyoUpdateToV1_0
