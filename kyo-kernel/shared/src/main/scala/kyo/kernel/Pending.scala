package kyo.kernel

import kyo.Arrow
import kyo.Arrow.Transform
import kyo.Arrow.TransformBase
import kyo.Frame
import kyo.Maybe
import kyo.Render
import kyo.kernel.internal.*
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.targetName
import scala.util.NotGiven

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
opaque type <[+A, -S] = A | Kyo[A, S] | Nested[A]

object `<` extends Implicits:
    implicit private[kernel] inline def fromKyo[A, S](inline k: Kyo[A, S]): A < S = k

    extension [A, S](inline self: A < S) // TODO check the impact in compile and runtime perf of marking self as inline here

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
            def arrow: Arrow[A, B, S2] =
                new TransformBase[A, B, S2]:
                    def frame                                          = _frame
                    def apply[C, S3](v: A < S3, cont: Arrow[B, C, S3]) = run(v, cont)
            def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                var slot: Safepoint.Slot = -1
                val shouldDefer          = v.isInstanceOf[Kyo[?, ?]] || { slot = Safepoint.get(); !Safepoint.enter(slot) }
                if shouldDefer then Effect.defer(v, arrow, cont)
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
            def arrow =
                new TransformBase[A, B, S2]:
                    def frame                                          = _frame
                    def apply[C, S3](v: A < S3, cont: Arrow[B, C, S3]) = run(v, cont)
            def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        Effect.defer(kyo, arrow, cont)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, arrow, cont)
                        else
                            val out = cont.head(f(Nested.unnest(v)), cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if
            run(self, Arrow.id)
        end flatMap

        @nowarn("msg=anonymous")
        inline def andThen[B, S2](inline f: => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow =
                new TransformBase[A, B, S2]:
                    def frame                                          = _frame
                    def apply[C, S3](v: A < S3, cont: Arrow[B, C, S3]) = run(v, cont)
            def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        Effect.defer(kyo, arrow, cont)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, arrow, cont)
                        else
                            val out = cont.head(f, cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if
            run(self, Arrow.id)
        end andThen

        @nowarn("msg=anonymous")
        inline def unit(using inline _frame: Frame): Unit < S =
            def arrow =
                new TransformBase[A, Unit, Any]:
                    def frame                                             = _frame
                    def apply[C, S3](v: A < S3, cont: Arrow[Unit, C, S3]) = run(v, cont)
            def run[C, S3](v: A < S3, cont: Arrow[Unit, C, S3]): C < S3 =
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        Effect.defer(kyo, arrow, cont)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, arrow, cont)
                        else
                            val out = cont.head((), cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if
            run(self, Arrow.id)
        end unit

        @nowarn("msg=anonymous")
        inline def flatten[B, S2](using ev: A <:< (B < S2), inline _frame: Frame): B < (S & S2) =
            def arrow =
                new TransformBase[A, B, S2]:
                    def frame                                          = _frame
                    def apply[C, S3](v: A < S3, cont: Arrow[B, C, S3]) = run(v, cont)
            def run[C, S3](v: A < S3, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        Effect.defer(kyo, arrow, cont)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, arrow, cont)
                        else
                            val out = cont.head(ev(Nested.unnest(v)), cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if
            run(self, Arrow.id)
        end flatten

        inline def handle[B](inline f: (=> A < S) => B): B =
            def h1 = self
            f(h1)
        end handle

        inline def handle[B, C](inline f1: (=> A < S) => B, inline f2: (=> B) => C): C =
            def h1 = self
            def h2 = f1(h1)
            f2(h2)
        end handle

        inline def handle[B, C, D](inline f1: (=> A < S) => B, inline f2: (=> B) => C, inline f3: (=> C) => D): D =
            def h1 = self
            def h2 = f1(h1)
            def h3 = f2(h2)
            f3(h3)
        end handle

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

        // the settled arm stays in the caller's compilation on purpose: a value that never suspended has
        // its result born in the caller's own map expansion, and delivering it through the non-inline
        // interpreter boundary makes that box a real allocation (measured 14 B and 3ns per settled eval).
        // Unnesting here instead lets escape analysis finish the job, and only a node graph pays the eval.
        // Observationally the interpreter does exactly this for a settled value: unnest, empty stack, return
        inline def eval(using S =:= Any): A =
            // bound once, for the reason evalNow binds once
            val v = self
            v match
                case _: Kyo[?, ?] => Eval(v.asInstanceOf[A < Any]).asInstanceOf[A]
                case _            => Nested.unnest(v)
        end eval

        // bound once: `self` is inline, so every occurrence re-expands the receiver expression, and two
        // occurrences here would build `v.map(f)` twice and run `f` twice on the settled path
        inline def evalNow: Maybe[A] =
            val v = self
            v match
                case _: Kyo[?, ?] => Maybe.empty
                case _            => Maybe(Nested.unnest(v))
        end evalNow

        /** Runs the releases this computation still owes, for a holder giving up on resuming it.
          *
          * Only a parked computation owes anything here: a bracket releases where its use ends, and one whose
          * slice ended early carries what it had not released yet. Anything else is a no-op.
          *
          * Delegates rather than matching in place, so the expansion is one call and carries no proxy for the
          * opaque type's owner.
          */
        private[kyo] inline def finalizeResources: Unit =
            Eval.finalizeResources(self.asInstanceOf[Any < Any])
    end extension

    /** A pending computation renders as its payload wrapped in `Kyo(...)`, with the payload rendered by its own instance, so the wrapper
      * says the value is a computation without hiding what it holds. A computation that has not settled renders as the operation it is
      * waiting on. A payload that is itself a computation renders through its own `toString` rather than through `ra`, which would be this
      * same instance and would not terminate.
      */
    given [A, S, APendingS <: A < S](using ra: Render[A]): Render[APendingS] with
        def asString(value: APendingS): String =
            value match
                case kyo: Kyo[?, ?]    => kyo.toString
                case nested: Nested[?] => s"Kyo(${nested.value})"
                case a: A @unchecked   => s"Kyo(${ra.asString(a)})"
    end given
end `<`
