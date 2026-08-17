package kyo.kernel

import kyo.Render
import kyo.kernel.internal.CanLift
import scala.language.implicitConversions

trait Implicits:

    // the evidence is the lint alone, resolved where the conversion is written
    // and baked, so a generic context is waived and stays sound through the
    // emission's box; CanLift.lift is the emission, re-expanded per site
    implicit inline def lift[A: CanLift, S](v: A): A < S = CanLift.lift[A, S](v)

    // a matching conversion that aborts after selection: a failed nested
    // evidence inside a conversion candidate surfaces as a plain mismatch, so
    // the Unit row trap (issue 903) must win the search first to speak
    implicit inline def abortCastUnit[S1, S2](inline v: Unit < S1): Unit < S2 =
        scala.compiletime.error(
            "Cannot lift `Unit < S1` to the expected type (`Unit < S2`).\n" +
                "This may be due to an effect type mismatch.\n" +
                "Consider removing or adjusting the type constraint on the left-hand side.\n" +
                "More info : https://github.com/getkyo/kyo/issues/903"
        )

    // the function lifts route their results through the canonical lift, so a
    // nested result type (A1 => B < S < S2) is rejected like any other lift

    /** Converts a pure single-argument function to an effectful computation. */
    implicit inline def liftPureFunction1[A1, B: CanLift](inline f: A1 => B): A1 => B < Any =
        a1 => lift(f(a1))

    /** Converts a pure two-argument function to an effectful computation. */
    implicit inline def liftPureFunction2[A1, A2, B: CanLift](inline f: (A1, A2) => B): (A1, A2) => B < Any =
        (a1, a2) => lift(f(a1, a2))

    /** Converts a pure three-argument function to an effectful computation. */
    implicit inline def liftPureFunction3[A1, A2, A3, B: CanLift](inline f: (A1, A2, A3) => B): (A1, A2, A3) => B < Any =
        (a1, a2, a3) => lift(f(a1, a2, a3))

    /** Converts a pure four-argument function to an effectful computation. */
    implicit inline def liftPureFunction4[A1, A2, A3, A4, B: CanLift](inline f: (A1, A2, A3, A4) => B): (A1, A2, A3, A4) => B < Any =
        (a1, a2, a3, a4) => lift(f(a1, a2, a3, a4))

    /** Converts a pure five-argument function to an effectful computation. */
    implicit inline def liftPureFunction5[A1, A2, A3, A4, A5, B: CanLift](
        inline f: (A1, A2, A3, A4, A5) => B
    ): (A1, A2, A3, A4, A5) => B < Any =
        (a1, a2, a3, a4, a5) => lift(f(a1, a2, a3, a4, a5))

    /** Converts a pure six-argument function to an effectful computation. */
    implicit inline def liftPureFunction6[A1, A2, A3, A4, A5, A6, B: CanLift](
        inline f: (A1, A2, A3, A4, A5, A6) => B
    ): (A1, A2, A3, A4, A5, A6) => B < Any =
        (a1, a2, a3, a4, a5, a6) => lift(f(a1, a2, a3, a4, a5, a6))

    given [A, S, APendingS <: A < S](using ra: Render[A]): Render[APendingS] with
        def asString(value: APendingS): String = value match
            case sus: Arrow[?, ?, ?] => sus.toString
            case a                   => s"Kyo(${ra.asString(Nested.unnest[A](a))})"
    end given

end Implicits
