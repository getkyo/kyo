package kyo.kernel.internal

import kyo.Arrow
import kyo.Chunk
import kyo.kernel.*
import kyo.kernel.internal.Kyo.*

object Eval2:

    def apply[A, S](v: A < S): A < S =
        def loop[A, B](v: A < S, cont: Arrow[A, B, S], handlers: Chunk[Handle[?, ?, ?]]): B < S =
            v match
                case kyo: Kyo.Defer[?, A, S] @unchecked =>
                    loop(kyo.value, kyo.cont.chain(cont), handlers)
                case kyo: Kyo.Suspend[?, ?, ?, ?, A, S] @unchecked =>
                    handlers.find(h => kyo.tag <:< h.tag) match
                        case None                                          => ???
                        case Some(h: HandleCont[?, ?, ?, ?, ?, ?])         =>
                        case Some(h: HandleLoop[?, ?, ?, ?, ?, ?])         =>
                        case Some(h: HandleLoopState[?, ?, ?, ?, ?, ?, ?]) =>
                    end match
                    ???
                case kyo: Kyo.HandleCont[?, ?, ?, ?, ?, ?] =>
                    ???
                case kyo: Kyo.HandleLoop[?, ?, ?, ?, ?, ?] =>
                    ???
                case settled =>
                    ???
        loop(v, Arrow[A], Chunk.empty)
    end apply
end Eval2
