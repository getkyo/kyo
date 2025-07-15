/*
rule = KyoUpdateToV1_0
 */
package fix

import kyo.*

object TestKyoUpdateToV1_0:
    val init: Unit < IO = IO(println("hello")).andThen(IO.apply(println(" world")))

    val prg = defer:
        init.now
        Console.printLine("hello").now

    val async: Fiber[Nothing, Unit] < Async = Async.run(Async.apply(Async(Kyo.unit)))

    val forked: Fiber[Nothing, Unit] < Async = Kyo.unit.fork

    val resource: Any < (Resource & IO) = Resource.acquireRelease(prg)(Unit => Kyo.unit)

    val forkedResource: Fiber[Nothing, Unit] < (Async & Resource) = Kyo.unit.forkScoped
end TestKyoUpdateToV1_0
