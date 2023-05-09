package kyo.concurrent

import kyo.core._
import kyo.ios._
import kyo.options._
import org.jctools.queues._

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

object queues {

  opaque type Queue[T] = Queues.Unsafe[T]

  extension [T](u: Queue[T]) {
    def capacity: Int =
      u.capacity
    def size: Int > IOs =
      IOs(u.size)
    def isEmpty: Boolean > IOs =
      IOs(u.isEmpty)
    def isFull: Boolean > IOs =
      IOs(u.isFull)
    def offer[S](v: T > S): Boolean > (S | IOs) =
      v.map(v => IOs(u.offer(v)))
    def poll: Option[T] > IOs =
      IOs(u.poll())
    def peek: Option[T] > IOs =
      IOs(u.peek())

    private[kyo] def unsafe: Queues.Unsafe[T] =
      u
  }

  object Queues {

    private[kyo] trait Unsafe[T] {
      def capacity: Int
      def size: Int
      def isEmpty: Boolean
      def isFull: Boolean
      def offer(v: T): Boolean
      def poll(): Option[T]
      def peek(): Option[T]
    }

    opaque type Unbounded[T] <: Queue[T] = Unsafe[T]

    extension [T](u: Unbounded[T]) {
      def add[S](v: T > S): Unit > (S | IOs) =
        v.map(u.offer).unit
    }

    private val zeroCapacity =
      new Unsafe[Any] {
        def capacity: Int          = 0
        def size: Int              = 0
        def isEmpty: Boolean       = true
        def isFull: Boolean        = true
        def offer(v: Any): Boolean = false
        def poll(): Option[Any]    = None
        def peek(): Option[Any]    = None
      }

    def bounded[T](capacity: Int, access: Access = Access.Mpmc): Queue[T] > IOs =
      IOs {
        capacity match {
          case 0 =>
            zeroCapacity.asInstanceOf[Queue[T]]
          case 1 =>
            new AtomicReference[T] with Unsafe[T] {
              def capacity = 1
              def size     = if (get == null) 0 else 1
              def isEmpty  = get == null
              def isFull   = get != null
              def offer(v: T) =
                compareAndSet(null.asInstanceOf[T], v)
              def poll() =
                Option(getAndSet(null.asInstanceOf[T]))
              def peek() =
                Option(get)
            }
          case _ =>
            access match {
              case Access.Mpmc =>
                bounded(MpmcArrayQueue(capacity), capacity)
              case Access.Mpsc =>
                bounded(MpscArrayQueue(capacity), capacity)
              case Access.Spmc =>
                bounded(SpmcArrayQueue(capacity), capacity)
              case Access.Spsc =>
                bounded(SpscArrayQueue(capacity), capacity)
            }
        }
      }

    def unbounded[T](access: Access = Access.Mpmc, chunkSize: Int = 8): Unbounded[T] > IOs =
      IOs {
        access match {
          case Access.Mpmc =>
            unbounded(MpmcUnboundedXaddArrayQueue(chunkSize))
          case Access.Mpsc =>
            unbounded(MpscUnboundedArrayQueue(chunkSize))
          case Access.Spmc =>
            unbounded(MpmcUnboundedXaddArrayQueue(chunkSize))
          case Access.Spsc =>
            unbounded(SpscUnboundedArrayQueue(chunkSize))
        }
      }

    private def unbounded[T](q: java.util.Queue[T]): Unbounded[T] =
      new Unsafe[T] {
        def capacity: Int        = Int.MaxValue
        def size: Int            = q.size
        def isEmpty              = q.isEmpty()
        def isFull               = false
        def offer(v: T): Boolean = q.offer(v)
        def poll(): Option[T]    = Option(q.poll)
        def peek(): Option[T]    = Option(q.peek)
      }

    private def bounded[T](q: java.util.Queue[T], _capacity: Int): Queue[T] =
      new Unsafe[T] {
        def capacity: Int        = _capacity
        def size: Int            = q.size
        def isEmpty              = q.isEmpty()
        def isFull               = q.size >= _capacity
        def offer(v: T): Boolean = q.offer(v)
        def poll(): Option[T]    = Option(q.poll)
        def peek(): Option[T]    = Option(q.peek)
      }
  }
}
