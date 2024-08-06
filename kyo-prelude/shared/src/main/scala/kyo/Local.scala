package kyo

import core.*
import core.internal.*
import kyo.internal.Trace
import kyo.iosInternal.*

abstract class Local[T]:

    import Locals.*

    def default: T

    val get: T < IOs =
        fromKyo {
            new KyoIO[T, Any]:
                def trace = Trace.derive
                def apply(v: Unit, s: Safepoint[IOs], l: State) =
                    get(l)
        }

    def let[U, S](f: T)(v: U < S)(using _trace: Trace): U < (S & IOs) =
        def letLoop(f: T, v: U < S): U < S =
            v match
                case kyo: Suspend[MX, Any, U, S] @unchecked =>
                    fromKyo {
                        new Continue[MX, Any, U, S](kyo):
                            def trace = _trace
                            def apply(v2: Any < S, s: Safepoint[S], l: Locals.State) =
                                letLoop(f, kyo(v2, s, l.updated(Local.this, f)))
                    }
                case _ =>
                    v
        letLoop(f, v)
    end let

    inline def use[U, S](inline f: T => U < S)(using inline _trace: Trace): U < (S & IOs) =
        fromKyo {
            new KyoIO[U, S]:
                def trace = _trace
                def apply(v: Unit, s: Safepoint[S & IOs], l: State) =
                    f(get(l))
        }

    def update[U, S](f: T => T)(v: U < S)(using _trace: Trace): U < (S & IOs) =
        def updateLoop(f: T => T, v: U < S): U < S =
            v match
                case kyo: Suspend[MX, Any, U, S] @unchecked =>
                    fromKyo {
                        new Continue[MX, Any, U, S](kyo):
                            def trace = _trace
                            def apply(v2: Any < S, s: Safepoint[S], l: Locals.State) =
                                updateLoop(f, kyo(v2, s, l.updated(Local.this, f(get(l)))))
                    }
                case _ =>
                    v
        updateLoop(f, v)
    end update

    private def get(l: Locals.State) =
        l.getOrElse(Local.this, default).asInstanceOf[T]
end Local

object Locals:

    type State = Map[Local[?], Any]

    object State:
        inline def empty: State = Map.empty

    def init[T](defaultValue: => T): Local[T] =
        new Local[T]:
            def default = defaultValue

    val save: State < IOs =
        fromKyo {
            new KyoIO[State, Any]:
                def trace = Trace.derive
                def apply(v: Unit, s: Safepoint[IOs], l: Locals.State) =
                    l
        }

    inline def save[U, S](inline f: State => U < S)(using inline _trace: Trace): U < (IOs & S) =
        fromKyo {
            new KyoIO[U, S]:
                def trace = _trace
                def apply(v: Unit, s: Safepoint[IOs & S], l: Locals.State) =
                    f(l)
        }

    def restore[T, S](st: State)(f: T < S)(using _trace: Trace): T < (IOs & S) =
        def loop(f: T < S): T < S =
            f match
                case kyo: Suspend[MX, Any, T, S] @unchecked =>
                    fromKyo {
                        new Continue[MX, Any, T, S](kyo):
                            def trace = _trace
                            def apply(v2: Any < S, s: Safepoint[S], l: Locals.State) =
                                loop(kyo(v2, s, l ++ st))
                    }
                case _ =>
                    f
        loop(f)
    end restore
end Locals
