package kyo.kernel

import kyo.Arrow
import kyo.Frame
import kyo.Maybe
import scala.annotation.nowarn
import scala.annotation.static
import scala.language.implicitConversions

private[kyo] trait Boxed

opaque type <[+A, -S] = A | Arrow[Any, A, S] | Nested[A]

// TODO let's move to Nested.scala
final private[kyo] case class Nested[+A](value: A) extends Boxed

object Nested:

    /** The runtime arm the lift emission calls when a value of the type could be a computation. A monomorphic bridge rather than the
      * wrapping directly: the emission lands at every generic lift site, and the shortest call keeps those sites inside the JIT's inline
      * budget.
      */
    @static def nest[A, S](v: A): A < S =
        v match
            case v: Boxed => Nested(v).asInstanceOf[A < S]
            case v        => v.asInstanceOf[A < S]

    @static def unnest[A](v: Any): A =
        (v match
            case n: Nested[?] => n.value
            case _            => v
        ).asInstanceOf[A]
end Nested

object `<` extends Implicits:
    // TODO why isn't this in Implicits?
    implicit def fromArrow[A, S](v: Arrow[Any, A, S]): A < S = v

    extension [A, S](self: A < S)

        @nowarn("msg=anonymous")
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow =
                new Arrow.Transform[A, B, S & S2]:
                    def frame = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                        run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                v match
                    case v: Arrow[Any, A, S3] @unchecked =>
                        v.chain(arrow.chain(next))
                    case v =>
                        val res  = Nested.unnest[A](v)
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Arrow.Bind(v, arrow.chain(next))
                        else
                            val step = next.step
                            val out  = step.head(f(res), step.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                end match
            end run
            run(self: A < S, Arrow[B])
        end map

        @nowarn("msg=anonymous")
        inline def flatMap[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow =
                new Arrow.Transform[A, B, S & S2]:
                    def frame = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                        run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                v match
                    case v: Arrow[Any, A, S3] @unchecked =>
                        v.chain(arrow.chain(next))
                    case v =>
                        val res  = Nested.unnest[A](v)
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Arrow.Bind(v, arrow.chain(next))
                        else
                            val step = next.step
                            val out  = step.head(f(res), step.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                end match
            end run
            run(self: A < S, Arrow[B])
        end flatMap

        @nowarn("msg=anonymous")
        inline def andThen[B, S2](inline f: => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow =
                new Arrow.Transform[A, B, S & S2]:
                    def frame = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                        run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                v match
                    case v: Arrow[Any, A, S3] @unchecked =>
                        v.chain(arrow.chain(next))
                    case v =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Arrow.Bind(v, arrow.chain(next))
                        else
                            val step = next.step
                            val out  = step.head(f, step.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                end match
            end run
            run(self: A < S, Arrow[B])
        end andThen

        @nowarn("msg=anonymous")
        inline def unit(using inline _frame: Frame): Unit < S =
            def arrow =
                new Arrow.Transform[A, Unit, S]:
                    def frame = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[Unit, C, S3]): C < (S & S3) =
                        run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[Unit, C, S3]): C < (S & S3) =
                v match
                    case v: Arrow[Any, A, S3] @unchecked =>
                        v.chain(arrow.chain(next))
                    case v =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Arrow.Bind(v, arrow.chain(next))
                        else
                            val step = next.step
                            val out  = step.head((), step.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                end match
            end run
            run(self: A < S, Arrow[Unit])
        end unit

        inline def eval(using S =:= Any): A =
            Eval((self: A < S).asInstanceOf[A < Any])

        private[kyo] inline def evalNow: Maybe[A] =
            (self: A < S) match
                case _: Arrow[?, ?, ?] => Maybe.Absent
                case v                 => Maybe(Nested.unnest[A](v))

        /** Applies a transformation to this computation, allowing a fluent style for effect handling:
          * `computation.handle(Abort.run, Env.run(1))` instead of `Env.run(1)(Abort.run(computation))`.
          */
        inline def handle[B](inline f: (=> A < S) => B): B =
            f(self: A < S)

        inline def handle[B, C](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C
        ): C =
            def handle2 = (self: A < S).handle(f1)
            f2(handle2)
        end handle

        inline def handle[B, C, D](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D
        ): D =
            def handle3 = (self: A < S).handle(f1, f2)
            f3(handle3)
        end handle

        inline def handle[B, C, D, E](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E
        ): E =
            def handle4 = (self: A < S).handle(f1, f2, f3)
            f4(handle4)
        end handle

        inline def handle[B, C, D, E, F](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F
        ): F =
            def handle5 = (self: A < S).handle(f1, f2, f3, f4)
            f5(handle5)
        end handle

        inline def handle[B, C, D, E, F, G](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G
        ): G =
            def handle6 = (self: A < S).handle(f1, f2, f3, f4, f5)
            f6(handle6)
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
            def handle7 = (self: A < S).handle(f1, f2, f3, f4, f5, f6)
            f7(handle7)
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
            def handle8 = (self: A < S).handle(f1, f2, f3, f4, f5, f6, f7)
            f8(handle8)
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
            def handle9 = (self: A < S).handle(f1, f2, f3, f4, f5, f6, f7, f8)
            f9(handle9)
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
            def handle10 = (self: A < S).handle(f1, f2, f3, f4, f5, f6, f7, f8, f9)
            f10(handle10)
        end handle

    end extension

    extension [A, S, S2](self: A < S < S2)

        @nowarn("msg=anonymous")
        inline def flatten(using inline _frame: Frame): A < (S & S2) =
            def arrow =
                new Arrow.Transform[A < S, A, S & S2]:
                    def frame = _frame
                    def apply[C, S3](v: A < S < S3, next: Arrow[A, C, S3]): C < (S & S2 & S3) =
                        run(v, next)
            def run[C, S3](v: A < S < S3, next: Arrow[A, C, S3]): C < (S & S2 & S3) =
                v match
                    case v: Arrow[Any, A < S, S3] @unchecked =>
                        v.chain(arrow.chain(next))
                    case v =>
                        val res  = Nested.unnest[A < S](v)
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Arrow.Bind(v, arrow.chain(next))
                        else
                            val step = next.step
                            val out  = step.head(res, step.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                end match
            end run
            run(self: A < S < S2, Arrow[A])
        end flatten

    end extension

end `<`
