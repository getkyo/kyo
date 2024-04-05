package kyo

import scala.annotation.tailrec
import scala.util.control.NonFatal

object core:

    import internal.*

    opaque type <[+T, -S] >: T = T | internal.Kyo[T, S]

    abstract class Effect[+E]:
        type Command[_]

    inline def suspend[E <: Effect[E], T](e: E)(v: e.Command[T])(
        using inline _tag: Tag[E]
    ): T < E =
        new Root[e.Command, T, E]:
            def command = v
            def tag     = _tag.asInstanceOf[Tag[Any]]

    inline def transform[T, S, U, S2](v: T < S)(inline k: T => (U < S2)): U < (S & S2) =
        def transformLoop(v: T < S): U < (S & S2) =
            v match
                case kyo: Suspend[MX, Any, T, S] @unchecked =>
                    new Continue[MX, Any, U, S & S2](kyo):
                        def apply(v: Any, s: Safepoint[S & S2], l: Locals.State) =
                            val r = kyo(v, s.asInstanceOf[Safepoint[S]], l)
                            if s.check() then
                                s.suspend(transformLoop(r))
                            else
                                transformLoop(r)
                            end if
                        end apply
                case v: T @unchecked =>
                    k(v)
        transformLoop(v)
    end transform

    inline def handle[Command[_], Result[_], E <: Effect[E], T, S, S2](
        handler: ResultHandler[Command, Result, E, S],
        value: T < (E & S2)
    )(using tag: Tag[E], flat: Flat[T < (E & S)]): Result[T] < (S & S2) =
        @tailrec def handleLoop(
            handler: ResultHandler[Command, Result, E, S],
            value: T < (E & S & S2)
        ): Result[T] < (S & S2) =
            value match
                case suspend: Suspend[Command, Any, T, S2] @unchecked
                    if suspend.tag == tag =>
                    handler.resume(suspend.command, suspend) match
                        case r: Recurse[Command, Result, E, T, S, S2] @unchecked =>
                            handleLoop(r.h, r.v)
                        case v =>
                            v.asInstanceOf[Result[T] < (S & S2)]
                case suspend: Suspend[MX, Any, T, S] @unchecked =>
                    new Continue[MX, Any, Result[T], S & S2](suspend):
                        def apply(v: Any, s: Safepoint[S & S2], l: Locals.State) =
                            val k =
                                try suspend(v, s.asInstanceOf[Safepoint[S]], l)
                                catch
                                    case ex if NonFatal(ex) =>
                                        handler.failed(ex)
                            handleLoop(handler, k)
                        end apply
                case v: T @unchecked =>
                    handler.done(v)
        handleLoop(handler, value)
    end handle

    case class Recurse[Command[_], Result[_], E <: Effect[E], T, S, S2](
        h: ResultHandler[Command, Result, E, S],
        v: T < (E & S & S2)
    )

    abstract class ResultHandler[Command[_], Result[_], E <: Effect[E], S]:

        opaque type Handle[T, S2] >: (Result[T] < (S & S2)) =
            Result[T] < (S & S2) | Recurse[Command, Result, E, T, S, S2]

        protected inline def handle[T, S2](v: T < (E & S & S2)): Handle[T, S2] =
            handle(this, v)
        protected inline def handle[T, S2](
            h: ResultHandler[Command, Result, E, S],
            v: T < (E & S & S2)
        ): Handle[T, S2] =
            Recurse(h, v)

        def done[T](v: T): Result[T] < S

        def failed(ex: Throwable): Nothing < E = throw ex

        def resume[T, U: Flat, S](
            command: Command[T],
            k: T => U < (E & S)
        ): Handle[U, S]

    end ResultHandler

    abstract class Handler[Command[_], E <: Effect[E], S]
        extends ResultHandler[Command, Id, E, S]:
        def done[T](v: T): Id[T] < S = v
    end Handler

    trait Safepoint[-E]:
        def check(): Boolean
        def suspend[T, S](v: => T < S): T < (E & S)

    object Safepoint:
        private val _noop = new Safepoint[?]:
            def check()                    = false
            def suspend[T, S](v: => T < S) = v
        inline def noop[S]: Safepoint[S] =
            _noop.asInstanceOf[Safepoint[S]]
    end Safepoint

    type Id[T]    = T
    type Const[T] = [U] =>> T

    private[kyo] object internal:

        type MX[T] = Any
        type EX <: Effect[EX]

        abstract class DeepHandler[Command[_], E, S]:
            def pure[T: Flat](v: T): Command[T]
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
                        bug(kyo.tag != tag, "Unhandled effect: " + kyo.tag.parse)
                        handler.resume(
                            kyo.command,
                            (v: Any) => deepHandleLoop(kyo(v))
                        )
                    case _ =>
                        handler.pure(v.asInstanceOf[T])
            if v == null then
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

        sealed abstract class Root[Command[_], T, E] extends Suspend[Command, T, T, E]:
            def command: Command[T]
            def tag: Tag[Any]
            def apply(v: T, s: Safepoint[E], l: Locals.State) = v
        end Root

        implicit inline def fromKyo[T, S](v: Kyo[T, S]): T < S =
            v.asInstanceOf[T < S]
    end internal
end core
