package kyo.concurrent

import kyo._
import kyo.ios._
import kyo.options._
import org.jctools.queues._

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

object queues {

  class Queue[T] private[queues] (private[kyo] val unsafe: Queues.Unsafe[T]) {
    val capacity: Int                              = unsafe.capacity
    val size: Int > IOs                            = IOs(unsafe.size)
    val isEmpty: Boolean > IOs                     = IOs(unsafe.isEmpty())
    val isFull: Boolean > IOs                      = IOs(unsafe.isFull())
    def offer[S](v: T > S): Boolean > (IOs with S) = v.map(v => IOs(unsafe.offer(v)))
    val poll: Option[T] > IOs                      = IOs(unsafe.poll())
    val peek: Option[T] > IOs                      = IOs(unsafe.peek())
  }

  object Queues {

    private[kyo] trait Unsafe[T] {
      def capacity: Int
      def size: Int
      def isEmpty(): Boolean
      def isFull(): Boolean
      def offer(v: T): Boolean
      def poll(): Option[T]
      def peek(): Option[T]
    }

    class Unbounded[T] private[queues] (unsafe: Queues.Unsafe[T]) extends Queue[T](unsafe) {

      def add[S](v: T > S): Unit > (IOs with S) = v.map(offer(_)).unit
    }

    private val zeroCapacity =
      new Unsafe[Any] {
        def capacity      = 0
        def size          = 0
        def isEmpty()     = true
        def isFull()      = true
        def offer(v: Any) = false
        def poll()        = None
        def peek()        = None
      }

    def initBounded[T](capacity: Int, access: Access = Access.Mpmc): Queue[T] > IOs =
      IOs {
        capacity match {
          case c if (c <= 0) =>
            zeroCapacity.asInstanceOf[Queue[T]]
          case 1 =>
            new Queue(
                new AtomicReference[T] with Unsafe[T] {
                  def capacity    = 1
                  def size        = if (get == null) 0 else 1
                  def isEmpty()   = get == null
                  def isFull()    = get != null
                  def offer(v: T) = compareAndSet(null.asInstanceOf[T], v)
                  def poll()      = Option(getAndSet(null.asInstanceOf[T]))
                  def peek()      = Option(get)
                }
            )
          case _ =>
            access match {
              case Access.Mpmc =>
                fromJava(new MpmcArrayQueue[T](capacity), capacity)
              case Access.Mpsc =>
                fromJava(new MpscArrayQueue[T](capacity), capacity)
              case Access.Spmc =>
                fromJava(new SpmcArrayQueue[T](capacity), capacity)
              case Access.Spsc =>
                fromJava(new SpscArrayQueue[T](capacity), capacity)
            }
        }
      }

    def initUnbounded[T](access: Access = Access.Mpmc, chunkSize: Int = 8): Unbounded[T] > IOs =
      IOs {
        access match {
          case Access.Mpmc =>
            fromJava(new MpmcUnboundedXaddArrayQueue[T](chunkSize))
          case Access.Mpsc =>
            fromJava(new MpscUnboundedArrayQueue[T](chunkSize))
          case Access.Spmc =>
            fromJava(new MpmcUnboundedXaddArrayQueue[T](chunkSize))
          case Access.Spsc =>
            fromJava(new SpscUnboundedArrayQueue[T](chunkSize))
        }
      }

    def initDropping[T](capacity: Int, access: Access = Access.Mpmc): Unbounded[T] > IOs =
      initBounded[T](capacity, access).map { q =>
        val u = q.unsafe
        val c = capacity
        new Unbounded(
            new Unsafe[T] {
              def capacity    = c
              def size        = u.size
              def isEmpty()   = u.isEmpty()
              def isFull()    = false
              def offer(v: T) = u.offer(v)
              def poll()      = u.poll()
              def peek()      = u.peek()
            }
        )
      }

    def initSliding[T](capacity: Int, access: Access = Access.Mpmc): Unbounded[T] > IOs =
      initBounded[T](capacity, access).map { q =>
        val u = q.unsafe
        val c = capacity
        new Unbounded(
            new Unsafe[T] {
              def capacity  = c
              def size      = u.size
              def isEmpty() = u.isEmpty()
              def isFull()  = false
              def offer(v: T) = {
                @tailrec def loop(v: T): Unit = {
                  val u = q.unsafe
                  if (u.offer(v)) ()
                  else {
                    u.poll()
                    loop(v)
                  }
                }
                loop(v)
                true
              }
              def poll() = u.poll()
              def peek() = u.peek()
            }
        )
      }

    private def fromJava[T](q: java.util.Queue[T]): Unbounded[T] =
      new Unbounded(
          new Unsafe[T] {
            def capacity    = Int.MaxValue
            def size        = q.size
            def isEmpty()   = q.isEmpty()
            def isFull()    = false
            def offer(v: T) = q.offer(v)
            def poll()      = Option(q.poll)
            def peek()      = Option(q.peek)
          }
      )

    private def fromJava[T](q: java.util.Queue[T], _capacity: Int): Queue[T] =
      new Queue(
          new Unsafe[T] {
            def capacity    = _capacity
            def size        = q.size
            def isEmpty()   = q.isEmpty()
            def isFull()    = q.size >= _capacity
            def offer(v: T) = q.offer(v)
            def poll()      = Option(q.poll)
            def peek()      = Option(q.peek)
          }
      )
  }
}
