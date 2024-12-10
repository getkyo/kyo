package kyo.kernel

import internal.*
import kyo.*
import kyo.Maybe
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.language.implicitConversions

/** Represents a computation that may perform effects before producing a value.
  *
  * The pending type (`<`) is the core abstraction in Kyo for representing effectful computations. It captures a computation that will
  * eventually produce a value of type `A` while potentially performing effects of type `S` along the way.
  *
  * For example, in `Int < (Abort[String] & Emit[Log])`:
  *   - `Int` is the value that will be produced
  *   - `Abort[String]` indicates the computation may fail with a String error
  *   - `Emit[Log]` indicates the computation may emit Log values during execution
  *   - The `&` shows that both effects may occur in this computation in any order
  *
  * This type allows Kyo to track effects at compile time and ensure they are properly handled. The effects are accumulated in the type
  * parameter `S` as an intersection type (`&`). Because intersection types are unordered, `Abort[String] & Emit[Log]` is equivalent to
  * `Emit[Log] & Abort[String]` - the order in which effects appear in the type does not determine the order in which they execute.
  *
  * The pending type has a single fundamental operation - the monadic bind, which is exposed as both `map` and `flatMap` (for
  * for-comprehension support). Plain values are automatically lifted into the effect context, which means `map` can serve as both `map` and
  * `flatMap`. This allows writing effectful code typically without having to distinguish map from flatMap or manually lifting values.
  */
opaque type <[+A, -S] = A | Kyo[A, S]

