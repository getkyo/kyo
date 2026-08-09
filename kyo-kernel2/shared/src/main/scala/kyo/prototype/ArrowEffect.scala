package kyo.prototype

import kyo.Frame
import kyo.Tag

abstract class ArrowEffect[I[_], O[_]]

object ArrowEffect:

    def suspend[I[_], O[_], E <: ArrowEffect[I, O], X](tag: Tag[E], input: I[X])(using frame: Frame): O[X] < E =
        new Kyo.Suspend(tag, input, frame, Arrow[O[X]])

    def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        tag: Tag[E],
        v: A < (E & S)
    )(
        f: [X] => (I[X], O[X] => A < (E & S)) => A < (E & S)
    )(using frame: Frame): A < S =
        @scala.annotation.tailrec
        def loop(v: A < (E & S)): A < S =
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] if tag.erased <:< kyo.tag.erased =>
                    val anchored = kyo.asInstanceOf[Kyo.Suspend[I, O, E, Any, A, E & S]]
                    loop(f[Any](anchored.input, o => anchored.cont(`<`.lift(o), Context.empty, Handlers.empty)))
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val defer = kyo.asInstanceOf[Kyo.Defer[Any, A, E & S]]
                    loop(defer.cont(defer.value, Context.empty, Handlers.empty))
                case v =>
                    v.asInstanceOf[A < S]
        val sp    = Safepoint.get
        val saved = sp.save()
        try loop(v)
        finally sp.restore(saved)
    end handle

end ArrowEffect
