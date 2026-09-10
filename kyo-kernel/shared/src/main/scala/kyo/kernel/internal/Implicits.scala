package kyo.kernel.internal

import kyo.<
import scala.language.implicitConversions

// The lift is a plain implicit gated by CanLift, which rejects pending values at the type level.
//
// It lives in this trait, mixed into the `<` companion, rather than in the companion itself: there the
// alias is transparent, so a lambda such as liftPureFunction1's is typed with the dealiased union as its
// result, which the inliner's opaque proxies do not map back when the conversion feeds map (PendingTest,
// "a pure function passes to map point-free" fails to compile in the companion).
trait Implicits:

    /** Implicitly converts a plain value to an effectful computation.
      *
      * This conversion is a critical part of the effect system's ergonomics. It handles two key cases:
      *
      *   1. When the input is already a Kyo effect instance, it wraps it in a Nested container to prevent unsound flattening and maintain
      *      proper effect composition.
      *   2. When the input is a regular value, it lifts it directly into the effect context through type casting.
      *
      * The CanLift constraint avoids unexpected lifting when the pending effect set of computations don't match.
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
                // Type arguments stated rather than inferred, and no second cast: `nest` already answers at
                // `A < S`. Inferred, `A` is taken from the argument, which for a stable identifier is its
                // singleton, and the cast this built named that singleton. Expanded into user code and
                // re-checked under `-Xcheck-macros`, where a nested computation shows `<` through one inline
                // proxy inside another and the compiler does not substitute across the nesting, that cast is
                // rejected as malformed.
                Nested.nest[A, S](v)

    implicit inline def abortCastUnit[S1, S2](inline v: Unit < S1): Unit < S2 = ${ CanLiftMacro.abortCastUnitImpl[S1, S2]('v) }

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

end Implicits
