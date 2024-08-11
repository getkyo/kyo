package kyo

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.jctools.queues.*
import scala.annotation.tailrec
import scala.util.control.NoStackTrace

class Queue[T] private[kyo] (initFrame: Frame, private[kyo] val unsafe: Queue.Unsafe[T]):

    def capacity(using Frame): Int             = unsafe.capacity
    def size(using Frame): Int < IO            = op(unsafe.size())
    def isEmpty(using Frame): Boolean < IO     = op(unsafe.isEmpty())
    def isFull(using Frame): Boolean < IO      = op(unsafe.isFull())
    def offer(v: T)(using Frame): Boolean < IO = IO(!unsafe.isClosed() && unsafe.offer(v))
    def poll(using Frame): Maybe[T] < IO       = op(Maybe(unsafe.poll()))
    def peek(using Frame): Maybe[T] < IO       = op(Maybe(unsafe.peek()))
    def drain(using Frame): Seq[T] < IO        = op(unsafe.drain())
    def isClosed(using Frame): Boolean < IO    = IO(unsafe.isClosed())
    def close(using Frame): Maybe[Seq[T]] < IO = IO(unsafe.close())

    protected inline def op[T, S](inline v: => T < (IO & S))(using frame: Frame): T < (IO & S) =
        IO {
            if unsafe.isClosed() then
                throw Closed("Queue", initFrame, frame)
            else
                v
        }
end Queue

object Queue:

    abstract private[kyo] class Unsafe[T]
        extends AtomicBoolean(false):

        def capacity: Int
        def size(): Int
        def isEmpty(): Boolean
        def isFull(): Boolean
        def offer(v: T): Boolean
        def poll(): T
        def peek(): T

        def drain(): Seq[T] =
            val b = Seq.newBuilder[T]
            @tailrec def loop(): Unit =
                val v = poll()
                if !isNull(v) then
                    b += v
                    loop()
            end loop
            loop()
            b.result()
        end drain

        def isClosed(): Boolean =
            super.get()

        def close(): Maybe[Seq[T]] =
            super.compareAndSet(false, true) match
                case false =>
                    Maybe.empty
                case true =>
                    Maybe(drain())
    end Unsafe

    class Unbounded[T] private[kyo] (initFrame: Frame, unsafe: Queue.Unsafe[T]) extends Queue[T](initFrame, unsafe):
        def add[S](v: T < S)(using Frame): Unit < (IO & S) =
            op(v.map(offer).unit)

    def init[T](capacity: Int, access: Access = Access.Mpmc)(using frame: Frame): Queue[T] < IO =
        IO {
            capacity match
                case c if (c <= 0) =>
                    new Queue(
                        frame,
                        new Unsafe[T]:
                            def capacity    = 0
                            def size()      = 0
                            def isEmpty()   = true
                            def isFull()    = true
                            def offer(v: T) = false
                            def poll()      = null.asInstanceOf[T]
                            def peek()      = null.asInstanceOf[T]
                    )
                case 1 =>
                    new Queue(
                        frame,
                        new Unsafe[T]:
                            val state       = new AtomicReference[T]
                            def capacity    = 1
                            def size()      = if isNull(state.get()) then 0 else 1
                            def isEmpty()   = isNull(state.get())
                            def isFull()    = !isNull(state.get())
                            def offer(v: T) = state.compareAndSet(null.asInstanceOf[T], v)
                            def poll()      = state.getAndSet(null.asInstanceOf[T])
                            def peek()      = state.get()
                    )
                case Int.MaxValue =>
                    initUnbounded(access)
                case _ =>
                    access match
                        case Access.Mpmc =>
                            fromJava(new MpmcArrayQueue[T](capacity), capacity)
                        case Access.Mpsc =>
                            fromJava(new MpscArrayQueue[T](capacity), capacity)
                        case Access.Spmc =>
                            fromJava(new SpmcArrayQueue[T](capacity), capacity)
                        case Access.Spsc =>
                            if capacity >= 4 then
                                fromJava(new SpscArrayQueue[T](capacity), capacity)
                            else
                                // Spsc queue doesn't support capacity < 4
                                fromJava(new SpmcArrayQueue[T](capacity), capacity)
        }

    def initUnbounded[T](access: Access = Access.Mpmc, chunkSize: Int = 8)(using Frame): Unbounded[T] < IO =
        IO {
            access match
                case Access.Mpmc =>
                    fromJava(new MpmcUnboundedXaddArrayQueue[T](chunkSize))
                case Access.Mpsc =>
                    fromJava(new MpscUnboundedArrayQueue[T](chunkSize))
                case Access.Spmc =>
                    fromJava(new MpmcUnboundedXaddArrayQueue[T](chunkSize))
                case Access.Spsc =>
                    fromJava(new SpscUnboundedArrayQueue[T](chunkSize))
        }

    def initDropping[T](capacity: Int, access: Access = Access.Mpmc)(using frame: Frame): Unbounded[T] < IO =
        init[T](capacity, access).map { q =>
            val u = q.unsafe
            val c = capacity
            new Unbounded(
                frame,
                new Unsafe[T]:
                    def capacity  = c
                    def size()    = u.size()
                    def isEmpty() = u.isEmpty()
                    def isFull()  = false
                    def offer(v: T) =
                        u.offer(v)
                        true
                    def poll() = u.poll()
                    def peek() = u.peek()
            )
        }

    def initSliding[T](capacity: Int, access: Access = Access.Mpmc)(using frame: Frame): Unbounded[T] < IO =
        init[T](capacity, access).map { q =>
            val u = q.unsafe
            val c = capacity
            new Unbounded(
                frame,
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

    private def fromJava[T](q: java.util.Queue[T])(using frame: Frame): Unbounded[T] =
        new Unbounded(
            frame,
            new Unsafe[T]:
                def capacity    = Int.MaxValue
                def size()      = q.size
                def isEmpty()   = q.isEmpty()
                def isFull()    = false
                def offer(v: T) = q.offer(v)
                def poll()      = q.poll
                def peek()      = q.peek
        )

    private def fromJava[T](q: java.util.Queue[T], _capacity: Int)(using frame: Frame): Queue[T] =
        new Queue(
            frame,
            new Unsafe[T]:
                def capacity    = _capacity
                def size()      = q.size
                def isEmpty()   = q.isEmpty()
                def isFull()    = q.size >= _capacity
                def offer(v: T) = q.offer(v)
                def poll()      = q.poll
                def peek()      = q.peek
        )
end Queue