object `<`:

    extension [A, S](inline v: A < S)

        /** Maps the value produced by this computation to a new computation and flattens the result. This is the monadic bind operation for
          * the pending type.
          *
          * Note: Both `map` and `flatMap` have identical behavior in this API - they both act as the monadic bind. While `map` is the
          * recommended method to use, `flatMap` exists to support for-comprehension syntax in Scala.
          *
          * @param f
          *   The transformation function to apply to the result
          * @return
          *   A new computation producing the transformed value
          */
        inline def map[B, S2](inline f: Safepoint ?=> A => B < S2)(
            using
            inline _frame: Frame,
            inline flatA: WeakFlat[A],
            inline flatB: WeakFlat[B],
            inline safepoint: Safepoint
        ): B < (S & S2) =
            @nowarn("msg=anonymous") def mapLoop(v: A < S)(using Safepoint): B < (S & S2) =
                v match
                    case kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked =>
                        new KyoContinue[IX, OX, EX, Any, B, S & S2](kyo):
                            def frame = _frame
                            def apply(v: OX[Any], context: Context)(using Safepoint) =
                                mapLoop(kyo(v, context))
                    case v =>
                        val value = v.asInstanceOf[A]
                        Safepoint.handle(value)(
                            suspend = mapLoop(value),
                            continue = f(value)
                        )
            mapLoop(v)
        end map

        /** Maps the value produced by this computation to a new computation and flattens the result.
          *
          * This method exists to support for-comprehension syntax in Scala. It is identical to `map` and `map` should be preferred when not
          * using for-comprehensions.
          *
          * @param f
          *   The function producing the next computation
          * @return
          *   A computation producing the final result
          */
        inline def flatMap[B, S2](inline f: Safepoint ?=> A => B < S2)(
            using
            inline frame: Frame,
            inline flatA: WeakFlat[A],
            inline flatB: WeakFlat[B]
        ): B < (S & S2) =
            map(v => f(v))

        /** Executes this computation, discards its result, and then executes another computation.
          *
          * @param f
          *   The computation to execute after this one
          * @return
          *   A computation producing the second result
          */
        inline def andThen[B, S2](inline f: Safepoint ?=> B < S2)(
            using
            inline frame: Frame,
            inline flatA: WeakFlat[A],
            inline flatB: WeakFlat[B]
        ): B < (S & S2) =
            map(_ => f)

        /** Executes this computation and discards its result.
          *
          * @return
          *   A computation that produces Unit
          */
        inline def unit(
            using
            inline _frame: Frame,
            inline flat: WeakFlat[A],
            inline safepoint: Safepoint
        ): Unit < S =
            @nowarn("msg=anonymous") def unitLoop(v: A < S)(using Safepoint): Unit < S =
                v match
                    case kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked =>
                        new KyoContinue[IX, OX, EX, Any, Unit, S](kyo):
                            def frame = _frame
                            def apply(v: OX[Any], context: Context)(using Safepoint) =
                                unitLoop(kyo(v, context))
                    case v =>
                        ()
            unitLoop(v)
        end unit

        /** Applies a sequence of transformations to this computation.
          */
        inline def pipe[B](inline f: (=> A < S) => B)(
            using inline flat: WeakFlat[A]
        ): B =
            def pipe1 = v
            f(pipe1)
        end pipe

        /** Applies a sequence of transformations to this computation.
          */
        inline def pipe[B, C](
            inline f1: A < S => B,
            inline f2: (=> B) => C
        )(using inline flat: WeakFlat[A]): C =
            def pipe2 = v.pipe(f1)
            f2(pipe2)
        end pipe

        /** Applies a sequence of transformations to this computation.
          */
        inline def pipe[B, C, D](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D
        )(using inline flat: WeakFlat[A]): D =
            def pipe3 = v.pipe(f1, f2)
            f3(pipe3)
        end pipe

        /** Applies a sequence of transformations to this computation.
          */
        inline def pipe[B, C, D, E](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E
        )(using inline flat: WeakFlat[A]): E =
            def pipe4 = v.pipe(f1, f2, f3)
            f4(pipe4)
        end pipe

        /** Applies a sequence of transformations to this computation.
          */
        inline def pipe[B, C, D, E, F](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F
        )(using inline flat: WeakFlat[A]): F =
            def pipe5 = v.pipe(f1, f2, f3, f4)
            f5(pipe5)
        end pipe

        /** Applies a sequence of transformations to this computation.
          */
        inline def pipe[B, C, D, E, F, G](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G
        )(using inline flat: WeakFlat[A]): G =
            def pipe6 = v.pipe(f1, f2, f3, f4, f5)
            f6(pipe6)
        end pipe

        private[kyo] inline def evalNow(using inline flat: Flat[A]): Maybe[A] =
            v match
                case kyo: Kyo[?, ?] => Maybe.empty
                case v              => Maybe(v.asInstanceOf[A])

    end extension

    extension [A, S, S2](v: A < S < S2)
        /** Flattens a nested pending computation into a single computation.
          *
          * @return
          *   A flattened computation of type `A` with combined effects `S & S2`
          */
        def flatten(using _frame: Frame): A < (S & S2) =
            def flattenLoop(v: A < S < S2)(using Safepoint): A < (S & S2) =
                v match
                    case kyo: KyoSuspend[IX, OX, EX, Any, A < S, S2] @unchecked =>
                        new KyoContinue[IX, OX, EX, Any, A, S & S2](kyo):
                            def frame = _frame
                            def apply(v: OX[Any], context: Context)(using Safepoint) =
                                flattenLoop(kyo(v, context))
                    case v =>
                        v.asInstanceOf[A]
            flattenLoop(v)

    extension [A](inline v: A < Any)

        /** Evaluates a pending computation that has no remaining effects (effect type is `Any`).
          *
          * This method can only be called on computations where all effects have been handled, leaving only pure computation steps. It will
          * execute the computation and return the final result.
          *
          * @return
          *   The final result of type `A` after evaluating the computation
          * @throws IllegalStateException
          *   if unhandled effects remain in the computation
          */
        inline def eval(using inline frame: Frame, inline flat: Flat[A]): A =
            @tailrec def evalLoop(kyo: A < Any)(using Safepoint): A =
                kyo match
                    case kyo: KyoSuspend[Const[Unit], Const[Unit], Defer, Any, A, Any] @unchecked
                        if kyo.tag =:= Tag[Defer] =>
                        evalLoop(kyo((), Context.empty))
                    case kyo: Kyo[A, Any] @unchecked =>
                        bug.failTag(kyo, Tag[Any])
                    case v =>
                        v.asInstanceOf[A]
                end match
            end evalLoop
            Safepoint.eval(evalLoop(v))
        end eval
    end extension

    implicit private[kernel] inline def fromKyo[A, S](v: Kyo[A, S]): A < S = v

    /** Converts a plain value to an effectful computation. */
    implicit inline def lift[A, S](v: A)(using inline flat: WeakFlat[A]): A < S = v

    /** Converts a pure single-argument function to an effectful computation. */
    implicit inline def liftPureFunction1[A1, B](inline f: A1 => B)(
        using inline flat: WeakFlat[B]
    ): A1 => B < Any =
        a1 => f(a1)

    /** Converts a pure two-argument function to an effectful computation. */
    implicit inline def liftPureFunction2[A1, A2, B](inline f: (A1, A2) => B)(
        using inline flat: WeakFlat[B]
    ): (A1, A2) => B < Any =
        (a1, a2) => f(a1, a2)

    /** Converts a pure three-argument function to an effectful computation. */
    implicit inline def liftPureFunction3[A1, A2, A3, B](inline f: (A1, A2, A3) => B)(
        using inline flat: WeakFlat[B]
    ): (A1, A2, A3) => B < Any =
        (a1, a2, a3) => f(a1, a2, a3)

    /** Converts a pure four-argument function to an effectful computation. */
    implicit inline def liftPureFunction4[A1, A2, A3, A4, B](inline f: (A1, A2, A3, A4) => B)(
        using inline flat: WeakFlat[B]
    ): (A1, A2, A3, A4) => B < Any =
        (a1, a2, a3, a4) => f(a1, a2, a3, a4)

    /** Converts a pure five-argument function to an effectful computation. */
    implicit inline def liftPureFunction5[A1, A2, A3, A4, A5, B](inline f: (A1, A2, A3, A4, A5) => B)(
        using inline flat: WeakFlat[B]
    ): (A1, A2, A3, A4, A5) => B < Any =
        (a1, a2, a3, a4, a5) => f(a1, a2, a3, a4, a5)

    /** Converts a pure six-argument function to an effectful computation. */
    implicit inline def liftPureFunction6[A1, A2, A3, A4, A5, A6, B](inline f: (A1, A2, A3, A4, A5, A6) => B)(
        using inline flat: WeakFlat[B]
    ): (A1, A2, A3, A4, A5, A6) => B < Any =
        (a1, a2, a3, a4, a5, a6) => f(a1, a2, a3, a4, a5, a6)

    given [A, S, APS <: A < S](using sha: AsText[A]): AsText[APS] with
        def asText(value: APS): String = value match
            case sus: Kyo[?, ?]  => sus.toString
            case a: A @unchecked => s"Kyo(${sha.asText(a)})"
    end given

end `<`
