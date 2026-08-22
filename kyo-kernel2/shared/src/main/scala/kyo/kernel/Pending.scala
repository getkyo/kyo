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

opaque type <[+A, -S] = A | Kyo[A, S] | Nested[A]

object `<` extends Implicits:
    implicit private[kernel] inline def fromKyo[A, S](inline k: Kyo[A, S]): A < S = k

    extension [A, S](inline self: A < S) // TODO check the impact in compile and runtime perf of marking self as inline here

        @nowarn("msg=anonymous")
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow: Arrow[A, B, S2] =
                new TransformBase[A, B, S2]:
                    def frame                                          = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S2 & S3) =
                var slot: Safepoint.Slot = -1
                val shouldDefer          = v.isInstanceOf[Kyo[?, ?]] || { slot = Safepoint.get(); !Safepoint.enter(slot, _frame) }
                if shouldDefer then Effect.defer(v, arrow, next)
                else
                    val out = next.head(f(Nested.unnest(v)), next.tail)
                    Safepoint.exit(slot)
                    out
                end if
            end run
            run(self, Arrow.id)
        end map

        @nowarn("msg=anonymous")
        inline def flatMap[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow =
                new TransformBase[A, B, S2]:
                    def frame                                          = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S2 & S3) =
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        Effect.defer(kyo, arrow, next)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot, _frame) then
                            Effect.defer(v, arrow, next)
                        else
                            val out = next.head(f(Nested.unnest(v)), next.tail)
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
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S2 & S3) =
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        Effect.defer(kyo, arrow, next)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot, _frame) then
                            Effect.defer(v, arrow, next)
                        else
                            val out = next.head(f, next.tail)
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
                    def apply[C, S3](v: A < S3, next: Arrow[Unit, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[Unit, C, S3]): C < S3 =
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        Effect.defer(kyo, arrow, next)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot, _frame) then
                            Effect.defer(v, arrow, next)
                        else
                            val out = next.head((), next.tail)
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
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S2 & S3) =
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        Effect.defer(kyo, arrow, next)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot, _frame) then
                            Effect.defer(v, arrow, next)
                        else
                            val out = next.head(ev(Nested.unnest(v)), next.tail)
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
