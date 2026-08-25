package kyo.kernel.internal

import kyo.<
import kyo.Arrow
import kyo.kernel.*
import scala.language.implicitConversions

private[kernel] trait Implicits:

    implicit inline def lift[A, S](v: A)(using inline cl: CanLift[A]): A < S =
        inline scala.compiletime.erasedValue[A] match
            case _: (Int | Long | Float | Double | Boolean | Byte | Short | Char | Unit | String) =>
                v.asInstanceOf[A < S]
            case _ =>
                Nested.nest(v).asInstanceOf[A < S]

    implicit inline def abortCastUnit[S1, S2](inline v: Unit < S1): Unit < S2 = ${ LiftMacro.abortCastUnitMacro[S1, S2]('v) }

    // the value lift cannot reach the result position of a function type, so a function returning a
    // bare value needs its own conversion to pass where a computation-returning one is expected
    implicit inline def liftPureFunction1[A1, B](inline f: A1 => B)(
        using inline flat: CanLift[B]
    ): A1 => B < Any =
        a1 => lift(f(a1))

    implicit inline def liftPureFunction2[A1, A2, B](inline f: (A1, A2) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2) => B < Any =
        (a1, a2) => lift(f(a1, a2))

    implicit inline def liftPureFunction3[A1, A2, A3, B](inline f: (A1, A2, A3) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2, A3) => B < Any =
        (a1, a2, a3) => lift(f(a1, a2, a3))

    implicit inline def liftPureFunction4[A1, A2, A3, A4, B](inline f: (A1, A2, A3, A4) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2, A3, A4) => B < Any =
        (a1, a2, a3, a4) => lift(f(a1, a2, a3, a4))

    implicit inline def liftPureFunction5[A1, A2, A3, A4, A5, B](inline f: (A1, A2, A3, A4, A5) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2, A3, A4, A5) => B < Any =
        (a1, a2, a3, a4, a5) => lift(f(a1, a2, a3, a4, a5))

    implicit inline def liftPureFunction6[A1, A2, A3, A4, A5, A6, B](inline f: (A1, A2, A3, A4, A5, A6) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2, A3, A4, A5, A6) => B < Any =
        (a1, a2, a3, a4, a5, a6) => lift(f(a1, a2, a3, a4, a5, a6))

end Implicits
