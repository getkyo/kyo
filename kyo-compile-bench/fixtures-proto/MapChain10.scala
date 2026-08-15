package kyobench

import kyo.kernel.proto.*

object MapChain10:

    def run(v: Int < Any): Int < Any =
        v.map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1)

end MapChain10
