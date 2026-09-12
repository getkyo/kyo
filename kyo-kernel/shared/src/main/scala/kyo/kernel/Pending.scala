package kyo.kernel

import kyo.*
import kyo.kernel.Arrow
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.annotation.publicInBinary
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
  * `Emit[Log] & Abort[String]`: the order in which effects appear in the type does not determine the order in which they execute.
  *
  * The pending type has a single fundamental operation, the monadic bind, exposed as both `map` and `flatMap` (the latter for
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
  * functions, it is primarily designed for effect handling.
  *
  * #### Representation
  *
  * The type is opaque over a three-arm union, and each arm is load-bearing:
  *   - a raw `A`, which is why a plain value is already a computation and why a settled one costs no wrapper;
  *   - a `Pending` node, the family in `PendingInternal` reifying one combinator: a deferral, a suspension, a region entry, a parked slice
  *     or a stack snapshot;
  *   - a `Nested` wrapper, which is what the lift puts around a computation used as a value. Without an arm of its own, `Nothing < S`
  *     erases to `Pending` and a position holding a nested computation could not carry it.
  *
  * The combinators below build `Arrow` and `Defer` nodes and leave the stack-depth budget to the evaluator, which is why their function
  * parameters take no `Safepoint` evidence.
  */
opaque type <[+A, -S] = A | Pending[A, S] | Nested[A]

object `<` extends Implicits:

    extension [A, S](inline v: A < S)

        /** Maps the value produced by this computation to a new computation and flattens the result. This is the monadic bind operation for
          * the pending type.
          *
          * Note: `map` and `flatMap` have identical behavior in this API, both acting as the monadic bind. `map` is the recommended one;
          * `flatMap` exists to support for-comprehension syntax in Scala.
          *
          * @param f
          *   The transformation function to apply to the result
          * @return
          *   A new computation producing the transformed value
          */
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            @nowarn("msg=anonymous") def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) =
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
            // States the receiver's own type, so it holds by construction. Needed because under -Xcheck-macros the
            // inline body sees `<` through a proxy the compiler does not substitute into the union, leaving a
            // pending value type nothing to conform to. Erased.
            run(v.asInstanceOf[A < S], Arrow.id)
        end map

        /** Maps the value this computation produces, with no preemption point between the value arriving and `f` running.
          *
          * `map` polls the safepoint before applying its function, so an interrupt pending when the value arrives parks the
          * computation and `f` is never reached. That is wrong where `f` records an obligation the value has already created,
          * a resource that is open and whose finalizer is not yet registered: the park drops the registration and the value
          * leaks. This variant applies `f` as the value arrives, so an interrupt lands on either side of the pair.
          */
        inline def ensureMap[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            // Through `Arrow.ensure` rather than `new Arrow.Ensure` here: `Ensure` is `private[kyo]`, so naming it
            // in this expansion made the method uncallable from outside the package.
            // Cast per `map`'s note above.
            Arrow.ensure[A](f)(v.asInstanceOf[A < S])
        end ensureMap

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
        inline def flatMap[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            @nowarn("msg=anonymous") def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) =
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
            // Cast per `map`'s note above.
            run(v.asInstanceOf[A < S], Arrow.id)
        end flatMap

        /** Executes this computation, discards its result, and then executes another computation.
          *
          * @param f
          *   The computation to execute after this one
          * @return
          *   A computation producing the second result
          */
        inline def andThen[B, S2](inline f: => B < S2)(using inline _frame: Frame): B < (S & S2) =
            @nowarn("msg=anonymous") def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) =
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
            // Cast per `map`'s note above.
            run(v.asInstanceOf[A < S], Arrow.id)
        end andThen

        /** Executes this computation and discards its result.
          *
          * @return
          *   A computation that produces Unit
          */
        inline def unit(using inline _frame: Frame): Unit < S =
            @nowarn("msg=anonymous") def run[C, S3](v: A < S3, cont: Arrow[Unit, C, S3]): C < S3 =
                var slot: Safepoint.Slot = -1
                val shouldDefer          = v.isInstanceOf[Pending[?, ?]] || { slot = Safepoint.get(); !Safepoint.enter(slot) }
                if shouldDefer then
                    new Pending.DeferWith[A, C, S3]:
                        override def frame = _frame
                        def value          = v
                        override def apply[C2, S4](v2: A < S4, cont2: Arrow[C, C2, S4]) =
                            run(v2, cont.chain(cont2))
                else
                    // `Unit` is the union's first arm, so this holds by construction; same -Xcheck-macros reason
                    // as `map`'s cast above. Erased.
                    val out = cont.head(().asInstanceOf[Unit < S3], cont.tail)
                    Safepoint.exit(slot)
                    out
                end if
            end run
            // Cast per `map`'s note above.
            run(v.asInstanceOf[A < S], Arrow.id)
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
            def handle1 = v
            f(handle1)
        end handle

        // Every stage takes its computation by name, so a stage such as `Abort.run` sees an exception thrown
        // while the receiver is built.
        /** Applies two transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying both transformations
          */
        inline def handle[B, C](
            inline f1: (=> A < S) => B,
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
            inline f1: (=> A < S) => B,
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
            inline f1: (=> A < S) => B,
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
            inline f1: (=> A < S) => B,
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
            inline f1: (=> A < S) => B,
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
            inline f1: (=> A < S) => B,
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
            inline f1: (=> A < S) => B,
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
            def handle9 = v.handle(f1, f2, f3, f4, f5, f6, f7, f8)
            f9(handle9)
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
            def handle10 = v.handle(f1, f2, f3, f4, f5, f6, f7, f8, f9)
            f10(handle10)
        end handle

        private[kyo] inline def evalNow: Maybe[A] =
            v match
                case _: Pending[?, ?] => Maybe.empty
                case v                => Maybe(Nested.unnest(v))

    end extension

    extension [A, S, S2](v: A < S < S2)
        /** Flattens a nested pending computation into a single computation.
          *
          * @return
          *   A flattened computation of type `A` with combined effects `S & S2`
          */
        @nowarn("msg=anonymous")
        def flatten(using _frame: Frame): A < (S & S2) =
            def arrow: Arrow[A < S, A, S] =
                new Arrow.Step[A < S, A, S]:
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
            run(v, Arrow.id)
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
            v match
                case kyo: Pending[?, ?] => Nested.unnest[A](Eval(kyo.asInstanceOf[A < Any]))
                case v                  => Nested.unnest(v)
        end eval
    end extension

    // Public in binary rather than inline: an inline conversion binds a prefix proxy at every expansion site, a
    // private one goes through an inline accessor, and both grow every suspension (ArrowEffectBytecodeTest pins this).
    @publicInBinary implicit private[kernel] def fromKyo[A, S](v: Pending[A, S]): A < S = v

    given [A, S, APendingS <: A < S](using ra: Render[A]): Render[APendingS] with
        // A lifted value is wrapped in Nested, not a Pending, so it needs its own case to render as Kyo(...).
        def asString(value: APendingS): String = value match
            case sus: Pending[?, ?] => sus.toString
            case nested: Nested[?]  => s"Kyo(${nested.value})"
            case a: A @unchecked    => s"Kyo(${ra.asString(a)})"
    end given

end `<`
