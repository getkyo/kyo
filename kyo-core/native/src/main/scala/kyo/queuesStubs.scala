package org.jctools.queues

import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import scala.annotation.tailrec

class StubQueue[A](capacity: Int) extends LinkedBlockingQueue[A](capacity):
    def isFull = size() >= capacity
    def drain(f: A => Unit): Unit =
        given [B]: CanEqual[B, B] = CanEqual.derived
        @tailrec def loop(): Unit =
            super.poll() match
                case null =>
                case value =>
                    f(value)
                    loop()
        end loop
        loop()
    end drain
    override def offer(e: A): Boolean =
        !isFull && super.offer(e)
end StubQueue

case class MpmcArrayQueue[A](capacity: Int) extends StubQueue[A](capacity)

case class MpscArrayQueue[A](capacity: Int) extends StubQueue[A](capacity)

case class SpmcArrayQueue[A](capacity: Int) extends StubQueue[A](capacity)

case class SpscArrayQueue[A](capacity: Int) extends StubQueue[A](capacity)

case class MpmcUnboundedXaddArrayQueue[A](chunkSize: Int) extends StubQueue[A](Int.MaxValue)

case class MpscUnboundedArrayQueue[A](chunkSize: Int) extends StubQueue[A](Int.MaxValue)

case class SpscUnboundedArrayQueue[A](chunkSize: Int) extends StubQueue[A](Int.MaxValue)
