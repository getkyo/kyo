package kyo.kernel.internal

import kyo.<
import scala.language.implicitConversions

// The lift is a plain implicit gated by CanLift, which rejects pending values at the type level. It must live in
// this trait rather than in the `<` companion it is mixed into: in the companion the alias is transparent, so a
// lambda such as liftPureFunction1's is typed with the dealiased union as its result, which the inliner's opaque
// proxies do not map back when the conversion feeds map (PendingTest, "a pure function passes to map point-free").
trait Implicits:

    /** Implicitly converts a plain value to an effectful computation.
      *
      * A computation used where a value is expected is wrapped in `Nested` to prevent unsound flattening; a plain value is lifted directly.
      * The `CanLift` constraint refuses an argument that is already a computation, and a kyo module object; a `Unit`
      * computation widened to another row is the separate case `abortCastUnit` answers with a guided error.
      *
      * @param v
      *   The value to lift into the effect context
      * @return
      *   A computation in the effect context
      */
    implicit inline def lift[A, S](v: A)(using inline cl: CanLift[A]): A < S =
        inline scala.compiletime.erasedValue[A] match
            case _: (Int | Long | Float | Double | Boolean | Byte | Short | Char | Unit | String) =>
                v.asInstanceOf[A < S]
            case _ =>
                // Type arguments are stated rather than inferred, and there is no second cast: `nest` already
                // answers at `A < S`. Inferred, `A` is the argument's singleton type for a stable identifier, and
                // the cast built from it is rejected as malformed under `-Xcheck-macros` once it expands inside a
                // nested computation.
                Nested.nest[A, S](v)

    implicit inline def abortCastUnit[S1, S2](inline v: Unit < S1): Unit < S2 = ${ CanLiftMacro.abortCastUnitImpl[S1, S2]('v) }

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
