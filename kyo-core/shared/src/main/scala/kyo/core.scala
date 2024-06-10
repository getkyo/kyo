package kyo

import kyo.Locals.State
import kyo.internal.Trace
import scala.annotation.tailrec
import scala.language.implicitConversions
import scala.util.control.NonFatal

object core:

    type Id[T]    = T
    type Const[T] = [U] =>> T

    import internal.*

    abstract class Effect[-I[_], +O[_]]

    final case class <[+T, -S](private val state: T | Pending[T, S]) extends AnyVal

    object `<`:
        implicit def lift[T](v: T): T < Any =
            <(v)

    case class SuspendDsl[T](ign: Unit) extends AnyVal:
        inline def apply[I[_], O[_], E <: Effect[I, O], U, S](
            inline _tag: Tag[E],
            inline _input: I[T],
            inline cont: O[T] => U < S = (v: O[T]) => v
        )(using _trace: Trace): U < (E & S) =
            <(new Suspend[I, O, E, T, U, S]:
                def tag   = _tag
                def input = _input
                def trace = _trace
                def apply(v: O[T], s: Safepoint, l: Locals.State): U < S =
                    if s.preempt() then
                        s.suspend(cont(v))
                    else
                        cont(v)
            )
    end SuspendDsl

    inline def suspend[T]: SuspendDsl[T] = SuspendDsl(())

    inline def bind[T, U, S, S2](v: T < S)(inline f: T => U < S2)(using inline _trace: Trace): U < (S & S2) =
        def bindLoop(curr: T < S): U < (S & S2) =
            curr match
                case <(kyo: Suspend[IX, OX, EX, Any, T, S] @unchecked) =>
                    <(new Continue[IX, OX, EX, Any, U, S & S2](kyo):
                        def trace = _trace
                        def apply(v: OX[Any], s: Safepoint, l: Locals.State) =
                            val r = kyo(v, s, l)
                            if s.preempt() then
                                s.suspend(bindLoop(r))
                            else
                                bindLoop(r)
                            end if
                        end apply
                    )
                case <(v) =>
                    f(v.asInstanceOf[T])
        bindLoop(v)
    end bind

    abstract class Continuation[T, U, S]:
        def apply(v: T): U < S

    object handle:
        inline def apply[I[_], O[_], E <: Effect[I, O], T, S, S2](
            inline tag: Tag[E],
            inline v: T < (E & S)
        )(
            inline handle: [C] => (I[C], Continuation[O[C], T, E & S & S2]) => T < (E & S & S2),
            inline accept: [C] => I[C] => Boolean = [C] => (_: I[C]) => true
        )(using inline _trace: Trace): T < (S & S2) =
            def handleLoop(v: T < (E & S & S2)): T < (S & S2) =
                v match
                    case <(kyo: Suspend[I, O, E, Any, T, E & S & S2] @unchecked) if tag =:= kyo.tag && accept(kyo.input) =>
                        handleLoop(handle(kyo.input, kyo))
                    case <(kyo: Suspend[IX, OX, EX, Any, T, E & S & S2] @unchecked) =>
                        <(new Continue[IX, OX, EX, Any, T, S & S2](kyo):
                            def trace = _trace
                            def apply(v: OX[Any], s: Safepoint, l: Locals.State) =
                                val r = kyo(v, s, l)
                                if s.preempt() then
                                    s.suspend(handleLoop(r))
                                else
                                    handleLoop(r)
                                end if
                            end apply
                        )
                    case <(v) =>
                        v.asInstanceOf[T]
            handleLoop(v)
        end apply

        inline def state[I[_], O[_], E <: Effect[I, O], State, T, U, S, S2](
            inline tag: Tag[E],
            inline st: State,
            inline v: T < (E & S)
        )(
            inline handle: [C] => (I[C], State, Continuation[O[C], T, E & S & S2]) => (State, T < (E & S & S2)) < (S & S2),
            inline done: (State, T) => U < (S & S2) = (_: State, v: T) => v
        )(using inline _trace: Trace): U < (S & S2) =
            def handleStateLoop(st: State, v: T < (E & S & S2)): U < (S & S2) =
                v match
                    case <(kyo: Suspend[I, O, E, Any, T, E & S & S2] @unchecked) if tag =:= kyo.tag =>
                        bind(handle(kyo.input, st, kyo))(handleStateLoop)
                    case <(kyo: Suspend[IX, OX, EX, Any, T, S] @unchecked) =>
                        <(new Continue[IX, OX, EX, Any, U, S & S2](kyo):
                            def trace = _trace
                            def apply(v: OX[Any], s: Safepoint, l: Locals.State) =
                                val r = kyo(v, s, l)
                                if s.preempt() then
                                    s.suspend(handleStateLoop(st, r))
                                else
                                    handleStateLoop(st, r)
                                end if
                            end apply
                        )
                    case <(v) =>
                        done(st, v.asInstanceOf[T])
            handleStateLoop(st, v)
        end state
    end handle

    inline def eval[T, S, S2](v: => T < S)(
        inline resume: (Safepoint, () => T < (S & S2)) => T < (S & S2),
        inline done: (Safepoint, T) => T < (S & S2) = (s: Safepoint, v: T) => v,
        inline suspend: T < (S & S2) => T < (S & S2) = (v: T < (S & S2)) => v
    )(using inline _trace: Trace): T < (S & S2) =
        def evalLoop(s: Safepoint, v: T < (S & S2)): T < (S & S2) =
            v match
                case <(kyo: Suspend[IX, OX, EX, Any, T, S & S2] @unchecked) =>
                    <(new Continue[IX, OX, EX, Any, T, S & S2](kyo):
                        def trace = _trace
                        def apply(v: OX[Any], s: Safepoint, l: Locals.State) =
                            suspend(evalLoop(s, resume(s, () => kyo(v, s, l))))
                    )
                case <(v) =>
                    suspend(done(s, v.asInstanceOf[T]))
        val s = Safepoint.noop
        evalLoop(s, suspend(resume(s, () => v)))
    end eval

    inline def catching[T, S, U >: T, S2](v: => T < S)(
        inline pf: PartialFunction[Throwable, U < S2]
    )(using inline _trace: Trace): U < (S & S2) =
        def catchingLoop(v: U < (S & S2)): U < (S & S2) =
            v match
                case <(kyo: Suspend[IX, OX, EX, Any, U, S & S2] @unchecked) =>
                    <(new Continue[IX, OX, EX, Any, U, S & S2](kyo):
                        def trace = _trace
                        def apply(v: OX[Any], s: Safepoint, l: Locals.State) =
                            try
                                catchingLoop(kyo(v, s, l))
                            catch
                                case ex: Throwable if (NonFatal(ex) && pf.isDefinedAt(ex)) =>
                                    pf(ex)
                    )
                case <(v) =>
                    v.asInstanceOf[T]
        try
            catchingLoop(v)
        catch
            case ex: Throwable if (NonFatal(ex) && pf.isDefinedAt(ex)) =>
                pf(ex)
        end try
    end catching

    private[kyo] object internal:
        type IX[_]
        type OX[_]
        type EX <: Effect[IX, OX]

        trait Safepoint:
            def preempt(): Boolean
            def suspend[T, S](v: => T < S): T < S

        object Safepoint:
            val noop = new Safepoint:
                def preempt()                  = false
                def suspend[T, S](v: => T < S) = v
        end Safepoint

        sealed trait Pending[+T, -S]

        abstract class Suspend[I[_], O[_], E <: Effect[I, O], T, U, S]
            extends Continuation[O[T], U, S] with Pending[U, S]:

            def tag: Tag[E]
            def input: I[T]
            def trace: Trace

            def apply(v: O[T]): U < S = apply(v, Safepoint.noop, Locals.State.empty)
            def apply(v: O[T], s: Safepoint, l: Locals.State): U < S

            override def toString() =
                s"Kyo(${tag.show}, Input($input), ${trace.position}, ${trace.snippet})"
        end Suspend

        abstract class Continue[I[_], O[_], E <: Effect[I, O], T, U, S](
            prev: Suspend[I, O, E, T, ?, ?]
        ) extends Suspend[I, O, E, T, U, S]:
            val tag   = prev.tag
            val input = prev.input
        end Continue

    end internal

end core
