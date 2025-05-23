package kyo.kernel

import kyo.*
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.language.implicitConversions
import scala.quoted.Expr
import scala.quoted.Quotes
import scala.quoted.Type

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
  *
  * Effects are typically handled using their specific `run` methods, such as `Abort.run(computation)` or `Env.run(config)(computation)`.
  * Each effect provides its own handling mechanism that processes the computation and removes that effect from the type signature.
  *
  * The `handle` method offers an alternative approach that enables passing a computation to one or more transformation functions. For
  * example, instead of `Abort.run(computation)`, one can write `computation.handle(Abort.run)`. The multi-parameter version enables
  * chaining transformations: `computation.handle(Abort.run, Env.run(config))`. This can be useful when composing multiple effect handlers.
  *
  * Beyond effect handlers, `handle` can be used with any function that takes a computation as input. For example,
  * `computation.handle(Abort.run, _.map(_ + 1))` handles `Abort` and then applies a transformation. While `handle` supports arbitrary
  * functions, it is primarily designed for effect handling .
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
                        val value = v.unsafeGet
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
            using inline _frame: Frame
        ): B < (S & S2) =
            @nowarn("msg=anonymous") def flatMapLoop(v: A < S)(using Safepoint): B < (S & S2) =
                v match
                    case kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked =>
                        new KyoContinue[IX, OX, EX, Any, B, S & S2](kyo):
                            def frame = _frame
                            def apply(v: OX[Any], context: Context)(using Safepoint) =
                                flatMapLoop(kyo(v, context))
                    case v =>
                        val value = v.unsafeGet
                        Safepoint.handle(value)(
                            suspend = flatMapLoop(value),
                            continue = f(value)
                        )
            flatMapLoop(v)
        end flatMap

        /** Executes this computation, discards its result, and then executes another computation.
          *
          * @param f
          *   The computation to execute after this one
          * @return
          *   A computation producing the second result
          */
        inline def andThen[B, S2](inline f: Safepoint ?=> B < S2)(
            using inline _frame: Frame
        ): B < (S & S2) =
            @nowarn("msg=anonymous") def andThenLoop(v: A < S)(using Safepoint): B < (S & S2) =
                v match
                    case kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked =>
                        new KyoContinue[IX, OX, EX, Any, B, S & S2](kyo):
                            def frame = _frame
                            def apply(v: OX[Any], context: Context)(using Safepoint) =
                                andThenLoop(kyo(v, context))
                    case v =>
                        val value = v.unsafeGet
                        Safepoint.handle(value)(
                            suspend = andThenLoop(value),
                            continue = f
                        )
            andThenLoop(v)
        end andThen

        /** Executes this computation and discards its result.
          *
          * @return
          *   A computation that produces Unit
          */
        inline def unit(
            using
            inline _frame: Frame,
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

        /** Applies a transformation to this computation.
          *
          * The `handle` method provides a convenient way to pass a computation to a transformation function. It's primarily designed for
          * effect handling, allowing a more fluent API style compared to the traditional approach of passing the computation to a handler
          * function.
          *
          * For example, instead of:
          * ```
          * Env.run(1)(Abort.run(computation))
          * ```
          * You can write:
          * ```
          * computation.handle(Abort.run, Env.run(1))
          * ```
          *
          * While `handle` can be used with any function that processes a computation, its main purpose is to facilitate effect handling and
          * composition of multiple handlers. The multi-parameter versions of `handle` enable chaining transformations in a readable
          * sequential style.
          *
          * @param f
          *   The transformation function to apply
          * @return
          *   The result of applying the transformation
          */
        inline def handle[B](inline f: (=> A < S) => B): B =
            def handle1 = v
            f(handle1)
        end handle

        /** Applies two transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying both transformations
          */
        inline def handle[B, C](
            inline f1: A < S => B,
            inline f2: (=> B) => C
        ): C =
            def handle2 = v.handle(f1)
            f2(handle2)
        end handle

        /** Applies three transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying all transformations in sequence
          */
        inline def handle[B, C, D](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D
        ): D =
            def handle3 = v.handle(f1, f2)
            f3(handle3)
        end handle

        /** Applies four transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying all transformations in sequence
          */
        inline def handle[B, C, D, E](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E
        ): E =
            def handle4 = v.handle(f1, f2, f3)
            f4(handle4)
        end handle

        /** Applies five transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying all transformations in sequence
          */
        inline def handle[B, C, D, E, F](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F
        ): F =
            def handle5 = v.handle(f1, f2, f3, f4)
            f5(handle5)
        end handle

        /** Applies six transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying all transformations in sequence
          */
        inline def handle[B, C, D, E, F, G](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G
        ): G =
            def handle6 = v.handle(f1, f2, f3, f4, f5)
            f6(handle6)
        end handle

        /** Applies a sequence of transformations to this computation.
          */
        inline def handle[B, C, D, E, F, G, H](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H
        ): H =
            def handle7 = v.handle(f1, f2, f3, f4, f5, f6)
            f7(handle7)
        end handle

        /** Applies a sequence of transformations to this computation.
          */
        inline def handle[B, C, D, E, F, G, H, I](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H,
            inline f8: (=> H) => I
        ): I =
            def handle8 = v.handle(f1, f2, f3, f4, f5, f6, f7)
            f8(handle8)
        end handle

        /** Applies a sequence of transformations to this computation.
          */
        inline def handle[B, C, D, E, F, G, H, I, J](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H,
            inline f8: (=> H) => I,
            inline f9: (=> I) => J
        ): J =
            def handle9 = v.handle(f1, f2, f3, f4, f5, f6, f7, f8)
            f9(handle9)
        end handle

        /** Applies a sequence of transformations to this computation.
          */
        inline def handle[B, C, D, E, F, G, H, I, J, K](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H,
            inline f8: (=> H) => I,
            inline f9: (=> I) => J,
            inline f10: (=> J) => K
        ): K =
            def handle10 = v.handle(f1, f2, f3, f4, f5, f6, f7, f8, f9)
            f10(handle10)
        end handle

        private[kyo] inline def evalNow: Maybe[A] =
            v match
                case kyo: KyoSuspend[?, ?, ?, ?, ?, ?] => Maybe.empty
                case v                                 => Maybe(v.unsafeGet)

    end extension

    extension [A, S](v: A < S)
        private[kyo] def unsafeGet: A =
            v match
                case Nested(v) => v.asInstanceOf[A]
                case _         => v.asInstanceOf[A]
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
                        v.unsafeGet
            flattenLoop(v)
    end extension

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
        inline def eval(using inline frame: Frame): A =
            @tailrec def evalLoop(kyo: A < Any)(using Safepoint): A =
                kyo match
                    case kyo: KyoSuspend[Const[Unit], Const[Unit], Defer, Any, A, Any] @unchecked
                        if kyo.tag =:= Tag[Defer] =>
                        evalLoop(kyo((), Context.empty))
                    case kyo: KyoSuspend[?, ?, ?, ?, A, Any] @unchecked =>
                        bug.failTag(kyo, Tag[Any])
                    case v =>
                        v.unsafeGet
                end match
            end evalLoop
            Safepoint.eval(evalLoop(v))
        end eval
    end extension

    implicit private[kernel] inline def fromKyo[A, S](v: Kyo[A, S]): A < S = v

    /** Implicitly converts a plain value to an effectful computation.
      *
      * This conversion is a critical part of the effect system's ergonomics. It handles two key cases:
      *
      *   1. When the input is already a Kyo effect instance, it wraps it in a Nested container to prevent unsound flattening and maintain
      *      proper effect composition.
      *   2. When the input is a regular value, it lifts it directly into the effect context through type casting.
      *
      * The WeakFlat constraint avoids unexpected lifting when the pending effect set of computations don't match.
      *
      * @param v
      *   The value to lift into the effect context
      * @return
      *   A computation in the effect context
      */
    implicit def lift[A: WeakFlat, S](v: A): A < S =
        v match
            case kyo: Kyo[?, ?] => Nested(kyo)
            case _              => v.asInstanceOf[A < S]

    implicit inline def liftUnit[S1, S2](inline v: Unit < S1): Unit < S2 = ${ liftUnitImpl[S1, S2]('v) }

    private def liftUnitImpl[S1: Type, S2: Type](v: Expr[Unit < S1])(using quotes: Quotes): Expr[Unit < S2] =
        import quotes.reflect.*
        val source = TypeRepr.of[S1].show
        report.errorAndAbort(
            s"""Cannot lift `Unit < $source` to the required type (`Unit < ?`).
                |Please remove the type constraint on Left Hand Side.
                |More info : https://github.com/getkyo/kyo/issues/903""".stripMargin
        )
    end liftUnitImpl

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

    given [A, S, APendingS <: A < S](using ra: Render[A]): Render[APendingS] with
        def asText(value: APendingS): Text = value match
            case sus: Kyo[?, ?]  => sus.toString
            case a: A @unchecked => s"Kyo(${ra.asText(a)})"
    end given

end `<`
