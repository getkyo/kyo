package kyo.proto

import scala.language.implicitConversions

private[proto] trait Implicits:

    implicit inline def lift[A, S](v: A)(using inline flat: CanLift[A]): A < S =
        inline scala.compiletime.erasedValue[A] match
            case _: (Int | Long | Float | Double | Boolean | Byte | Short | Char | Unit | String) =>
                v.asInstanceOf[A < S]
            case _ =>
                `<`.nest(v)

    implicit inline def abortCastUnit[S1, S2](inline v: Unit < S1): Unit < S2 = ${ LiftMacro.abortCastUnitMacro[S1, S2]('v) }

end Implicits
