package sttp.client3

import kyo.*
import kyo.internal.KyoSttpMonad
import sttp.client3.internal.ws.SimpleQueue
import sttp.ws.WebSocketBufferFull

class KyoSimpleQueue[A](ch: Channel[A]) extends SimpleQueue[KyoSttpMonad.M, A]:

    def offer(t: A): Unit =
        import kyo.AllowUnsafe.embrace.danger
        if ch.unsafe.offer(t).contains(false) then
            throw WebSocketBufferFull(Int.MaxValue)
    end offer

    def poll =
        import kyo.AllowUnsafe.embrace.danger
        ch.unsafe.takeFiber().mapResult(_.fold(e => throw e.exception)(Result.success)).safe.get
end KyoSimpleQueue
