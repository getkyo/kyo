package kyobench

import kyo.*
import kyo.kernel.*

/** The control. The lambda already returns a computation, so no lift fires and no CanLift is summoned. The difference between this and
  * BareValue is what the lift costs; the difference between this and BareSingleton is what the lift plus the macro cost.
  */
object AlreadyPending:

    sealed trait Ask extends ArrowEffect[[X] =>> Unit, [X] =>> Int]

    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def one: Int < Ask = ask.map(x => (x + 1: Int < Any))

end AlreadyPending
