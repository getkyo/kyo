package kyo.kernel2

import kyo.Render
import kyo.kernel2.internal.CanLift
import kyo.kernel2.internal.LiftMacro
import language.implicitConversions
import scala.quoted.*

/** The implicit conversions into the effect context, inherited by the `<` companion so they sit in the implicit scope of every
  * computation type without cluttering the companion body.
  */
abstract class Implicits private[kernel2] ():

    /** Implicitly lifts a value into the effect context.
      *
      * The CanLift evidence rejects statically-pending values at compile time: accidental nesting must go through an explicit `Kyo.lift`
      * or `flatten`. The macro elides the runtime check when the type is provably not a computation.
      */
    implicit inline def lift[A: CanLift, S](v: A): A < S = ${ LiftMacro.liftMacro[A, S]('v) }

    implicit inline def liftAnyVal[A <: AnyVal, S](inline v: A): A < S = v.asInstanceOf[A < S]

    implicit inline def liftUnit[S](inline v: Unit): Unit < S = v.asInstanceOf[Unit < S]

    implicit inline def abortCastUnit[S1, S2](inline v: Unit < S1): Unit < S2 = ${ Implicits.abortCastUnitImpl[S1, S2]('v) }

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
            case _              => s"Kyo(${ra.asString(Kyo.unnest(value).asInstanceOf[A])})"
    end given

end Implicits

object Implicits:

    def abortCastUnitImpl[S1: Type, S2: Type](v: Expr[Unit < S1])(using quotes: Quotes): Expr[Unit < S2] =
        import quotes.reflect.*
        val source = TypeRepr.of[S1].show
        report.errorAndAbort(
            s"""Cannot lift `Unit < ${source}` to the expected type (`Unit < ?`).
               |This may be due to an effect type mismatch.
               |Consider removing or adjusting the type constraint on the left-hand side.
               |More info : https://github.com/getkyo/kyo/issues/903""".stripMargin
        )
    end abortCastUnitImpl

end Implicits
