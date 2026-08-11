package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.Tag
import kyo.kernel.*
import scala.annotation.static

sealed trait Boxed

final case class Nested[+A](value: A) extends Boxed

object Nested:

    @static private[kernel] def lift[A, S](v: A): A < S =
        v match
            case boxed: Boxed => Nested(boxed).asInstanceOf[A < S]
            case v            => v.asInstanceOf[A < S]

    @static private[kernel] def unnest[A](v: Any): A =
        v match
            case n: Nested[?] => n.value.asInstanceOf[A]
            case _            => v.asInstanceOf[A]
end Nested

sealed trait Kyo[+A, -S] extends Boxed:
    def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2)

object Kyo:

    private[kernel] inline def unnest[A, S](inline v: A < S): A =
        inline scala.compiletime.erasedValue[A] match
            case _: (Int | Long | Float | Double | Boolean | Byte | Short | Char | Unit | String) =>
                v.asInstanceOf[A]
            case _ =>
                Nested.unnest(v)

    // a suspension carrying a fallback: evaluation resolves it through a
    // matching handler like any suspension, and the miss path resumes with
    // the default instead of failing, which is how optional context works
    // TODO no, this is not acceptable, we need to fully review. Let's discuss
    private[kyo] trait Defaulted:
        self: Suspend[?, ?, ?, ?, ?, ?] =>
        def default: Any

    trait Suspend[I[_], O[_], E <: ArrowEffect[I, O], X, +A, -S] extends Kyo[A, S]:
        self =>

        def tag: Tag[E]
        def input: I[X]
        def frame: Frame
        def cont: Arrow[O[X], A, S]

        private[kernel] def root: Suspend[I, O, E, X, ?, ?] = this

        final override def toString =
            s"Kyo(${tag.show}, Input($input), ${frame.position.show}, ${frame.snippetShort})"

        final def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
            val r = root
            val c = cont.chain(f)
            new Suspend[I, O, E, X, B, S & S2]:
                override val root = r
                def tag           = root.tag
                def input         = root.input
                def frame         = root.frame
                val cont          = c
            end new
        end map
    end Suspend

    final class Defer[A, +B, -S](val value: A < S, val cont: Arrow[A, B, S]) extends Kyo[B, S]:
        def map[C, S2](f: Arrow[B, C, S2]) =
            new Defer(value, cont.chain(f))

        override def toString = s"Kyo(Defer($value))"
    end Defer

    // the region node of a stateless handler; the handler field's union
    // makes carrying a stateful handler in a stateless region a type error.
    // The rows are split because the handler kinds are invariant in their
    // row (their clauses use it in result position): S is the handler's row
    // and S2 the extra row the exit continuation contributes
    final class Handled[I[_], O[_], E <: ArrowEffect[I, O], A, +B, S, -S2](
        val value: A < (E & S),
        val handler: Handler.Cont[I, O, E, A, S] | Handler.Loop[I, O, E, A, S],
        val cont: Arrow[A, B, S & S2]
    ) extends Kyo[B, S & S2]:
        def map[C, S3](f: Arrow[B, C, S3]) =
            new Handled[I, O, E, A, C, S, S2 & S3](value, handler, cont.chain(f))

        override def toString = s"Kyo(Handled(${handler.tag.show}, $value))"
    end Handled

    // the region node of a stateful handler. state is the value this entry
    // of the region starts from: the initial state at construction, and the
    // state the region had when a residual or a captured continuation
    // rebuilt the node, so re-entering resumes rather than resetting
    final class HandledState[I[_], O[_], E <: ArrowEffect[I, O], A, +B, S, -S2, State](
        val value: A < (E & S),
        val handler: Handler.LoopState[I, O, E, A, S, State],
        val cont: Arrow[A, B, S & S2],
        val state: State
    ) extends Kyo[B, S & S2]:
        def map[C, S3](f: Arrow[B, C, S3]) =
            new HandledState[I, O, E, A, C, S, S2 & S3, State](value, handler, cont.chain(f), state)

        override def toString = s"Kyo(HandledState(${handler.tag.show}, $state, $value))"
    end HandledState

end Kyo
