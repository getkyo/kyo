package kyobench

import kyo.*
import kyo.kernel.*

/** The common shape: the lambda returns a bare value, so the implicit lift fires inside map's expansion and brings a CanLift summon with
  * it. `Int` takes the primitive arm of lift's inline match.
  */
object BareValue:

    sealed trait Ask extends ArrowEffect[[X] =>> Unit, [X] =>> Int]

    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def one: Int < Ask = ask.map(_ + 1)

end BareValue
