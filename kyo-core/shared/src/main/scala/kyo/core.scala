package kyo

import scala.util.control.NonFatal

object core:

    import internal.*

    abstract class Handler[M[_], E <: Effect[M, E], S]:
        def pure[T: Flat](v: T): M[T]
        def handle(ex: Throwable): Nothing < E = throw ex
        def apply[T, U: Flat, S2](m: M[T], f: T => U < (E & S2)): U < (E & S & S2)
    end Handler

    extension [M[_], E <: Effect[M, E]](e: E)

        def suspend[T, S](v: M[T]): T < (S & E) =
            KyoRoot[M, E, T, S & E](v, e)

        inline def handle[T, S, S2](v: T < (E & S))(
            using
            h: Handler[M, E, S2],
            f: Flat[T < (E & S)]
        ): M[T] < (S & S2) =
            def handleLoop(
                v: T < (S & S2 & E)
            ): M[T] < (S & S2) =
                // Flat.unsafe.bypass[T] is used to avoid capturing.
                // It should ideally use erased terms but it's not available
                // in the current production compiler
                v match
                    case kyo: Kyo[M, E, Any, T, S & E] @unchecked if (e.accepts(kyo.effect)) =>
                        if kyo.isRoot then
                            kyo.value.asInstanceOf[M[T] < S]
                        else
                            handleLoop(h[Any, T, S & E](kyo.value, kyo)(
                                using Flat.unsafe.bypass[T]
                            ))
                    case kyo: Kyo[MX, EX, Any, T, S & E] @unchecked =>
                        new KyoCont[MX, EX, Any, M[T], S & S2](kyo):
                            def apply(v: Any < (S & S2), s2: Safepoint[MX, EX], l: Locals.State) =
                                handleLoop {
                                    try kyo(v, s2, l)
                                    catch
                                        case ex if (NonFatal(ex)) =>
                                            h.handle(ex)
                                }
                    case _ =>
                        h.pure(v.asInstanceOf[T])(
                            using Flat.unsafe.bypass[T]
                        )
            handleLoop(v)
        end handle
    end extension

    abstract class Effect[+M[_], +E <: Effect[M, E]]:
        self: E =>
        def accepts[M2[_], E2 <: Effect[M2, E2]](other: Effect[M2, E2]): Boolean = this eq other
    end Effect

    inline def transform[T, S, U, S2](v: T < S)(inline f: T => (U < S2)): U < (S & S2) =
        def transformLoop(v: T < S): U < (S & S2) =
            v match
                case kyo: Kyo[MX, EX, Any, T, S] @unchecked =>
                    new KyoCont[MX, EX, Any, U, S & S2](kyo):
                        def apply(v: Any < (S & S2), s: Safepoint[MX, EX], l: Locals.State) =
                            val n = kyo(v, s, l)
                            if s.check() then
                                s.suspend[U, S & S2](transformLoop(n))
                                    .asInstanceOf[U < (S & S2)]
                            else
                                transformLoop(n)
                            end if
                        end apply
                case _ =>
                    f(v.asInstanceOf[T])
        transformLoop(v)
    end transform

    trait Safepoint[M[_], E <: Effect[M, _]]:
        def check(): Boolean
        def suspend[T, S](v: => T < S): T < (S & E)

    object Safepoint:
        private val _noop = new Safepoint[MX, EX]:
            def check()                    = false
            def suspend[T, S](v: => T < S) = v
        given noop[M[_], E <: Effect[M, E]]: Safepoint[M, E] =
            _noop.asInstanceOf[Safepoint[M, E]]
    end Safepoint

    private[kyo] object internal:

        def deepHandle[M[_], E <: Effect[M, E], T, S](e: E)(v: T < E)(
            using
            h: DeepHandler[M, E, S],
            s: Safepoint[M, E],
            f: Flat[T]
        ): M[T] < S =
            def deepHandleLoop(v: T < E): M[T] < S =
                v match
                    case kyo: Kyo[M, E, Any, T, E] @unchecked =>
                        require(kyo.effect == e, "Unhandled effect: " + kyo.effect)
                        h.apply(
                            kyo.value,
                            (v: Any) => deepHandleLoop(kyo(v, s, Locals.State.empty))
                        )
                    case _ =>
                        h.pure(v.asInstanceOf[T])
            if v == null then
                throw new NullPointerException
            deepHandleLoop(v)
        end deepHandle

        type MX[_] = Any
        type EX    = Effect[MX, ?]

        abstract class DeepHandler[M[_], E <: Effect[M, E], S]:
            def pure[T: Flat](v: T): M[T]
            def apply[T, U: Flat](m: M[T], f: T => M[U] < S): M[U] < S

        abstract class Kyo[M[_], E <: Effect[M, E], T, U, S] extends Function1[T < S, U < S]:
            def value: M[T]
            def effect: E
            def apply(v: T < S): U < S =
                apply(v, Safepoint.noop[M, E], Locals.State.empty)
            def apply(v: T < S, s: Safepoint[M, E], l: Locals.State): U < S
            def isRoot: Boolean = false
        end Kyo

        case class KyoRoot[M[_], E <: Effect[M, E], T, S](v: M[T], e: E)
            extends Kyo[M, E, T, T, S]:
            final def value  = v
            final def effect = e
            final def apply(v: T < S, s: Safepoint[M, E], l: Locals.State) =
                v
            final override def isRoot = true
        end KyoRoot

        abstract class KyoCont[M[_], E <: Effect[M, E], T, U, S](prev: Kyo[M, E, T, ?, ?])
            extends Kyo[M, E, T, U, S]:
            final val value: M[T] = prev.value
            final val effect: E   = prev.effect
        end KyoCont

        implicit inline def fromKyo[M[_], E <: Effect[M, E], T, U, S](
            v: Kyo[M, E, T, U, S]
        ): U < S =
            v.asInstanceOf[U < S]
    end internal
end core
