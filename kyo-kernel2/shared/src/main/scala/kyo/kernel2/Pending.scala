package kyo.kernel2

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel2.internal.Context
import kyo.kernel2.internal.Eval
import kyo.kernel2.internal.Finalize
import kyo.kernel2.internal.Handlers
import kyo.kernel2.internal.Kyo
import language.implicitConversions
import scala.annotation.nowarn

opaque type <[+A, -S] = A | Kyo[A, S] | Kyo.Nested[A]

object `<` extends Implicits:

    implicit private[kyo] inline def fromKyo[A, S](v: Kyo[A, S]): A < S = v

    extension [A, S](self: A < S)

        // runtime machinery, not user surface: runs the finalizers a parked computation's
        // brackets carry. The scheduler calls it when dropping a continuation that will
        // never be resumed.
        private[kyo] def finalizeBracket: Unit =
            val errors = Finalize.finalizeValue(self)
            errors.headMaybe match
                case Maybe.Present(t) =>
                    errors.dropLeft(1).foreach(t.addSuppressed)
                    throw t
                case Maybe.Absent => ()
            end match
        end finalizeBracket
    end extension

    extension [A, S](inline self: A < S)
        @nowarn
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            val arrow = new Arrow.Transform[A, B, S2]:
                def frame = _frame
                def run[C, S3](v: A, context: Context, handlers: Handlers, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                    val w = f(v)
                    cont match
                        // the inlined dispatch below repeats across the minted transforms deliberately: routing it through a
                        // shared method, shape-preserving or not, was measured at +7% to +38% time and up to +50% allocation
                        // (deepBind10k, narrowIter, suspension), because each expansion needs its own JIT profile
                        case o: Arrow.Step[Any, Any, Any, Any] @unchecked if !w.isInstanceOf[Kyo[?, ?]] =>
                            o.head.run(Kyo.unnest(w), context, handlers, o.next).asInstanceOf[C < (S2 & S3)]
                        case _ =>
                            cont(w, context, handlers)
                    end match
                end run
            // construction-time eager entry: the just-minted transform cannot consume
            // a context, so the empty argument is inert
            arrow(self, Context.empty, Handlers.empty)
        end map

        /** Maps the value produced by this computation to a new computation and flattens the result.
          *
          * This method exists to support for-comprehension syntax in Scala. It is identical to `map` and `map` should be preferred when not
          * using for-comprehensions.
          */
        @nowarn
        inline def flatMap[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            val arrow = new Arrow.Transform[A, B, S2]:
                def frame = _frame
                def run[C, S3](v: A, context: Context, handlers: Handlers, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                    val w = f(v)
                    cont match
                        case o: Arrow.Step[Any, Any, Any, Any] @unchecked if !w.isInstanceOf[Kyo[?, ?]] =>
                            o.head.run(Kyo.unnest(w), context, handlers, o.next).asInstanceOf[C < (S2 & S3)]
                        case _ =>
                            cont(w, context, handlers)
                    end match
                end run
            arrow(self, Context.empty, Handlers.empty)
        end flatMap

        /** Executes this computation, discards its result, and then executes another computation. */
        @nowarn
        inline def andThen[B, S2](inline f: => B < S2)(using inline _frame: Frame): B < (S & S2) =
            val arrow = new Arrow.Transform[A, B, S2]:
                def frame = _frame
                def run[C, S3](v: A, context: Context, handlers: Handlers, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                    val w = f
                    cont match
                        case o: Arrow.Step[Any, Any, Any, Any] @unchecked if !w.isInstanceOf[Kyo[?, ?]] =>
                            o.head.run(Kyo.unnest(w), context, handlers, o.next).asInstanceOf[C < (S2 & S3)]
                        case _ =>
                            cont(w, context, handlers)
                    end match
                end run
            arrow(self, Context.empty, Handlers.empty)
        end andThen

        /** Executes this computation and discards its result. */
        @nowarn
        inline def unit(using inline _frame: Frame): Unit < S =
            val arrow = new Arrow.Transform[A, Unit, Any]:
                def frame = _frame
                def run[C, S3](v: A, context: Context, handlers: Handlers, cont: Arrow[Unit, C, S3]): C < (Any & S3) =
                    cont match
                        case o: Arrow.Step[Any, Any, Any, Any] @unchecked =>
                            o.head.run((), context, handlers, o.next).asInstanceOf[C < (Any & S3)]
                        case _ =>
                            cont((), context, handlers)
                    end match
                end run
            arrow(self, Context.empty, Handlers.empty)
        end unit

        /** Applies a transformation to this computation.
          *
          * Passes the computation to the transformation function, enabling a fluent style for effect handling:
          * `computation.handle(Abort.run, Env.run(1))` instead of `Env.run(1)(Abort.run(computation))`. The multi-parameter versions chain
          * transformations in sequence.
          */
        inline def handle[B](inline f: (=> A < S) => B): B =
            def handle1 = self
            f(handle1)
        end handle

        /** Applies two transformations to this computation in sequence. */
        inline def handle[B, C](
            inline f1: A < S => B,
            inline f2: (=> B) => C
        ): C =
            def handle2 = self.handle(f1)
            f2(handle2)
        end handle

        /** Applies three transformations to this computation in sequence. */
        inline def handle[B, C, D](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D
        ): D =
            def handle3 = self.handle(f1, f2)
            f3(handle3)
        end handle

        /** Applies four transformations to this computation in sequence. */
        inline def handle[B, C, D, E](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E
        ): E =
            def handle4 = self.handle(f1, f2, f3)
            f4(handle4)
        end handle

        /** Applies five transformations to this computation in sequence. */
        inline def handle[B, C, D, E, F](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F
        ): F =
            def handle5 = self.handle(f1, f2, f3, f4)
            f5(handle5)
        end handle

        /** Applies six transformations to this computation in sequence. */
        inline def handle[B, C, D, E, F, G](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G
        ): G =
            def handle6 = self.handle(f1, f2, f3, f4, f5)
            f6(handle6)
        end handle

        /** Applies seven transformations to this computation in sequence. */
        inline def handle[B, C, D, E, F, G, H](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H
        ): H =
            def handle7 = self.handle(f1, f2, f3, f4, f5, f6)
            f7(handle7)
        end handle

        /** Applies eight transformations to this computation in sequence. */
        inline def handle[B, C, D, E, F, G, H, I](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H,
            inline f8: (=> H) => I
        ): I =
            def handle8 = self.handle(f1, f2, f3, f4, f5, f6, f7)
            f8(handle8)
        end handle

        /** Applies nine transformations to this computation in sequence. */
        inline def handle[B, C, D, E, F, G, H, I, J](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H,
            inline f8: (=> H) => I,
            inline f9: (=> I) => J
        ): J =
            def handle9 = self.handle(f1, f2, f3, f4, f5, f6, f7, f8)
            f9(handle9)
        end handle

        /** Applies ten transformations to this computation in sequence. */
        inline def handle[B, C, D, E, F, G, H, I, J, K](
            inline f1: A < S => B,
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
            def handle10 = self.handle(f1, f2, f3, f4, f5, f6, f7, f8, f9)
            f10(handle10)
        end handle

        /** The value when this computation is already complete, or Absent when it is suspended. */
        private[kyo] inline def evalNow: Maybe[A] =
            self match
                case _: Kyo[?, ?] => Maybe.empty
                case v            => Maybe(Kyo.settled(v))

    end extension

    extension [A, S, S2](self: A < S < S2)
        /** Flattens a nested pending computation into a single computation. */
        def flatten(using Frame): A < (S & S2) =
            self.map(v => v)
    end extension

    extension [A](self: A < Any)

        /** Evaluates the computation to its value.
          *
          * The drive runs Masked: eval must return `A`, so it cannot hand back a remainder, and a non-polling drive would spin on a
          * pending request; it absorbs requests and re-issues them at exit so an enclosing slice still sees them. All effects must
          * have handlers installed; reaching a suspension no handler matched is a defect and throws.
          */
        def eval: A =
            if self.isInstanceOf[Kyo[?, ?]] then
                Eval.evalLoop(self.asInstanceOf[Any < Any], Eval.Masked, Context.empty, Handlers.empty) match
                    case pending: Kyo[?, ?] => kyo.bug.failTag(pending.asInstanceOf[Any < Any], Tag[Any])
                    case v                  => Kyo.unnest(v).asInstanceOf[A]
            else Kyo.settled(self)

        /** Evaluates until the computation completes or a preemption request is consumed, returning the remainder.
          *
          * The plain Preemptible evaluation: the caller re-schedules the remainder and drives it again. The request is consumed once
          * at exit, so the caller's authoritative check after this returns observes every condition published before the request.
          */
        private[kyo] def evalPartial: A < Any =
            Eval.evalLoop(self.asInstanceOf[Any < Any], Eval.Preemptible, Context.empty, Handlers.empty).asInstanceOf[A < Any]

    end extension

end `<`

extension (self: kyo.bug.type)
    private[kyo] def failTag[A, B, S](
        kyo: A < S,
        expected: Tag[B]
    ): Nothing =
        self(s"Unexpected pending effect while handling ${expected.show}: " + kyo)
end extension
