package kyobench

import kyo.*
import kyo.kernel.*

/** The control. The lambda's body already has a pending type, so nothing converts at the map site: `pending` carries the lift inside its
  * own body, once, where it does not multiply per call site. The difference between this and BareValue is what the lift costs; the
  * difference between this and BareSingleton is what the lift plus the macro cost.
  *
  * An ascription (`x + 1: Int < Any`) does not work as the control: it fires the same implicit conversion the bare value does.
  */
object AlreadyPending:

    sealed trait Ask extends ArrowEffect[[X] =>> Unit, [X] =>> Int]

    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def pending(x: Int): Int < Any = x

    def one: Int < Ask = ask.map(x => pending(x + 1))

end AlreadyPending
