package kyo.kernel

import kyo.Arrow
import kyo.Frame
import kyo.Maybe
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.language.implicitConversions

opaque type <[+A, -S] = A | Kyo[A, S]

object `<` extends Implicits:

    // the representation stays sealed: nodes convert privately instead of
    // publishing Kyo as a subtype of < through a lower bound
    implicit private[kernel] inline def fromKyo[A, S](v: Kyo[A, S]): A < S = v

    private val unitValue: Unit < Any = ()

    extension [A, S](self: A < S)

        @nowarn("msg=anonymous")
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            @nowarn("msg=anonymous") def mapLoop[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                def arrow =
                    new Arrow.Transform[A, C, S & S2 & S3]:
                        def frame = _frame
                        def apply[D, S4](v: A < S4, next2: Arrow[C, D, S4]) =
                            mapLoop(v, next.chain(next2))
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        kyo.map(arrow)
                    case v =>
                        val res  = Kyo.unnest(v)
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Kyo.Defer(v, arrow)
                        else
                            val step = next.step
                            val out  = step.head(f(res), step.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                end match
            end mapLoop
            mapLoop(self: A < S, Arrow[B])
        end map

        // flatMap, andThen, unit, and flatten carry their own full bodies
        // instead of delegating to map: an inline method whose body calls
        // another inline method expands twice per call site, and under nested
        // closures (every for comprehension) the re-expansion compounds with
        // depth, measured at 1.44x the old kernel's compile time before the
        // bodies were split (kyo-compile-bench, ForComprehensions)
        @nowarn("msg=anonymous")
        inline def flatMap[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            @nowarn("msg=anonymous") def flatMapLoop[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                def arrow =
                    new Arrow.Transform[A, C, S & S2 & S3]:
                        def frame = _frame
                        def apply[D, S4](v: A < S4, next2: Arrow[C, D, S4]) =
                            flatMapLoop(v, next.chain(next2))
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        kyo.map(arrow)
                    case v =>
                        val res  = Kyo.unnest(v)
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Kyo.Defer(v, arrow)
                        else
                            val step = next.step
                            val out  = step.head(f(res), step.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                end match
            end flatMapLoop
            flatMapLoop(self: A < S, Arrow[B])
        end flatMap

        @nowarn("msg=anonymous")
        inline def andThen[B, S2](inline f: => B < S2)(using inline _frame: Frame): B < (S & S2) =
            @nowarn("msg=anonymous") def andThenLoop[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                def arrow =
                    new Arrow.Transform[A, C, S & S2 & S3]:
                        def frame = _frame
                        def apply[D, S4](v: A < S4, next2: Arrow[C, D, S4]) =
                            andThenLoop(v, next.chain(next2))
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        kyo.map(arrow)
                    case v =>
                        // the value is discarded, so it stays boxed
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Kyo.Defer(v, arrow)
                        else
                            val step = next.step
                            val out  = step.head(f, step.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                end match
            end andThenLoop
            andThenLoop(self: A < S, Arrow[B])
        end andThen

        @nowarn("msg=anonymous")
        inline def unit(using inline _frame: Frame): Unit < S =
            @nowarn("msg=anonymous") def unitLoop[C, S3](v: A < S3, next: Arrow[Unit, C, S3]): C < (S & S3) =
                def arrow =
                    new Arrow.Transform[A, C, S & S3]:
                        def frame = _frame
                        def apply[D, S4](v: A < S4, next2: Arrow[C, D, S4]) =
                            unitLoop(v, next.chain(next2))
                v match
                    case kyo: Kyo[A, S3] @unchecked =>
                        kyo.map(arrow)
                    case v =>
                        // the value is discarded, so it stays boxed
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Kyo.Defer(v, arrow)
                        else
                            val step = next.step
                            val out  = step.head(`<`.unitValue, step.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                end match
            end unitLoop
            unitLoop(self: A < S, Arrow[Unit])
        end unit

        inline def eval(using S =:= Any): A =
            (self: A < S) match
                case kyo: Kyo[?, ?] =>
                    Eval(self: A < S) match
                        case kyo: Kyo[?, ?] => throw new IllegalStateException(s"unhandled suspension: $kyo")
                        case v              => Kyo.unnest(v.asInstanceOf[A < Any])
                case v =>
                    Kyo.unnest(v.asInstanceOf[A < Any])
        end eval

        private[kyo] inline def evalNow: Maybe[A] =
            (self: A < S) match
                case kyo: Kyo[?, ?] => Maybe.Absent
                case v              => Maybe(Kyo.unnest(v))

        /** Applies a transformation to this computation, allowing a fluent
          * style for effect handling: `computation.handle(Abort.run, Env.run(1))`
          * instead of `Env.run(1)(Abort.run(computation))`.
          */
        inline def handle[B](inline f: (=> A < S) => B): B =
            f(self: A < S)

        inline def handle[B, C](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C
        ): C =
            def handle2 = handle(f1)
            f2(handle2)
        end handle

        inline def handle[B, C, D](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D
        ): D =
            def handle3 = handle(f1, f2)
            f3(handle3)
        end handle

        inline def handle[B, C, D, E](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E
        ): E =
            def handle4 = handle(f1, f2, f3)
            f4(handle4)
        end handle

        inline def handle[B, C, D, E, F](
            inline f1: (=> A < S) => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F
        ): F =
            def handle5 = handle(f1, f2, f3, f4)
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
            def handle6 = handle(f1, f2, f3, f4, f5)
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
            def handle7 = handle(f1, f2, f3, f4, f5, f6)
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
            def handle8 = handle(f1, f2, f3, f4, f5, f6, f7)
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
            def handle9 = handle(f1, f2, f3, f4, f5, f6, f7, f8)
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
            def handle10 = handle(f1, f2, f3, f4, f5, f6, f7, f8, f9)
            f10(handle10)
        end handle

    end extension

    extension [A, S, S2](self: A < S < S2)
        /** Flattens a nested pending computation into a single computation. */
        @nowarn("msg=anonymous")
        inline def flatten(using inline _frame: Frame): A < (S & S2) =
            @nowarn("msg=anonymous") def flattenLoop[C, S3](v: (A < S) < S3, next: Arrow[A, C, S3]): C < (S & S2 & S3) =
                def arrow =
                    new Arrow.Transform[A < S, C, S & S2 & S3]:
                        def frame = _frame
                        def apply[D, S4](v: (A < S) < S4, next2: Arrow[C, D, S4]) =
                            flattenLoop(v, next.chain(next2))
                v match
                    case kyo: Kyo[A < S, S3] @unchecked =>
                        kyo.map(arrow)
                    case v =>
                        val res  = Kyo.unnest(v)
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Kyo.Defer(v, arrow)
                        else
                            val step = next.step
                            val out  = step.head(res, step.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                end match
            end flattenLoop
            flattenLoop(self: A < S < S2, Arrow[A])
        end flatten
    end extension

end `<`
