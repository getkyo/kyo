package sttp.client3

import kyo.*
import kyo.internal.KyoSttpMonad
import sttp.client3.internal.ws.SimpleQueue
import sttp.ws.WebSocketBufferFull

class KyoSimpleQueue[T](ch: Channel[T]) extends SimpleQueue[KyoSttpMonad.M, T]:

    def offer(t: T): Unit =
        if !IO.run(ch.offer(t)).eval then
            throw WebSocketBufferFull(Int.MaxValue)

    def poll =
        ch.take
end KyoSimpleQueue
