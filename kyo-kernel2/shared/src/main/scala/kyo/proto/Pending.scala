package kyo.proto

import kyo.Frame
import kyo.Maybe
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.targetName
import scala.util.NotGiven

opaque type <[+A, -S] = A | Kyo[A, S] | Nested[A]

object `<`:
    implicit private[proto] def fromKyo[A, S](k: Kyo[A, S]): A < S = k

    // the one lift. A box lifted again is boxed again, so each level of nesting is one Nested and
    // lower strips one. A statically pending value never lifts implicitly: inference would nest a
    // computation where a merged row was meant (issue 903), and a generic function is the way to
    // hold a computation as a value on purpose
    implicit def lift[A](v: A)(using NotGiven[A <:< (Any < Nothing)]): A < Any =
        v match
            case v: (Kyo[?, ?] | Nested[?]) => Nested(v)
            case _                          => v

    extension [A, S](inline self: A < S)

        @nowarn("msg=anonymous")
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow =
                new Arrow.Transform[A, B, S2]:
                    def frame                                          = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S2 & S3) =
                v.lower(
                    pending = kyo => fromKyo(Kyo.Defer(kyo, arrow, next)),
                    done = b =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            fromKyo(Kyo.Defer(v, arrow, next))
                        else
                            val out = next.head(f(b), next.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                )
            run(self, Arrow.id)
        end map

        @nowarn("msg=anonymous")
        inline def flatMap[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow =
                new Arrow.Transform[A, B, S2]:
                    def frame                                          = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S2 & S3) =
                v.lower(
                    pending = kyo => fromKyo(Kyo.Defer(kyo, arrow, next)),
                    done = b =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            fromKyo(Kyo.Defer(v, arrow, next))
                        else
                            val out = next.head(f(b), next.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                )
            run(self, Arrow.id)
        end flatMap

        @nowarn("msg=anonymous")
        inline def andThen[B, S2](inline f: => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow =
                new Arrow.Transform[A, B, S2]:
                    def frame                                          = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S2 & S3) =
                v.lower(
                    pending = kyo => fromKyo(Kyo.Defer(kyo, arrow, next)),
                    done = _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            fromKyo(Kyo.Defer(v, arrow, next))
                        else
                            val out = next.head(f, next.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                )
            run(self, Arrow.id)
        end andThen

        @nowarn("msg=anonymous")
        inline def unit(using inline _frame: Frame): Unit < S =
            def arrow =
                new Arrow.Transform[A, Unit, Any]:
                    def frame                                             = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[Unit, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[Unit, C, S3]): C < S3 =
                v.lower(
                    pending = kyo => fromKyo(Kyo.Defer(kyo, arrow, next)),
                    done = _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            fromKyo(Kyo.Defer(v, arrow, next))
                        else
                            val out = next.head((), next.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                )
            run(self, Arrow.id)
        end unit

        @nowarn("msg=anonymous")
        inline def flatten[B, S2](using ev: A <:< (B < S2), inline _frame: Frame): B < (S & S2) =
            def arrow =
                new Arrow.Transform[A, B, S2]:
                    def frame                                          = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]) = run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S2 & S3) =
                v.lower(
                    pending = kyo => fromKyo(Kyo.Defer(kyo, arrow, next)),
                    done = b =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            fromKyo(Kyo.Defer(v, arrow, next))
                        else
                            val out = next.head(ev(b), next.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                )
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
            self.lower(pending = _ => Maybe.empty, done = a => Maybe(a))

        inline def lower[B](
            inline pending: Kyo[A, S] => B,
            inline done: A => B
        ): B =
            self match
                case self: Kyo[A, S] @unchecked => pending(self)
                case self =>
                    val value =
                        self match
                            case self: Nested[A] @unchecked => self.value
                            case self                       => self.asInstanceOf[A]
                    done(value)
            end match
        end lower
    end extension
end `<`
