package fix

import kyo.*

object KyoUpdateToV1_0:
    val init: Unit < Sync = Sync.defer(println("hello")).andThen(Sync.defer(println(" world")))

    val prg = direct:
        init.now
        Console.printLine("hello").now

end KyoUpdateToV1_0
