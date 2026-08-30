package kyo.proto.kernel

import kyo.Frame
import kyo.Maybe
import kyo.Render
import kyo.proto.Arrow
import kyo.proto.Arrow.Step
import kyo.proto.Arrow.Transform
import kyo.proto.kernel.internal.*
import language.implicitConversions
import scala.annotation.nowarn

// no lower bound on purpose: a raw value reaches the pending type only through the implicit lift,
// which is what preserves a computation held as data (the lift nests payloads; subsumption would
// carry them bare and erase their data-ness where they surface)
opaque type <[+A, -S] = A | Pending[A, S]

object `<` extends Implicits:
    implicit def fromKyo[A, S](kyo: Pending[A, S]): A < S = kyo

    // the receiver is inline so call sites splice it with its ascribed type: a proxied receiver is
    // typed as its singleton intersected with the pending type, and that conformance fails against a
    // nested payload. Every occurrence re-expands the receiver expression, so each body binds it once
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
                    // one allocation fulfilling both roles: the rescue record and its own transform,
                    // with the continuation in the closure, composed by the apply
                    new Kyo.DeferTransform[A, C, S2 & S3]:
                        override def frame = _frame
                        def value          = v
                        def contA          = this
                        def contB          = Arrow.id
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
                    // one allocation fulfilling both roles: the rescue record and its own transform,
                    // with the continuation in the closure, composed by the apply
                    new Kyo.DeferTransform[A, C, S2 & S3]:
                        override def frame = _frame
                        def value          = v
                        def contA          = this
                        def contB          = Arrow.id
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
                    // one allocation fulfilling both roles: the rescue record and its own transform,
                    // with the continuation in the closure, composed by the apply
                    new Kyo.DeferTransform[A, C, S2 & S3]:
                        override def frame = _frame
                        def value          = v
                        def contA          = this
                        def contB          = Arrow.id
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
                    // one allocation fulfilling both roles: the rescue record and its own transform,
                    // with the continuation in the closure, composed by the apply
                    new Kyo.DeferTransform[A, C, S3]:
                        override def frame = _frame
                        def value          = v
                        def contA          = this
                        def contB          = Arrow.id
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

        /** Applies seven transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying all transformations in sequence
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

        /** Applies eight transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying all transformations in sequence
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

        /** Applies nine transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying all transformations in sequence
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

        /** Applies ten transformations to this computation in sequence.
          *
          * Enables chaining multiple effect handlers or transformations in a readable sequential style.
          *
          * @return
          *   The result after applying all transformations in sequence
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

        // the settled arm stays in the caller's compilation on purpose: a value that never suspended has
        // its result born in the caller's own map expansion, and delivering it through the non-inline
        // interpreter boundary makes that box a real allocation. Unnesting here instead lets escape
        // analysis finish the job, and only a node graph pays the eval.
        private[kyo] inline def evalNow: Maybe[A] =
            val v = self
            v match
                case _: Pending[?, ?] => Maybe.empty
                case _                => Maybe(Nested.unnest(v))
        end evalNow
    end extension

    // chain stays non-inline, so it lives outside the inline-receiver block
    extension [A, S](self: A < S)

        /** Composes this computation with a continuation. Value composition is the normalizing entry: a node with a free continuation slot
          * absorbs the arrow directly, and anything else is reified as a deferral record. Continuation composition stays [[Arrow.chain]];
          * the two roles meet only here.
          */
        def chain[B, S2](cont: Arrow[A, B, S2]): B < (S & S2) =
            self match
                case kyo: Pending[A, S] @unchecked =>
                    if cont.isInstanceOf[Arrow.Id[?]] then
                        // Id pins B = A
                        kyo.asInstanceOf[B < (S & S2)]
                    else
                        kyo match
                            case kyo: Kyo.Suspend[?, A, S] @unchecked if kyo.cont.isInstanceOf[Arrow.Id[?]] =>
                                // cont eq Id pins Op = A, so the arrow composes directly into the free slot
                                kyo.withCont(cont.asInstanceOf[Arrow[kyo.Op, B, S & S2]])
                            case kyo: Kyo.Handle[e, x, b, A, S, st] @unchecked if kyo.cont.isInstanceOf[Arrow.Id[?]] =>
                                // cont eq Id pins b = A, so the arrow composes directly into the free slot
                                Kyo.Handle[e, x, b, B, S & S2, st](
                                    kyo.value,
                                    kyo.handler,
                                    kyo.state,
                                    cont.asInstanceOf[Arrow[b, B, S & S2]]
                                )
                            case kyo: Kyo.Defer[a, b, A, S] @unchecked
                                if kyo.contB.isInstanceOf[Arrow.Id[?]] && !(kyo.contA eq kyo) =>
                                // contB eq Id pins b = A; the free-slot law re-evaluates the record, which is only
                                // valid for a constant record: a self-fulfilling one consumes its input in arrow
                                // position and is reified below instead
                                Effect.defer(kyo.value, kyo.contA, cont.asInstanceOf[Arrow[b, B, S & S2]])
                            case _ =>
                                Effect.defer(kyo, cont)
                case _ =>
                    cont.head(self.asInstanceOf[A], cont.tail)
            end match
        end chain
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
          */
        inline def eval(using inline frame: Frame): A =
            // bound once: `v` is inline, so every occurrence re-expands the receiver expression
            val v0 = v
            v0 match
                case _: Pending[?, ?] => Nested.unnest[A](Eval(v0.asInstanceOf[A < Any]))
                case _                => Nested.unnest(v0)
        end eval
    end extension

    /** A pending computation renders as its payload wrapped in `Kyo(...)`, with the payload rendered by its own instance, so the wrapper
      * says the value is a computation without hiding what it holds. A computation that has not settled renders as the operation it is
      * waiting on. A payload that is itself a computation renders through its own `toString` rather than through `ra`, which would be this
      * same instance and would not terminate.
      */
    given [A, S, APendingS <: A < S](using ra: Render[A]): Render[APendingS] with
        def asString(value: APendingS): String =
            value match
                case kyo: Pending[?, ?] => kyo.toString
                case nested: Nested[?]  => s"Kyo(${nested.value})"
                case a: A @unchecked    => s"Kyo(${ra.asString(a)})"
    end given
end `<`
