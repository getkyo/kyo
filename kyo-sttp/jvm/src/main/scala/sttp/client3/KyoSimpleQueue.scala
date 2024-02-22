package sttp.client3

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import kyo.*
import kyo.internal.KyoSttpMonad
import sttp.client3.internal.ws.SimpleQueue
import sttp.ws.WebSocketBufferFull

class KyoSimpleQueue[T](ch: Channel[T]) extends SimpleQueue[KyoSttpMonad.M, T]:

    def offer(t: T): Unit =
        if !IOs.run(ch.offer(t)) then
            throw WebSocketBufferFull(Int.MaxValue)

    def poll =
        ch.take
end KyoSimpleQueue
