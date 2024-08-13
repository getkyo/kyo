package sttp.client3

import kyo.*
import kyo.internal.KyoSttpMonad
import sttp.client3.internal.ws.SimpleQueue
import sttp.ws.WebSocketBufferFull

class KyoSimpleQueue[A](ch: Channel[A]) extends SimpleQueue[KyoSttpMonad.M, A]:

    def offer(t: A): Unit =
        if !IO.run(ch.offer(t)).eval then
            throw WebSocketBufferFull(Int.MaxValue)

    def poll =
        ch.take
end KyoSimpleQueue
