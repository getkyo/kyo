package kyo.kernel

import kyo.Render
import kyo.kernel.internal.*
import scala.language.implicitConversions

private[kernel] trait Implicits:

    implicit inline def lift[A, S](v: A)(using inline flat: CanLift[A]): A < S =
        inline scala.compiletime.erasedValue[A] match
            case _: (Int | Long | Float | Double | Boolean | Byte | Short | Char | Unit | String) =>
                v.asInstanceOf[A < S]
            case _ =>
                Nested.lift(v)

    implicit inline def liftAnyVal[A <: AnyVal, S](inline v: A): A < S = v.asInstanceOf[A < S]

    implicit inline def liftUnit[S](inline v: Unit): Unit < S = v.asInstanceOf[Unit < S]

    implicit inline def abortCastUnit[S1, S2](inline v: Unit < S1): Unit < S2 = ${ LiftMacro.abortCastUnitMacro[S1, S2]('v) }

    /** Converts a pure single-argument function to an effectful computation. */
    implicit inline def liftPureFunction1[A1, B](inline f: A1 => B)(
        using inline flat: CanLift[B]
    ): A1 => B < Any =
        a1 => lift(f(a1))

    /** Converts a pure two-argument function to an effectful computation. */
    implicit inline def liftPureFunction2[A1, A2, B](inline f: (A1, A2) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2) => B < Any =
        (a1, a2) => lift(f(a1, a2))

    /** Converts a pure three-argument function to an effectful computation. */
    implicit inline def liftPureFunction3[A1, A2, A3, B](inline f: (A1, A2, A3) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2, A3) => B < Any =
        (a1, a2, a3) => lift(f(a1, a2, a3))

    /** Converts a pure four-argument function to an effectful computation. */
    implicit inline def liftPureFunction4[A1, A2, A3, A4, B](inline f: (A1, A2, A3, A4) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2, A3, A4) => B < Any =
        (a1, a2, a3, a4) => lift(f(a1, a2, a3, a4))

    /** Converts a pure five-argument function to an effectful computation. */
    implicit inline def liftPureFunction5[A1, A2, A3, A4, A5, B](inline f: (A1, A2, A3, A4, A5) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2, A3, A4, A5) => B < Any =
        (a1, a2, a3, a4, a5) => lift(f(a1, a2, a3, a4, a5))

    /** Converts a pure six-argument function to an effectful computation. */
    implicit inline def liftPureFunction6[A1, A2, A3, A4, A5, A6, B](inline f: (A1, A2, A3, A4, A5, A6) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2, A3, A4, A5, A6) => B < Any =
        (a1, a2, a3, a4, a5, a6) => lift(f(a1, a2, a3, a4, a5, a6))

    given [A, S, APendingS <: A < S](using ra: Render[A]): Render[APendingS] with
        def asString(value: APendingS): String = value match
            case sus: Kyo[?, ?] => sus.toString
            case a              => s"Kyo(${ra.asString(Nested.unnest[A](a))})"
    end given

end Implicits
