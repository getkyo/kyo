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

    def bounded[T](
        capacity: Int,
        multipleProducers: Boolean = true,
        multipleConsumers: Boolean = true
    ): Queue[T] > IOs =
      IOs {
        if (multipleConsumers && multipleProducers) {
          Queue(MpmcArrayQueue(capacity), capacity)
        } else if (multipleConsumers) {
          Queue(SpmcArrayQueue(capacity), capacity)
        } else if (multipleProducers) {
          Queue(MpscArrayQueue(capacity), capacity)
        } else {
          Queue(SpscArrayQueue(capacity), capacity)
        }
      }

    def unbounded[T](
        multipleProducers: Boolean = true,
        multipleConsumers: Boolean = true,
        chunkSize: Int = 8
    ): UnboundedQueue[T] > IOs =
      IOs {
        if (multipleConsumers && multipleProducers) {
          UnboundedQueue(MpmcUnboundedXaddArrayQueue(chunkSize))
        } else if (multipleConsumers) {
          UnboundedQueue(MpmcUnboundedXaddArrayQueue(chunkSize))
        } else if (multipleProducers) {
          UnboundedQueue(MpscUnboundedArrayQueue(chunkSize))
        } else {
          UnboundedQueue(SpscUnboundedArrayQueue(chunkSize))
        }
      }
  }
}
