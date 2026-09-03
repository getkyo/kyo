package kyo.kernel

import kyo.Frame
import kyo.Maybe
import kyo.Render
import kyo.kernel.Arrow
import kyo.kernel.Arrow.Step
import kyo.kernel.Arrow.Transform
import kyo.kernel.internal.*
import language.implicitConversions
import scala.annotation.nowarn

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
// Diverges from main: the union's second arm is Pending (the node family in PendingInternal)
// where main has Kyo, its suspension ADT. The combinators below build Arrow and Defer nodes and
// leave the stack-depth budget to the evaluator, so their function parameters take no
// `Safepoint ?=>` context and no Safepoint evidence; `eval` and `flatten` go through Eval.
opaque type <[+A, -S] = A | Pending[A, S]

object `<` extends Implicits:
    implicit def fromKyo[A, S](kyo: Pending[A, S]): A < S = kyo

    extension [A, S](inline self: A < S)

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
        @nowarn("msg=anonymous")
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                var slot: Safepoint.Slot = -1
                val shouldDefer          = v.isInstanceOf[Pending[?, ?]] || { slot = Safepoint.get(); !Safepoint.enter(slot) }
                if shouldDefer then
                    new Pending.DeferWith[A, C, S2 & S3]:
                        override def frame = _frame
                        def value          = v
                        override def apply[C2, S4](v2: A < S4, cont2: Arrow[C, C2, S4]) =
                            run(v2, cont.chain(cont2))
                else
                    val out = cont.head(f(Nested.unnest(v)), cont.tail)
                    Safepoint.exit(slot)
                    out
                end if
            end run
            run(self, Arrow.id)
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
        @nowarn("msg=anonymous")
        inline def flatMap[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                var slot: Safepoint.Slot = -1
                val shouldDefer          = v.isInstanceOf[Pending[?, ?]] || { slot = Safepoint.get(); !Safepoint.enter(slot) }
                if shouldDefer then
                    new Pending.DeferWith[A, C, S2 & S3]:
                        override def frame = _frame
                        def value          = v
                        override def apply[C2, S4](v2: A < S4, cont2: Arrow[C, C2, S4]) =
                            run(v2, cont.chain(cont2))
                else
                    val out = cont.head(f(Nested.unnest(v)), cont.tail)
                    Safepoint.exit(slot)
                    out
                end if
            end run
            run(self, Arrow.id)
        end flatMap

        /** Executes this computation, discards its result, and then executes another computation.
          *
          * @param f
          *   The computation to execute after this one
          * @return
          *   A computation producing the second result
          */
        @nowarn("msg=anonymous")
        inline def andThen[B, S2](inline f: => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                var slot: Safepoint.Slot = -1
                val shouldDefer          = v.isInstanceOf[Pending[?, ?]] || { slot = Safepoint.get(); !Safepoint.enter(slot) }
                if shouldDefer then
                    new Pending.DeferWith[A, C, S2 & S3]:
                        override def frame = _frame
                        def value          = v
                        override def apply[C2, S4](v2: A < S4, cont2: Arrow[C, C2, S4]) =
                            run(v2, cont.chain(cont2))
                else
                    val out = cont.head(f, cont.tail)
                    Safepoint.exit(slot)
                    out
                end if
            end run
            run(self, Arrow.id)
        end andThen

        /** Executes this computation and discards its result.
          *
          * @return
          *   A computation that produces Unit
          */
        @nowarn("msg=anonymous")
        inline def unit(using inline _frame: Frame): Unit < S =
            def run[C, S3](v: A < S3, cont: Arrow[Unit, C, S3]): C < S3 =
                var slot: Safepoint.Slot = -1
                val shouldDefer          = v.isInstanceOf[Pending[?, ?]] || { slot = Safepoint.get(); !Safepoint.enter(slot) }
                if shouldDefer then
                    new Pending.DeferWith[A, C, S3]:
                        override def frame = _frame
                        def value          = v
                        override def apply[C2, S4](v2: A < S4, cont2: Arrow[C, C2, S4]) =
                            run(v2, cont.chain(cont2))
                else
                    val out = cont.head((), cont.tail)
                    Safepoint.exit(slot)
                    out
                end if
            end run
            run(self, Arrow.id)
        end unit

        /** Applies a transformation to this computation.
          *
          * The `handle` method provides a convenient way to pass a computation to a transformation function. It's primarily designed for
          * effect handling, allowing a more fluent API style compared to the traditional approach of passing the computation to a handler
          * function.
          *
          * For example, instead of:
          *
          * ```scala
          * Env.run(1)(Abort.run(computation))
          * ```
          *
          * You can write:
          *
          * ```scala
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
            def h1 = self
            f(h1)
        end handle

        /** Applies two transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying both transformations
          */
        inline def handle[B, C](inline f1: (=> A < S) => B, inline f2: (=> B) => C): C =
            def h1 = self
            def h2 = f1(h1)
            f2(h2)
        end handle

        /** Applies three transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying all transformations in sequence
          */
        inline def handle[B, C, D](inline f1: (=> A < S) => B, inline f2: (=> B) => C, inline f3: (=> C) => D): D =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            f3(h3)
        end handle

        /** Applies four transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying all transformations in sequence
          */
        inline def handle[B, C, D, E](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E
        ): E =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            def h4 = f3(h3)
            f4(h4)
        end handle

        /** Applies five transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying all transformations in sequence
          */
        inline def handle[B, C, D, E, F](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F
        ): F =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            def h4 = f3(h3)
            def h5 = f4(h4)
            f5(h5)
        end handle

        /** Applies six transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying all transformations in sequence
          */
        inline def handle[B, C, D, E, F, G](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G
        ): G =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            def h4 = f3(h3)
            def h5 = f4(h4)
            def h6 = f5(h5)
            f6(h6)
        end handle

        /** Applies a sequence of transformations to this computation.
          */
        inline def handle[B, C, D, E, F, G, H](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H
        ): H =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            def h4 = f3(h3)
            def h5 = f4(h4)
            def h6 = f5(h5)
            def h7 = f6(h6)
            f7(h7)
        end handle

        /** Applies a sequence of transformations to this computation.
          */
        inline def handle[B, C, D, E, F, G, H, I](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H,
            inline f8: (=> H) => I
        ): I =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            def h4 = f3(h3)
            def h5 = f4(h4)
            def h6 = f5(h5)
            def h7 = f6(h6)
            def h8 = f7(h7)
            f8(h8)
        end handle

        /** Applies a sequence of transformations to this computation.
          */
        inline def handle[B, C, D, E, F, G, H, I, J](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H,
            inline f8: (=> H) => I,
            inline f9: (=> I) => J
        ): J =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            def h4 = f3(h3)
            def h5 = f4(h4)
            def h6 = f5(h5)
            def h7 = f6(h6)
            def h8 = f7(h7)
            def h9 = f8(h8)
            f9(h9)
        end handle

        /** Applies a sequence of transformations to this computation.
          */
        inline def handle[B, C, D, E, F, G, H, I, J, K](
            inline f1: (=> A < S) => B,
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
            def h1  = self
            def h2  = f1(h1)
            def h3  = f2(h2)
            def h4  = f3(h3)
            def h5  = f4(h4)
            def h6  = f5(h5)
            def h7  = f6(h6)
            def h8  = f7(h7)
            def h9  = f8(h8)
            def h10 = f9(h9)
            f10(h10)
        end handle

        private[kyo] inline def evalNow: Maybe[A] =
            val v = self
            v match
                case _: Pending[?, ?] => Maybe.empty
                case _                => Maybe(Nested.unnest(v))
        end evalNow
    end extension

    extension [A, S](self: A < S)

        def chain[B, S2](cont: Arrow[A, B, S2]): B < (S & S2) =
            cont(self, Arrow.id)
    end extension

    extension [A, S, S2](self: A < S < S2)

        /** Flattens a nested pending computation into a single computation.
          *
          * @return
          *   A flattened computation of type `A` with combined effects `S & S2`
          */
        @nowarn("msg=anonymous")
        def flatten(using _frame: Frame): A < (S & S2) =
            def arrow: Arrow[A < S, A, S] =
                new Step[A < S, A, S]:
                    def frame                                                = _frame
                    def apply[C, S3](v: (A < S) < S3, cont: Arrow[A, C, S3]) = run(v, cont)
            def run[C, S3](v: (A < S) < S3, cont: Arrow[A, C, S3]): C < (S & S3) =
                v match
                    case kyo: Pending[A < S, S3] @unchecked =>
                        Effect.defer(kyo, arrow, cont)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, arrow, cont)
                        else
                            val out = cont.head(Nested.unnest[A < S](v), cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if
            run(self, Arrow.id)
        end flatten
    end extension

    extension [A](inline v: A < Any)

        /** Evaluates a pending computation that has no remaining effects (effect type is `Any`).
          *
          * This method can only be called on computations where all effects have been handled, leaving only pure computation steps. It will
          * execute the computation and return the final result.
          *
          * @return
          *   The final result of type `A` after evaluating the computation
          * @throws java.lang.IllegalStateException
          *   if unhandled effects remain in the computation
          */
        inline def eval(using inline frame: Frame): A =

            val v0 = v
            v0 match
                case _: Pending[?, ?] => Nested.unnest[A](Eval(v0.asInstanceOf[A < Any]))
                case _                => Nested.unnest(v0)
        end eval
    end extension

    given [A, S, APendingS <: A < S](using ra: Render[A]): Render[APendingS] with
        def asString(value: APendingS): String =
            value match
                case kyo: Pending[?, ?] => kyo.toString
                case nested: Nested[?]  => s"Kyo(${nested.value})"
                case a: A @unchecked    => s"Kyo(${ra.asString(a)})"
    end given
end `<`
