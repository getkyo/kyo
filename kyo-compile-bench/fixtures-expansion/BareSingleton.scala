package kyobench

import kyo.*
import kyo.kernel.*

/** The expensive shape. The lambda returns a plain module singleton, which is a Singleton but not a Product, so CanLift resolves through
  * `derivedSingleton` and expands the `CanLiftMacro.checkSingleton` splice macro. A case object would take `derivedCaseObject` and never
  * reach the macro, which is why this fixture deliberately uses a non-case object.
  */
object BareSingleton:

    object Plain

    sealed trait Ask extends ArrowEffect[[X] =>> Unit, [X] =>> Int]

    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def one: Plain.type < Ask = ask.map(_ => Plain)

end BareSingleton
