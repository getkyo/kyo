package kyo

import java.util.concurrent.atomic.AtomicReference
import java.util as j
import kyo.debug.Debug
import org.jctools.queues.*
import scala.annotation.tailrec
import scala.util.control.NonFatal

opaque type Queue[A] = Queue.Unsafe[A]

object Queue:

    extension [A](self: Queue[A])
        def capacity: Int                                            = self.capacity
        def size(using Frame): Int < (IO & Abort[Closed])            = IO.Unsafe(Abort.get(self.size()))
        def empty(using Frame): Boolean < (IO & Abort[Closed])       = IO.Unsafe(Abort.get(self.empty()))
        def full(using Frame): Boolean < (IO & Abort[Closed])        = IO.Unsafe(Abort.get(self.full()))
        def offer(v: A)(using Frame): Boolean < (IO & Abort[Closed]) = IO.Unsafe(Abort.get(self.offer(v)))
        def poll(using Frame): Maybe[A] < (IO & Abort[Closed])       = IO.Unsafe(Abort.get(self.poll()))
        def peek(using Frame): Maybe[A] < (IO & Abort[Closed])       = IO.Unsafe(Abort.get(self.peek()))
        def drain(using Frame): Seq[A] < (IO & Abort[Closed])        = IO.Unsafe(Abort.get(self.drain()))
        def close(using Frame): Seq[A] < (IO & Abort[Closed])        = IO.Unsafe(Abort.get(self.close()))
        def closed(using Frame): Boolean < IO                        = IO.Unsafe(self.closed())
        def unsafe: Unsafe[A]                                        = self
    end extension

    def init[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Queue[A] < IO =
        IO.Unsafe(Unsafe.init(capacity, access))

    opaque type Unbounded[A] <: Queue[A] = Queue[A]

    object Unbounded:
        extension [A](self: Unbounded[A])
            def add(value: A)(using Frame): Unit < IO = IO.Unsafe(Unsafe.add(self)(value))
            def unsafe: Unsafe[A]                     = self

        def init[A](access: Access = Access.MultiProducerMultiConsumer, chunkSize: Int = 8)(using Frame): Unbounded[A] < IO =
            IO.Unsafe(Unsafe.init(access, chunkSize))

        def initDropping[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Unbounded[A] < IO =
            IO.Unsafe(Unsafe.initDropping(capacity, access))

        def initSliding[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Unbounded[A] < IO =
            IO.Unsafe(Unsafe.initSliding(capacity, access))

        opaque type Unsafe[A] <: Queue.Unsafe[A] = Queue[A]

        object Unsafe:
            extension [A](self: Unsafe[A])
                def add(value: A)(using AllowUnsafe, Frame): Unit = discard(self.offer(value))
                def safe: Unbounded[A]                            = self

            def init[A](access: Access = Access.MultiProducerMultiConsumer, chunkSize: Int = 8)(
                using
                Frame,
                AllowUnsafe
            ): Unsafe[A] =
                access match
                    case Access.MultiProducerMultiConsumer =>
                        Queue.Unsafe.fromJava(new MpmcUnboundedXaddArrayQueue[A](chunkSize))
                    case Access.MultiProducerSingleConsumer =>
                        Queue.Unsafe.fromJava(new MpscUnboundedArrayQueue[A](chunkSize))
                    case Access.SingleProducerMultiConsumer =>
                        Queue.Unsafe.fromJava(new MpmcUnboundedXaddArrayQueue[A](chunkSize))
                    case Access.SingleProducerSingleConsumer =>
                        Queue.Unsafe.fromJava(new SpscUnboundedArrayQueue[A](chunkSize))

            def initDropping[A](_capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unsafe[A] =
                new Unsafe[A]:
                    val underlying                           = Queue.Unsafe.init[A](_capacity, access)
                    def capacity                             = _capacity
                    def size()(using AllowUnsafe)            = underlying.size()
                    def empty()(using AllowUnsafe)           = underlying.empty()
                    def full()(using AllowUnsafe)            = underlying.full().map(_ => false)
                    def offer(v: A)(using AllowUnsafe)       = underlying.offer(v).map(_ => true)
                    def poll()(using AllowUnsafe)            = underlying.poll()
                    def peek()(using AllowUnsafe)            = underlying.peek()
                    def drain()(using AllowUnsafe)           = underlying.drain()
                    def close()(using Frame, AllowUnsafe)    = underlying.close()
                    def closed()(using AllowUnsafe): Boolean = underlying.closed()
                end new
            end initDropping

            def initSliding[A](_capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unsafe[A] =
                new Unsafe[A]:
                    val underlying                 = Queue.Unsafe.init[A](_capacity, access)
                    def capacity                   = _capacity
                    def size()(using AllowUnsafe)  = underlying.size()
                    def empty()(using AllowUnsafe) = underlying.empty()
                    def full()(using AllowUnsafe)  = underlying.full().map(_ => false)
                    def offer(v: A)(using AllowUnsafe) =
                        @tailrec def loop(v: A): Result[Closed, Boolean] =
                            underlying.offer(v) match
                                case Result.Success(false) =>
                                    discard(underlying.poll())
                                    loop(v)
                                case result =>
                                    result
                        end loop
                        loop(v)
                    end offer
                    def poll()(using AllowUnsafe)            = underlying.poll()
                    def peek()(using AllowUnsafe)            = underlying.peek()
                    def drain()(using AllowUnsafe)           = underlying.drain()
                    def close()(using Frame, AllowUnsafe)    = underlying.close()
                    def closed()(using AllowUnsafe): Boolean = underlying.closed()
                end new
            end initSliding
        end Unsafe
    end Unbounded

    abstract class Unsafe[A]:
        def capacity: Int
        def size()(using AllowUnsafe): Result[Closed, Int]
        def empty()(using AllowUnsafe): Result[Closed, Boolean]
        def full()(using AllowUnsafe): Result[Closed, Boolean]
        def offer(v: A)(using AllowUnsafe): Result[Closed, Boolean]
        def poll()(using AllowUnsafe): Result[Closed, Maybe[A]]
        def peek()(using AllowUnsafe): Result[Closed, Maybe[A]]
        def drain()(using AllowUnsafe): Result[Closed, Seq[A]]
        def close()(using Frame, AllowUnsafe): Result[Closed, Seq[A]]
        def closed()(using AllowUnsafe): Boolean
        final def safe: Queue[A] = this
    end Unsafe

    object Unsafe:

        abstract class Closeable[A](initFrame: Frame) extends Unsafe[A]:
            import AllowUnsafe.embrace.danger
            final protected val _closed = AtomicRef.Unsafe.init(Maybe.empty[Result.Error[Closed]])

            final def close()(using frame: Frame, allow: AllowUnsafe) =
                val failClosed = Result.Fail(Closed("Queue", initFrame, frame))
                val ok         = _closed.cas(Maybe.empty, Maybe(failClosed))
                if ok then
                    Result(_drain())
                else
                    failClosed
                end if
            end close

            final def closed()(using AllowUnsafe) = _closed.get().isDefined

            final def drain()(using AllowUnsafe): Result[Closed, Seq[A]] = op(_drain())

            protected def _drain(): Seq[A]

            protected inline def op[A](inline f: => A): Result[Closed, A] =
                _closed.get().getOrElse(Result(f))

            protected inline def offerOp[A](inline f: => Boolean, inline raceRepair: => Boolean): Result[Closed, Boolean] =
                _closed.get().getOrElse {
                    val result = f
                    if result && _closed.get().isDefined then
                        Result(raceRepair)
                    else
                        Result(result)
                    end if
                }
        end Closeable

        def init[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using
            initFrame: Frame,
            allow: AllowUnsafe
        ): Unsafe[A] =
            capacity match
                case _ if capacity <= 0 =>
                    new Closeable[A](initFrame):
                        def capacity                       = 0
                        def size()(using AllowUnsafe)      = op(0)
                        def empty()(using AllowUnsafe)     = op(true)
                        def full()(using AllowUnsafe)      = op(true)
                        def offer(v: A)(using AllowUnsafe) = op(false)
                        def poll()(using AllowUnsafe)      = op(Maybe.empty)
                        def peek()(using AllowUnsafe)      = op(Maybe.empty)
                        def _drain()                       = Seq.empty
                case 1 =>
                    new Closeable[A](initFrame):
                        private val state                  = AtomicRef.Unsafe.init(Maybe.empty[A])
                        def capacity                       = 1
                        def empty()(using AllowUnsafe)     = op(state.get().isEmpty)
                        def size()(using AllowUnsafe)      = op(if state.get().isEmpty then 0 else 1)
                        def full()(using AllowUnsafe)      = op(state.get().isDefined)
                        def offer(v: A)(using AllowUnsafe) = offerOp(state.cas(Maybe.empty, Maybe(v)), !state.cas(Maybe(v), Maybe.empty))
                        def poll()(using AllowUnsafe)      = op(state.getAndSet(Maybe.empty))
                        def peek()(using AllowUnsafe)      = op(state.get())
                        def _drain()                       = state.getAndSet(Maybe.empty).toList
                case Int.MaxValue =>
                    Unbounded.Unsafe.init(access).safe
                case _ =>
                    access match
                        case Access.MultiProducerMultiConsumer =>
                            fromJava(new MpmcArrayQueue[A](capacity), capacity)
                        case Access.MultiProducerSingleConsumer =>
                            fromJava(new MpscArrayQueue[A](capacity), capacity)
                        case Access.SingleProducerMultiConsumer =>
                            fromJava(new SpmcArrayQueue[A](capacity), capacity)
                        case Access.SingleProducerSingleConsumer =>
                            if capacity >= 4 then
                                fromJava(new SpscArrayQueue[A](capacity), capacity)
                            else
                                // Spsc queue doesn't support capacity < 4
                                fromJava(new SpmcArrayQueue[A](capacity), capacity)

        def fromJava[A](q: java.util.Queue[A], _capacity: Int = Int.MaxValue)(using initFrame: Frame, allow: AllowUnsafe): Unsafe[A] =
            new Closeable[A](initFrame):
                def capacity                   = _capacity
                def size()(using AllowUnsafe)  = op(q.size())
                def empty()(using AllowUnsafe) = op(q.isEmpty())
                def full()(using AllowUnsafe)  = op(q.size() >= _capacity)
                def offer(v: A)(using AllowUnsafe) =
                    offerOp(
                        q.offer(v),
                        try !q.remove(v)
                        catch
                            case _: UnsupportedOperationException =>
                                // TODO the race repair should use '!q.remove(v)' but JCTools doesn't support the operation.
                                // In rare cases, items may be left in the queue permanently after closing due to this limitation.
                                // The item will only be removed when the queue object itself is garbage collected.
                                !q.contains(v)
                    )
                def poll()(using AllowUnsafe) = op(Maybe(q.poll()))
                def peek()(using AllowUnsafe) = op(Maybe(q.peek()))
                def _drain() =
                    val b = Seq.newBuilder[A]
                    @tailrec def loop(): Unit =
                        val value = q.poll()
                        if !isNull(value) then
                            b.addOne(value)
                            loop()
                    end loop
                    loop()
                    b.result()
                end _drain

    end Unsafe

end Queue
