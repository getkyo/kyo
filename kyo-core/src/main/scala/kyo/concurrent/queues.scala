package kyo.concurrent

import kyo.core._
import kyo.ios._
import kyo.options._
import org.jctools.queues._

object queues {

  opaque type Queue[T]                      = java.util.Queue[T]
  opaque type UnboundedQueue[T] <: Queue[T] = java.util.Queue[T]

  object Queue {

    def bounded[T](
        size: Int,
        multipleProducers: Boolean = true,
        multipleConsumers: Boolean = true
    ): Queue[T] > IOs =
      IOs {
        if (multipleConsumers && multipleProducers) {
          MpmcArrayQueue(size)
        } else if (multipleConsumers) {
          SpmcArrayQueue(size)
        } else if (multipleProducers) {
          MpscArrayQueue(size)
        } else {
          SpscArrayQueue(size)
        }
      }

    def unbounded[T](
        multipleProducers: Boolean = true,
        multipleConsumers: Boolean = true,
        chunkSize: Int = 8
    ): UnboundedQueue[T] > IOs =
      IOs {
        if (multipleConsumers && multipleProducers) {
          MpmcUnboundedXaddArrayQueue(chunkSize)
        } else if (multipleConsumers) {
          MpmcUnboundedXaddArrayQueue(chunkSize)
        } else if (multipleProducers) {
          MpscUnboundedArrayQueue(chunkSize)
        } else {
          SpscUnboundedArrayQueue(chunkSize)
        }
      }
  }

  extension [T](q: Queue[T]) {
    def isEmpty: Boolean > IOs =
      IOs(q.isEmpty())
    def offer(v: T): Boolean > IOs =
      IOs(q.offer(v))
    def poll: Option[T] > IOs =
      IOs(Option(q.poll))
    def peek: Option[T] > IOs =
      IOs(Option(q.peek))

  }

  extension [T](q: UnboundedQueue[T]) {
    def add(v: T): Unit > IOs =
      IOs(q.add(v)).unit
  }
}
