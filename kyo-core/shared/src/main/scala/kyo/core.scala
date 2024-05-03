package kyo

import kyo.Locals.State
import scala.annotation.tailrec
import scala.util.control.NonFatal

object core:

    import internal.*

    type Id[T]    = T
    type Const[T] = [U] =>> T

    opaque type <[+T, -S] >: T = T | internal.Kyo[T, S]

    trait Effect[+E]:
        type Command[_]

    extension [E <: Effect[E]](e: E)

        inline def suspend[T](inline cmd: e.Command[T])(
            using inline _tag: Tag[E]
        ): T < E =
            new Suspend[e.Command, T, T, E]:
                def command = cmd
                def tag     = _tag.asInstanceOf[Tag[Any]]
                def apply(v: T, s: Safepoint[E], l: State) =
                    v

        inline def suspend[T, U, S](inline cmd: e.Command[T], inline f: T => U < S)(
            using inline _tag: Tag[E]
        ): U < (E & S) =
            new Suspend[e.Command, T, U, S]:
                def command = cmd
                def tag     = _tag.asInstanceOf[Tag[Any]]
                def apply(v: T, s: Safepoint[S], l: State) =
                    f(v)
    end extension

    inline def transform[T, S, U, S2](v: T < S)(inline k: T => (U < S2)): U < (S & S2) =
        def transformLoop(v: T < S): U < (S & S2) =
            v match
                case kyo: Suspend[MX, Any, T, S] @unchecked =>
                    new Continue[MX, Any, U, S & S2](kyo):
                        def apply(v: Any, s: Safepoint[S & S2], l: Locals.State) =
                            val r = kyo(v, s.asInstanceOf[Safepoint[S]], l)
                            if s.preempt() then
                                s.suspend(transformLoop(r))
                            else
                                transformLoop(r)
                            end if
                        end apply
                case v =>
                    k(v.asInstanceOf[T])
        transformLoop(v)
    end transform

    extension [E <: Effect[E]](e: E)
        inline def handle[State, Result[_], T, S, S2](
            handler: ResultHandler[State, e.Command, E, Result, S]
        )(
            state: State,
            value: T < (E & S2)
        )(using inline tag: Tag[E], inline flat: Flat[T < (E & S)]): Result[T] < (S & S2) =
            def _handleLoop(st: State, value: T < (E & S & S2)): Result[T] < (S & S2) =
                handleLoop(st, value)
            @tailrec def handleLoop(st: State, value: T < (E & S & S2)): Result[T] < (S & S2) =
                value match
                    case kyo: Suspend[e.Command, Any, T, S2] @unchecked
                        if tag == kyo.tag && handler.accepts(st, kyo.command) =>
                        handler.resume(st, kyo.command, kyo) match
                            case r: handler.Resume[T, S & S2] @unchecked =>
                                handleLoop(r.st, r.v)
                            case kyo: Kyo[Result[T] | handler.Resume[T, S2], S & S2] @unchecked =>
                                resultLoop(kyo)
                            case r =>
                                r.asInstanceOf[Result[T]]
                        end match
                    case kyo: Suspend[MX, Any, T, E & S & S2] @unchecked =>
                        new Continue[MX, Any, Result[T], S & S2](kyo):
                            def apply(v: Any, s: Safepoint[S & S2], l: Locals.State) =
                                val r =
                                    try kyo(v, s, l)
                                    catch
                                        case ex if NonFatal(ex) =>
                                            handler.failed(st, ex)
                                _handleLoop(st, r)
                            end apply
                    case v =>
                        handler.done(st, v.asInstanceOf[T])
            def resultLoop(v: (Result[T] | handler.Resume[T, S2]) < (S & S2)): Result[T] < (S & S2) =
                v match
                    case r: handler.Resume[T, S & S2] @unchecked =>
                        _handleLoop(r.st, r.v)
                    case kyo: Suspend[MX, Any, Result[T] | handler.Resume[T, S2], S & S2] @unchecked =>
                        new Continue[MX, Any, Result[T], S & S2](kyo):
                            def apply(
                                v: Any,
                                s: Safepoint[S & S2],
                                l: Locals.State
                            ) =
                                resultLoop(kyo(v, s, l))
                    case r =>
                        r.asInstanceOf[Result[T]]
            handleLoop(state, value)
        end handle
    end extension

    inline def eval[T, S, S2](v: => T < S)(
        inline resume: (Safepoint[S & S2], () => T < (S & S2)) => T < (S & S2),
        inline done: (Safepoint[S & S2], T) => T < (S & S2) = (s: Safepoint[S & S2], v: T) => v,
        inline suspend: T < (S & S2) => T < (S & S2) = (v: T < (S & S2)) => v
    ): T < (S & S2) =
        def evalLoop(s: Safepoint[S & S2], v: T < (S & S2)): T < (S & S2) =
            v match
                case kyo: Suspend[MX, Any, T, S & S2] @unchecked =>
                    new Continue[MX, Any, T, S & S2](kyo):
                        def apply(v: Any, s: Safepoint[S & S2], l: Locals.State) =
                            suspend(evalLoop(s, resume(s, () => kyo(v, s, l))))
                case v =>
                    suspend(done(s, v.asInstanceOf[T]))
        val s = Safepoint.noop
        evalLoop(s, suspend(resume(s, () => v)))
    end eval

    abstract class ResultHandler[State, Command[_], E <: Effect[E], Result[_], S]:

        case class Resume[U, S2](st: State, v: U < (E & S & S2))

        def accepts[T](st: State, command: Command[T]): Boolean =
            true

        def done[T](st: State, v: T): Result[T] < S

        def failed(st: State, ex: Throwable): Nothing < (E & S) = throw ex

        def resume[T, U: Flat, S2](
            st: State,
            command: Command[T],
            k: T => U < (E & S2)
        ): (Result[U] | Resume[U, S2]) < (S & S2)

    end ResultHandler

    abstract class Handler[Command[_], E <: Effect[E], S]
        extends ResultHandler[Unit, Command, E, Id, S]:
        inline def done[T](st: Unit, v: T) =
            done(v)
        inline def resume[T, U: Flat, S2](
            st: Unit,
            command: Command[T],
            k: T => U < (E & S2)
        ): (U | Resume[U, S2]) < (S & S2) =
            resume(command, k)

        def done[T](v: T): T < S = v

        def resume[T, U: Flat, S2](
            command: Command[T],
            k: T => U < (E & S2)
        ): (U | Resume[U, S2]) < (S & S2)
    end Handler

    trait Safepoint[-E]:
        def preempt(): Boolean
        def suspend[T, S](v: => T < S): T < (E & S)

    object Safepoint:
        private val _noop = new Safepoint[?]:
            def preempt()                  = false
            def suspend[T, S](v: => T < S) = v
        inline def noop[S]: Safepoint[S] =
            _noop.asInstanceOf[Safepoint[S]]
    end Safepoint

    private[kyo] object internal:

        type MX[T] = Any
        type EX <: Effect[EX]

        abstract class DeepHandler[Command[_], E, S]:
            def done[T: Flat](v: T): Command[T]
            def resume[T, U: Flat](command: Command[T], k: T => Command[U] < S): Command[U] < S

        def deepHandle[Command[_], E <: Effect[E], S, T](
            handler: DeepHandler[Command, E, S],
            v: T < E
        )(using
            tag: Tag[E],
            flat: Flat[T < E]
        ): Command[T] < S =
            def deepHandleLoop(v: T < (E & S)): Command[T] < S =
                v match
                    case kyo: Suspend[Command, Any, T, E] @unchecked =>
                        bug.checkTag(kyo.tag, tag)
                        handler.resume(
                            kyo.command,
                            (v: Any) => deepHandleLoop(kyo(v))
                        )
                    case _ =>
                        handler.done(v.asInstanceOf[T])
            if isNull(v) then
                throw new NullPointerException
            deepHandleLoop(v)
        end deepHandle

        sealed abstract class Kyo[+T, -S]

        abstract class Suspend[Command[_], T, U, S] extends Kyo[U, S]
            with Function1[T, U < S]:
            def command: Command[T]
            def tag: Tag[Any]
            inline def apply(v: T) = apply(v, Safepoint.noop, Locals.State.empty)
            def apply(v: T, s: Safepoint[S], l: Locals.State): U < S
        end Suspend

        abstract class Continue[Command[_], T, U, S](
            s: Suspend[Command, T, ?, ?]
        ) extends Suspend[Command, T, U, S]:
            val command = s.command
            val tag     = s.tag
        end Continue

        implicit inline def fromKyo[T, S](v: Kyo[T, S]): T < S =
            v.asInstanceOf[T < S]
    end internal
end core
