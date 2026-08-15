package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.Tag
import kyo.kernel.*
import kyo.kernel.Loop.Outcome
import kyo.kernel.Loop.Outcome2
import scala.annotation.static
import scala.annotation.tailrec

sealed trait Boxed

final case class Nested[+A](value: A) extends Boxed

object Nested:

    @static def lift[A, S](v: A): A < S =
        v match
            case boxed: Boxed => Nested(boxed).asInstanceOf[A < S]
            case v            => v.asInstanceOf[A < S]

    @static def unnest[A](v: Any): A =
        v match
            case n: Nested[?] => n.value.asInstanceOf[A]
            case _            => v.asInstanceOf[A]
end Nested

sealed trait Kyo[+A, -S] extends Boxed:
    self =>

    def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
        if f.isIdentity then
            this.asInstanceOf[B < (S & S2)]
        else
            Kyo.defer(this, f)
end Kyo

object Kyo:

    inline def unnest[A, S](inline v: A < S): A =
        inline scala.compiletime.erasedValue[A] match
            case _: (Int | Long | Float | Double | Boolean | Byte | Short | Char | Unit | String) =>
                v.asInstanceOf[A]
            case _ =>
                Nested.unnest(v)

    def defer[A, B, S](v: A < S, f: Arrow[A, B, S]): Kyo[B, S] =
        new Defer[A, B, S]:
            val value = v
            val cont  = f

    abstract class Defer[A, +B, -S] extends Kyo[B, S]:
        self =>

        def value: A < S
        def cont: Arrow[A, B, S]

        // renders the pending operation's origin, not the latest transformation:
        // the walk is iterative because a deferred chain can be arbitrarily deep
        final override def toString: String =
            @tailrec def origin(v: Any): String =
                v match
                    case d: Defer[?, ?, ?] => origin(d.value)
                    case kyo: Kyo[?, ?]    => kyo.toString
                    case v                 => s"Kyo(Defer($v))"
            origin(this.value)
        end toString
    end Defer

    trait Suspend[I[_], O[_], E <: ArrowEffect[I, O], X, +A, -S] extends Kyo[A, S]:
        self =>

        def tag: Tag[E]
        def input: I[X]
        def frame: Frame

        final override def toString: String =
            s"Kyo(${tag.show}, Input($input), ${frame.position.show}, ${frame.snippetShort})"
    end Suspend

    sealed abstract class Handle[E, A, S] extends Kyo[A, S]:
        def tag: Tag[E]

    abstract class HandleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handle[E, B, S]:
        self =>

        def tag: Tag[E]
        def value: A < (E & S)

        def run[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)
        def complete(v: A): B < S

        /** A deep region stays installed while its clause result runs, so a re-raise dispatches back to it and the region itself
          * discharges the effect from the result's row.
          */
        def deep: Boolean = false

        final override def toString: String = s"Kyo(HandleCont(${tag.show}, $value))"
    end HandleCont

    abstract class HandleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handle[E, B, S]:
        self =>

        def tag: Tag[E]
        def value: A < (E & S)

        def run[X](input: I[X]): Outcome[O[X] < (E & S), B] < S
        def complete(v: A): B < S

        final override def toString: String = s"Kyo(HandleLoop(${tag.show}, $value))"
    end HandleLoop

    abstract class HandleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State] extends Handle[E, B, S]:

        def tag: Tag[E]
        def value: A < (E & S)

        def initialState: State
        def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B] < S
        def complete(state: State, v: A): B < S

        final override def toString: String = s"Kyo(HandleLoopState(${tag.show}, $value))"

    end HandleLoopState

end Kyo
