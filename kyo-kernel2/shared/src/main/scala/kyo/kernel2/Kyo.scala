package kyo.kernel2

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec

sealed abstract class Kyo[+A, -S]:
    private[kyo] def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2)
    private[kyo] def prepend(f: Arrow[Any, Any, Any]): A < S

object Kyo:

    // Compiled as a JVM static of class Kyo: hot callers (minted arrow fragments,
    // Arrow.apply, Offset.run) reach it via invokestatic with no module load and,
    // in minted fragments, no captured reference to an enclosing object.
    @static def unwrap(v: Any): Any =
        v match
            case n: Nested[?] => n.value
            case _            => v

    final private[kyo] class Nested[+A](val value: A):
        override def toString = "Nested"

    /** A bare suspension: an effect request with no continuation attached yet.
      *
      * The two suspension kinds share the chain machinery through [[Continue]]: an arrow operation awaiting a handler clause, or a context
      * read awaiting the innermost binding.
      */
    sealed abstract class Suspension[X, -E] extends Kyo[X, E]:

        final private[kyo] def map[B, S](f: Arrow[X, B, S]): B < (E & S) =
            Continue[X, B, E & S](this, f.asInstanceOf[Arrow[X, B, E & S]])

        final private[kyo] def prepend(f: Arrow[Any, Any, Any]): X < E =
            map(f.asInstanceOf[Arrow[X, X, Any]])

    end Suspension

    abstract class Suspend[I[_], O[_], E <: ControlEffect[I, O], A] extends Suspension[O[A], E]:

        def input: I[A]
        def tag: Tag[E]
        def frame: Frame

        final override def toString = "Suspend(" + tag.show + ", " + frame.position.show + ")"

    end Suspend

    abstract class ContextRead[V, E <: ContextEffect[V]] extends Suspension[V, E]:

        def tag: Tag[E]
        def default: Maybe[() => V]
        def frame: Frame

        final override def toString = "ContextRead(" + tag.show + ", " + frame.position.show + ")"

    end ContextRead

    final private[kyo] class Continue[X, +B, -S](
        val suspend: Suspension[X, ?],
        val cont: Arrow[X, B, S]
    ) extends Kyo[B, S]:

        private[kyo] def map[C, S2](f: Arrow[B, C, S2]): C < (S & S2) =
            Continue(suspend, cont.map(f))

        private[kyo] def prepend(f: Arrow[Any, Any, Any]): B < S =
            Continue(suspend, f.map(cont.asInstanceOf[Arrow[Any, B, S]]).asInstanceOf[Arrow[X, B, S]])

        override def toString = "Continue(" + suspend + ")"

    end Continue

    abstract class Bracket[R, A, S] extends Kyo[A, S]:

        def acquire: R < S
        def release(r: R): Unit < S
        def cont: Arrow[R, A, S]
        def frame: Frame

        final private[kyo] def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
            val outer = this
            new Bracket[R, B, S & S2]:
                def acquire       = outer.acquire
                def release(r: R) = outer.release(r)
                def cont          = outer.cont.map(f)
                def frame         = outer.frame
            end new
        end map

        final private[kyo] def prepend(f: Arrow[Any, Any, Any]): A < S =
            val outer = this
            new Bracket[R, A, S]:
                def acquire =
                    outer.acquire match
                        case kyo: Kyo[R, S] @unchecked => kyo.prepend(f)
                        case v                         => v
                def release(r: R) = outer.release(r)
                def cont          = f.map(outer.cont.asInstanceOf[Arrow[Any, A, S]]).asInstanceOf[Arrow[R, A, S]]
                def frame         = outer.frame
            end new
        end prepend

        final override def toString = "Bracket(" + frame.position.show + ")"

    end Bracket

    // public because the inline trampoline's Defer arm expands at user sites
    final class Defer[A, +B, -S](
        val value: A,
        val cont: Arrow[A, B, S]
    ) extends Kyo[B, S]:

        private[kyo] def map[C, S2](f: Arrow[B, C, S2]): C < (S & S2) =
            Defer(value, cont.map(f))

        private[kyo] def prepend(f: Arrow[Any, Any, Any]): B < S =
            Defer(value, f.map(cont.asInstanceOf[Arrow[Any, B, S]]).asInstanceOf[Arrow[A, B, S]])

        override def toString = "Defer"

    end Defer

end Kyo
