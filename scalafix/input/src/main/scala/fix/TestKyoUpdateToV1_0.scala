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

    val pureAsync: Unit < Async = Async(())

    val async = Async.run(Async.apply(pureAsync))

    val forked = pureAsync.fork

    val resource: Any < (Resource & IO) = Resource.acquireRelease(prg)(Unit => Kyo.unit)

    val forkedResource = pureAsync.forkScoped
end TestKyoUpdateToV1_0
