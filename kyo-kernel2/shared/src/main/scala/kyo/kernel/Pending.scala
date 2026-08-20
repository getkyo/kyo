package kyo.kernel

import kyo.Arrow
import kyo.Arrow.Transform
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
            // TODO check if this expanded code uses other nested `inline` methods and report
            // Transform is referenced unqualified, through the import, and never as Arrow.Transform.
            // The combinators are inline, so the body is re-typechecked at the expansion site, and a
            // site outside package kyo cannot select a private[kyo] member from Arrow.type: the
            // qualified spelling fails there with "Found: kyo.Arrow.type, Required: ?{ Transform: ? }".
            // Reached through the import it resolves without that selection. kyo-compile-bench is the
            // only corpus outside package kyo, so it is the only thing that catches a regression here
            def arrow: Arrow[A, B, S2] =
                new Transform[A, B, S2]:
                    def frame                                          = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S2 & S3) =
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        Effect.defer(kyo, arrow, next)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, arrow, next)
                        else
                            val out = next.head(f(v.unsafeGet), next.tail)
                            Safepoint.exit(slot)
                            out
                        end if
            run(self, Arrow.id)
        end map

        @nowarn("msg=anonymous")
        inline def flatMap[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow =
                new Transform[A, B, S2]:
                    def frame                                          = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S2 & S3) =
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        Effect.defer(kyo, arrow, next)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, arrow, next)
                        else
                            val out = next.head(f(v.unsafeGet), next.tail)
                            Safepoint.exit(slot)
                            out
                        end if
            run(self, Arrow.id)
        end flatMap

        @nowarn("msg=anonymous")
        inline def andThen[B, S2](inline f: => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow =
                new Transform[A, B, S2]:
                    def frame                                          = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S2 & S3) =
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        Effect.defer(kyo, arrow, next)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
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
                new Transform[A, Unit, Any]:
                    def frame                                             = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[Unit, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[Unit, C, S3]): C < S3 =
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        Effect.defer(kyo, arrow, next)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
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
                new Transform[A, B, S2]:
                    def frame                                          = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S2 & S3) =
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        Effect.defer(kyo, arrow, next)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, arrow, next)
                        else
                            val out = next.head(ev(v.unsafeGet), next.tail)
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

        inline def eval(using S =:= Any): A =
            Eval(self.asInstanceOf[A < Any]).asInstanceOf[A]

        // bound once: `self` is inline, so every occurrence re-expands the receiver expression, and two
        // occurrences here would build `v.map(f)` twice and run `f` twice on the settled path
        inline def evalNow: Maybe[A] =
            val v = self
            v match
                case _: Kyo[?, ?] => Maybe.empty
                case _            => Maybe(v.unsafeGet)
        end evalNow

        /** The settled value, one nesting level stripped. Only valid where the pending case is already excluded. */
        inline def unsafeGet: A =
            self match
                case self: Nested[A] @unchecked => self.value
                case self                       => self.asInstanceOf[A]
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
