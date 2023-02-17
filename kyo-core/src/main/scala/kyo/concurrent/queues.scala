package kyo.concurrent

import kyo.core._
import kyo.ios._
import kyo.options._
import org.jctools.queues._

object queues {

  class Queue[T](q: java.util.Queue[T], _capacity: Int) {
    def capacity: Int =
      _capacity
    def size: Int > IOs =
      IOs(q.size())
    def isEmpty: Boolean > IOs =
      IOs(q.isEmpty())
    def isFull: Boolean > IOs =
      IOs(q.size() == _capacity)
    def offer(v: T): Boolean > IOs =
      IOs(q.offer(v))
    def poll: Option[T] > IOs =
      IOs(Option(q.poll))
    def peek: Option[T] > IOs =
      IOs(Option(q.peek))

    private[kyo] def unsafe = q
  }

  class UnboundedQueue[T](q: java.util.Queue[T]) extends Queue(q, Int.MaxValue) {
    def add(v: T): Unit > IOs =
      IOs(q.add(v)).unit
  }

  object Queue {

    def bounded[T](capacity: Int, access: Access = Access.Mpmc): Queue[T] > IOs =
      IOs {
        access match {
          case Access.Mpmc =>
            Queue(MpmcArrayQueue(capacity), capacity)
          case Access.Mpsc =>
            Queue(MpscArrayQueue(capacity), capacity)
          case Access.Spmc =>
            Queue(SpmcArrayQueue(capacity), capacity)
          case Access.Spsc =>
            Queue(SpscArrayQueue(capacity), capacity)
        }
      }

    def unbounded[T](access: Access = Access.Mpmc, chunkSize: Int = 8): UnboundedQueue[T] > IOs =
      IOs {
        access match {
          case Access.Mpmc =>
            UnboundedQueue(MpmcUnboundedXaddArrayQueue(chunkSize))
          case Access.Mpsc =>
            UnboundedQueue(MpscUnboundedArrayQueue(chunkSize))
          case Access.Spmc =>
            UnboundedQueue(MpmcUnboundedXaddArrayQueue(chunkSize))
          case Access.Spsc =>
            UnboundedQueue(SpscUnboundedArrayQueue(chunkSize))
        }
      }
  }
}
