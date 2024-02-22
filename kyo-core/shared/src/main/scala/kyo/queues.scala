package kyo

import org.jctools.queues.*

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

class Queue[T] private[kyo] (private[kyo] val unsafe: Queues.Unsafe[T]):

    def capacity: Int               = unsafe.capacity
    def size: Int < IOs             = op(unsafe.size())
    def isEmpty: Boolean < IOs      = op(unsafe.isEmpty())
    def isFull: Boolean < IOs       = op(unsafe.isFull())
    def offer(v: T): Boolean < IOs  = op(unsafe.offer(v))
    def poll: Option[T] < IOs       = op(unsafe.poll())
    def peek: Option[T] < IOs       = op(unsafe.peek())
    def drain: Seq[T] < IOs         = op(unsafe.drain())
    def isClosed: Boolean < IOs     = IOs(unsafe.isClosed())
    def close: Option[Seq[T]] < IOs = IOs(unsafe.close())

    private inline def op[T](inline v: => T): T < IOs =
        IOs {
            if unsafe.isClosed() then
                Queues.closed
            else
                v
        }
end Queue

object Queues:

    private[kyo] val closed = IOs.fail("Queue closed!")

    private[kyo] abstract class Unsafe[T]
        extends AtomicBoolean(false):

        def capacity: Int
        def size(): Int
        def isEmpty(): Boolean
        def isFull(): Boolean
        def offer(v: T): Boolean
        def poll(): Option[T]
        def peek(): Option[T]

        def drain(): Seq[T] =
            def loop(acc: List[T]): List[T] =
                poll() match
                    case None =>
                        acc.reverse
                    case Some(v) =>
                        loop(v :: acc)
            loop(Nil)
        end drain

        def isClosed(): Boolean =
            super.get()

        def close(): Option[Seq[T]] =
            super.compareAndSet(false, true) match
                case false =>
                    None
                case true =>
                    Some(drain())
    end Unsafe

    class Unbounded[T] private[kyo] (unsafe: Queues.Unsafe[T]) extends Queue[T](unsafe):

        def add[S](v: T < S): Unit < (IOs & S) = v.map(offer(_)).unit

    def init[T](capacity: Int, access: Access = kyo.Access.Mpmc): Queue[T] < IOs =
        IOs {
            capacity match
                case c if (c <= 0) =>
                    new Queue(
                        new Unsafe[T]:
                            def capacity    = 0
                            def size()      = 0
                            def isEmpty()   = true
                            def isFull()    = true
                            def offer(v: T) = false
                            def poll()      = None
                            def peek()      = None
                    )
                case 1 =>
                    new Queue(
                        new Unsafe[T]:
                            val state       = new AtomicReference[T]
                            def capacity    = 1
                            def size()      = if state.get() == null then 0 else 1
                            def isEmpty()   = state.get() == null
                            def isFull()    = state.get() != null
                            def offer(v: T) = state.compareAndSet(null.asInstanceOf[T], v)
                            def poll()      = Option(state.getAndSet(null.asInstanceOf[T]))
                            def peek()      = Option(state.get())
                    )
                case Int.MaxValue =>
                    initUnbounded(access)
                case _ =>
                    access match
                        case kyo.Access.Mpmc =>
                            fromJava(new MpmcArrayQueue[T](capacity), capacity)
                        case kyo.Access.Mpsc =>
                            fromJava(new MpscArrayQueue[T](capacity), capacity)
                        case kyo.Access.Spmc =>
                            fromJava(new SpmcArrayQueue[T](capacity), capacity)
                        case kyo.Access.Spsc =>
                            if capacity >= 4 then
                                fromJava(new SpscArrayQueue[T](capacity), capacity)
                            else
                                // Spsc queue doesn't support capacity < 4
                                fromJava(new SpmcArrayQueue[T](capacity), capacity)
        }

    def initUnbounded[T](access: Access = kyo.Access.Mpmc, chunkSize: Int = 8): Unbounded[T] < IOs =
        IOs {
            access match
                case kyo.Access.Mpmc =>
                    fromJava(new MpmcUnboundedXaddArrayQueue[T](chunkSize))
                case kyo.Access.Mpsc =>
                    fromJava(new MpscUnboundedArrayQueue[T](chunkSize))
                case kyo.Access.Spmc =>
                    fromJava(new MpmcUnboundedXaddArrayQueue[T](chunkSize))
                case kyo.Access.Spsc =>
                    fromJava(new SpscUnboundedArrayQueue[T](chunkSize))
        }

    def initDropping[T](capacity: Int, access: Access = kyo.Access.Mpmc): Unbounded[T] < IOs =
        init[T](capacity, access).map { q =>
            val u = q.unsafe
            val c = capacity
            new Unbounded(
                new Unsafe[T]:
                    def capacity    = c
                    def size()      = u.size()
                    def isEmpty()   = u.isEmpty()
                    def isFull()    = false
                    def offer(v: T) = u.offer(v)
                    def poll()      = u.poll()
                    def peek()      = u.peek()
            )
        }

    def initSliding[T](capacity: Int, access: Access = kyo.Access.Mpmc): Unbounded[T] < IOs =
        init[T](capacity, access).map { q =>
            val u = q.unsafe
            val c = capacity
            new Unbounded(
                new Unsafe[T]:
                    def capacity  = c
                    def size()    = u.size()
                    def isEmpty() = u.isEmpty()
                    def isFull()  = false
                    def offer(v: T) =
                        @tailrec def loop(v: T): Unit =
                            val u = q.unsafe
                            if u.offer(v) then ()
                            else
                                u.poll()
                                loop(v)
                            end if
                        end loop
                        loop(v)
                        true
                    end offer
                    def poll() = u.poll()
                    def peek() = u.peek()
            )
        }

    private def fromJava[T](q: java.util.Queue[T]): Unbounded[T] =
        new Unbounded(
            new Unsafe[T]:
                def capacity    = Int.MaxValue
                def size()      = q.size
                def isEmpty()   = q.isEmpty()
                def isFull()    = false
                def offer(v: T) = q.offer(v)
                def poll()      = Option(q.poll)
                def peek()      = Option(q.peek)
        )

    private def fromJava[T](q: java.util.Queue[T], _capacity: Int): Queue[T] =
        new Queue(
            new Unsafe[T]:
                def capacity    = _capacity
                def size()      = q.size
                def isEmpty()   = q.isEmpty()
                def isFull()    = q.size >= _capacity
                def offer(v: T) = q.offer(v)
                def poll()      = Option(q.poll)
                def peek()      = Option(q.peek)
        )
end Queues
