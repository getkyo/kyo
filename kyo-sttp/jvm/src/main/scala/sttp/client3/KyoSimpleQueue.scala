package sttp.client3

import java.util.concurrent.{ArrayBlockingQueue, BlockingQueue, LinkedBlockingQueue}

import sttp.ws.WebSocketBufferFull

import sttp.client3.internal.ws.SimpleQueue
import kyo.internal.KyoSttpMonad
import kyo.ios.IOs
import kyo._

class KyoSimpleQueue[T](ch: Channel[T]) extends SimpleQueue[KyoSttpMonad.M, T] {

  def offer(t: T): Unit =
    if (!IOs.run(ch.offer(t))) {
      throw WebSocketBufferFull(Int.MaxValue)
    }

  def poll =
    ch.take
}
