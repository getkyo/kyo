/*
rule = KyoUpdateToV1_0
 */
package fix

import kyo.*

object KyoUpdateToV1_0:
    val init: Unit < IO = IO(println("hello"))

    val prg = defer:
        init.now
        Console.printLine("hello").now

end KyoUpdateToV1_0
