package kyo.kernel

import kyo.Render
import kyo.kernel.internal.*
import scala.language.implicitConversions

private[kernel] trait Implicits:

    implicit inline def lift[A, S](v: A)(using inline flat: CanLift[A]): A < S =
        ${ LiftMacro.liftMacro[A, S]('v) }

    implicit inline def abortCastUnit[S1, S2](inline v: Unit < S1): Unit < S2 = ${ LiftMacro.abortCastUnitMacro[S1, S2]('v) }

    /** Converts a pure single-argument function to an effectful computation. */
    implicit inline def liftPureFunction1[A1, B](inline f: A1 => B)(
        using inline flat: CanLift[B]
    ): A1 => B < Any =
        a1 => Implicits.liftInternal(f(a1))

    /** Converts a pure two-argument function to an effectful computation. */
    implicit inline def liftPureFunction2[A1, A2, B](inline f: (A1, A2) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2) => B < Any =
        (a1, a2) => Implicits.liftInternal(f(a1, a2))

    /** Converts a pure three-argument function to an effectful computation. */
    implicit inline def liftPureFunction3[A1, A2, A3, B](inline f: (A1, A2, A3) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2, A3) => B < Any =
        (a1, a2, a3) => Implicits.liftInternal(f(a1, a2, a3))

    /** Converts a pure four-argument function to an effectful computation. */
    implicit inline def liftPureFunction4[A1, A2, A3, A4, B](inline f: (A1, A2, A3, A4) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2, A3, A4) => B < Any =
        (a1, a2, a3, a4) => Implicits.liftInternal(f(a1, a2, a3, a4))

    /** Converts a pure five-argument function to an effectful computation. */
    implicit inline def liftPureFunction5[A1, A2, A3, A4, A5, B](inline f: (A1, A2, A3, A4, A5) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2, A3, A4, A5) => B < Any =
        (a1, a2, a3, a4, a5) => Implicits.liftInternal(f(a1, a2, a3, a4, a5))

    /** Converts a pure six-argument function to an effectful computation. */
    implicit inline def liftPureFunction6[A1, A2, A3, A4, A5, A6, B](inline f: (A1, A2, A3, A4, A5, A6) => B)(
        using inline flat: CanLift[B]
    ): (A1, A2, A3, A4, A5, A6) => B < Any =
        (a1, a2, a3, a4, a5, a6) => Implicits.liftInternal(f(a1, a2, a3, a4, a5, a6))

    given [A, S, APendingS <: A < S](using ra: Render[A]): Render[APendingS] with
        def asString(value: APendingS): String = value match
            case sus: Kyo[?, ?] => sus.toString
            case a              => s"Kyo(${ra.asString(Nested.unnest[A](a))})"
    end given

end Implicits

object Implicits:

    /** The macro-free lift for this module's own sources. The public lift expands
      * LiftMacro, and a unit whose compilation suspends on a same-module macro crashes the
      * inliner (StaleSymbolException, diagnosed with -Xprint-suspension), so files inside
      * kyo-kernel2 import this conversion instead: an import has lexical-scope priority over
      * the companion's implicit scope, so the macro never expands in the module that defines
      * it, while every other module and this module's own tests take the macro path.
      */
    implicit private[kyo] inline def liftInternal[A, S](v: A)(using inline flat: CanLift[A]): A < S =
        inline scala.compiletime.erasedValue[A] match
            case _: (Int | Long | Float | Double | Boolean | Byte | Short | Char | Unit | String) =>
                v.asInstanceOf[A < S]
            case _ =>
                Nested.lift(v)

end Implicits
