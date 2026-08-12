package kyobench

import kyo.*
import kyo.kernel.*

object UnitRowMismatch:
    sealed trait E1 extends ArrowEffect[[X] =>> Int, [X] =>> Int]
    sealed trait E2 extends ArrowEffect[[X] =>> Int, [X] =>> Int]

    def v: Unit < E1 = ???

    val bad: Unit < E2 = v
