package kyobench

import kyo.*
import kyo.kernel.*

object TagDerivation:

    sealed trait Eff0 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    def op0: Int < Eff0 = ArrowEffect.suspend[Any](Tag[Eff0], ())

    sealed trait Eff1 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    def op1: Int < Eff1 = ArrowEffect.suspend[Any](Tag[Eff1], ())

    sealed trait Eff2 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    def op2: Int < Eff2 = ArrowEffect.suspend[Any](Tag[Eff2], ())

    sealed trait Eff3 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    def op3: Int < Eff3 = ArrowEffect.suspend[Any](Tag[Eff3], ())

    sealed trait Eff4 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    def op4: Int < Eff4 = ArrowEffect.suspend[Any](Tag[Eff4], ())

    sealed trait Eff5 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    def op5: Int < Eff5 = ArrowEffect.suspend[Any](Tag[Eff5], ())

    sealed trait Eff6 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    def op6: Int < Eff6 = ArrowEffect.suspend[Any](Tag[Eff6], ())

    sealed trait Eff7 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    def op7: Int < Eff7 = ArrowEffect.suspend[Any](Tag[Eff7], ())

    sealed trait Eff8 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    def op8: Int < Eff8 = ArrowEffect.suspend[Any](Tag[Eff8], ())

    sealed trait Eff9 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    def op9: Int < Eff9 = ArrowEffect.suspend[Any](Tag[Eff9], ())

    sealed trait Eff10 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    def op10: Int < Eff10 = ArrowEffect.suspend[Any](Tag[Eff10], ())

    sealed trait Eff11 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    def op11: Int < Eff11 = ArrowEffect.suspend[Any](Tag[Eff11], ())

    sealed trait State[V] extends ArrowEffect[[X] =>> Unit, [X] =>> V]

    def st0: Int < State[Int] = ArrowEffect.suspend[Any](Tag[State[Int]], ())

    def st1: String < State[String] = ArrowEffect.suspend[Any](Tag[State[String]], ())

    def st2: Boolean < State[Boolean] = ArrowEffect.suspend[Any](Tag[State[Boolean]], ())

    def st3: Long < State[Long] = ArrowEffect.suspend[Any](Tag[State[Long]], ())

    def st4: Double < State[Double] = ArrowEffect.suspend[Any](Tag[State[Double]], ())

    def st5: List[Int] < State[List[Int]] = ArrowEffect.suspend[Any](Tag[State[List[Int]]], ())

    def st6: Option[String] < State[Option[String]] = ArrowEffect.suspend[Any](Tag[State[Option[String]]], ())

    def st7: Map[String, Int] < State[Map[String, Int]] = ArrowEffect.suspend[Any](Tag[State[Map[String, Int]]], ())

end TagDerivation
