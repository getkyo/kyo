package kyo.proto

import kyo.Frame
import kyo.Maybe
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.targetName
import scala.util.NotGiven

opaque type <[+A, -S] = A | Kyo[A, S] | Nested[A]

object `<` extends Implicits:
    implicit private[proto] inline def fromKyo[A, S](inline k: Kyo[A, S]): A < S = k

    extension [A, S](inline self: A < S)

        @nowarn("msg=anonymous")
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow =
                new Arrow.Transform[A, B, S2]:
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
                new Arrow.Transform[A, B, S2]:
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
                new Arrow.Transform[A, B, S2]:
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
                new Arrow.Transform[A, Unit, Any]:
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
                new Arrow.Transform[A, B, S2]:
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

        inline def evalNow: Maybe[A] =
            self match
                case _: Kyo[?, ?] => Maybe.empty
                case _            => Maybe(self.unsafeGet)

        /** The settled value, one nesting level stripped. Only valid where the pending case is already excluded. */
        inline def unsafeGet: A =
            self match
                case self: Nested[A] @unchecked => self.value
                case self                       => self.asInstanceOf[A]
    end extension
end `<`
