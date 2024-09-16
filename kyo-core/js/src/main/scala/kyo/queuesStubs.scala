package org.jctools.queues

import java.util.ArrayDeque

class StubQueue[A](capacity: Int) extends ArrayDeque[A]:
    def isFull = size() >= capacity
    override def offer(e: A): Boolean =
        !isFull && super.offer(e)
end StubQueue

case class MpmcArrayQueue[A](capacity: Int) extends StubQueue[A](capacity)

case class MpscArrayQueue[A](capacity: Int) extends StubQueue[A](capacity)

case class SpmcArrayQueue[A](capacity: Int) extends StubQueue[A](capacity)

case class SpscArrayQueue[A](capacity: Int) extends StubQueue[A](capacity)

case class MpmcUnboundedXaddArrayQueue[A](chunkSize: Int) extends ArrayDeque[A] {}

case class MpscUnboundedArrayQueue[A](chunkSize: Int) extends ArrayDeque[A] {}

case class SpscUnboundedArrayQueue[A](chunkSize: Int) extends ArrayDeque[A] {}
