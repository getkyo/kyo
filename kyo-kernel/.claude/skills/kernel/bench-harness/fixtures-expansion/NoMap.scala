package kyobench

import kyo.*
import kyo.kernel.*

/** The zero point. Same declarations as the other three fixtures with the map removed, so subtracting it from any of them leaves the cost
  * of exactly one map call site.
  */
object NoMap:

    sealed trait Ask extends ArrowEffect[[X] =>> Unit, [X] =>> Int]

    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def pending(x: Int): Int < Any = x

    def one: Int < Ask = ask

end NoMap
