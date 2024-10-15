package kyo

import org.jctools.queues.MpmcUnboundedXaddArrayQueue
import scala.annotation.tailrec

/** A channel for communicating between fibers.
  *
  * @tparam A
  *   The type of elements in the channel
  */
abstract class Channel[A]:
    self =>

    /** Returns the current size of the channel.
      *
      * @return
      *   The number of elements currently in the channel
      */
    def size(using Frame): Int < IO

    /** Attempts to offer an element to the channel without blocking.
      *
      * @param v
      *   The element to offer
      * @return
      *   true if the element was added to the channel, false otherwise
      */
    def offer(v: A)(using Frame): Boolean < IO

    /** Offers an element to the channel without returning a result.
      *
      * @param v
      *   The element to offer
      */
    def offerDiscard(v: A)(using Frame): Unit < IO

    /** Attempts to poll an element from the channel without blocking.
      *
      * @return
      *   Maybe containing the polled element, or empty if the channel is empty
      */
    def poll(using Frame): Maybe[A] < IO

    private[kyo] def unsafePoll(using AllowUnsafe): Maybe[A]

    /** Checks if the channel is empty.
      *
      * @return
      *   true if the channel is empty, false otherwise
      */
    def empty(using Frame): Boolean < IO

    /** Checks if the channel is full.
      *
      * @return
      *   true if the channel is full, false otherwise
      */
    def full(using Frame): Boolean < IO

    /** Creates a fiber that puts an element into the channel.
      *
      * @param v
      *   The element to put
      * @return
      *   A fiber that completes when the element is put into the channel
      */
    def putFiber(v: A)(using Frame): Fiber[Nothing, Unit] < IO

    /** Creates a fiber that takes an element from the channel.
      *
      * @return
      *   A fiber that completes with the taken element
      */
    def takeFiber(using Frame): Fiber[Nothing, A] < IO

    /** Puts an element into the channel, asynchronously blocking if necessary.
      *
      * @param v
      *   The element to put
      */
    def put(v: A)(using Frame): Unit < Async

    /** Takes an element from the channel, asynchronously blocking if necessary.
      *
      * @return
      *   The taken element
      */
    def take(using Frame): A < Async

    /** Checks if the channel is closed.
      *
      * @return
      *   true if the channel is closed, false otherwise
      */
    def closed(using Frame): Boolean < IO

    /** Drains all elements from the channel.
      *
      * @return
      *   A sequence containing all elements that were in the channel
      */
    def drain(using Frame): Seq[A] < IO

    /** Closes the channel.
      *
      * @return
      *   Maybe containing a sequence of remaining elements, or empty if the channel was already closed
      */
    def close(using Frame): Maybe[Seq[A]] < IO
end Channel

object Channel:

    /** Initializes a new Channel.
      *
      * @param capacity
      *   The capacity of the channel
      * @param access
      *   The access mode for the channel (default is MPMC)
      * @param initFrame
      *   The initial frame for the channel
      * @tparam A
      *   The type of elements in the channel
      * @return
      *   A new Channel instance
      */
    def init[A](
        capacity: Int,
        access: Access = Access.MultiProducerMultiConsumer
    )(using initFrame: Frame): Channel[A] < IO =
        Queue.init[A](capacity, access).map { queue =>
            IO {
                new Channel[A]:

                    def u     = queue.unsafe
                    val takes = new MpmcUnboundedXaddArrayQueue[Promise.Unsafe[Nothing, A]](8)
                    val puts  = new MpmcUnboundedXaddArrayQueue[(A, Promise.Unsafe[Nothing, Unit])](8)

                    def size(using Frame)  = op(u.size())
                    def empty(using Frame) = op(u.empty())
                    def full(using Frame)  = op(u.full())

                    def offer(v: A)(using Frame) =
                        IO.Unsafe {
                            !u.closed() && {
                                try u.offer(v)
                                finally flush()
                            }
                        }

                    def offerDiscard(v: A)(using Frame) =
                        IO.Unsafe {
                            if !u.closed() then
                                try discard(u.offer(v))
                                finally flush()
                        }

                    def unsafePoll(using AllowUnsafe): Maybe[A] =
                        if u.closed() then
                            Maybe.empty
                        else
                            try u.poll()
                            finally flush()

                    def poll(using Frame) =
                        IO.Unsafe(unsafePoll)

                    def put(v: A)(using Frame) =
                        IO.Unsafe {
                            try
                                if u.closed() then
                                    throw closedException
                                else if u.offer(v) then
                                    ()
                                else
                                    val p = Promise.Unsafe.init[Nothing, Unit]()
                                    puts.add((v, p))
                                    p.safe.get
                                end if
                            finally
                                flush()
                        }

                    def putFiber(v: A)(using frame: Frame) =
                        IO.Unsafe {
                            try
                                if u.closed() then
                                    throw closedException
                                else if u.offer(v) then
                                    Fiber.unit
                                else
                                    val p = Promise.Unsafe.init[Nothing, Unit]()
                                    puts.add((v, p))
                                    p.safe
                                end if
                            finally
                                flush()
                        }

                    def take(using Frame) =
                        IO.Unsafe {
                            try
                                if u.closed() then
                                    throw closedException
                                else
                                    u.poll() match
                                        case Absent =>
                                            val p = Promise.Unsafe.init[Nothing, A]()
                                            takes.add(p)
                                            p.safe.get
                                        case Present(v) =>
                                            v
                            finally
                                flush()
                        }

                    def takeFiber(using frame: Frame) =
                        IO.Unsafe {
                            try
                                if u.closed() then
                                    throw closedException
                                else
                                    u.poll() match
                                        case Absent =>
                                            val p = Promise.Unsafe.init[Nothing, A]()
                                            takes.add(p)
                                            p.safe
                                        case Present(v) =>
                                            Fiber.success(v)
                            finally
                                flush()
                        }

                    def closedException(using frame: Frame): Closed = Closed("Channel", initFrame, frame)

                    inline def op[A](inline v: AllowUnsafe ?=> A)(using inline frame: Frame): A < IO =
                        IO.Unsafe {
                            if u.closed() then
                                throw closedException
                            else
                                v
                        }

                    def closed(using Frame) = queue.closed

                    def drain(using Frame) = queue.drain

                    def close(using frame: Frame) =
                        IO.Unsafe {
                            u.close() match
                                case Absent => Maybe.empty
                                case r =>
                                    val c = Result.panic(closedException)
                                    def dropTakes(): Unit =
                                        val p = takes.poll()
                                        if !isNull(p) then
                                            p.completeDiscard(c)
                                            dropTakes()
                                    end dropTakes
                                    def dropPuts(): Unit =
                                        puts.poll() match
                                            case null => ()
                                            case (_, p) =>
                                                p.completeDiscard(c)
                                                dropPuts()
                                    dropTakes()
                                    dropPuts()
                                    r
                        }

                    @tailrec private def flush(): Unit =
                        import AllowUnsafe.embrace.danger

                        // This method ensures that all values are processed
                        // and handles interrupted fibers by discarding them.
                        val queueSize  = u.size()
                        val takesEmpty = takes.isEmpty()
                        val putsEmpty  = puts.isEmpty()

                        if queueSize > 0 && !takesEmpty then
                            // Attempt to transfer a value from the queue to
                            // a waiting consumer (take).
                            val p = takes.poll()
                            if !isNull(p) then
                                u.poll() match
                                    case Absent =>
                                        // If the queue has been emptied before the
                                        // transfer, requeue the consumer's promise.
                                        discard(takes.add(p))
                                    case Present(v) =>
                                        if !p.complete(Result.success(v)) && !u.offer(v) then
                                            // If completing the take fails and the queue
                                            // cannot accept the value back, enqueue a
                                            // placeholder put operation to preserve the value.
                                            val placeholder = Promise.Unsafe.init[Nothing, Unit]()
                                            discard(puts.add((v, placeholder)))
                            end if
                            flush()
                        else if queueSize < capacity && !putsEmpty then
                            // Attempt to transfer a value from a waiting
                            // producer (put) to the queue.
                            val t = puts.poll()
                            if t != null then
                                val (v, p) = t
                                if u.offer(v) then
                                    // Complete the put's promise if the value is
                                    // successfully enqueued. If the fiber became
                                    // interrupted, the completion will be ignored.
                                    discard(p.complete(Result.success(())))
                                else
                                    // If the queue becomes full before the transfer,
                                    // requeue the producer's operation.
                                    discard(puts.add(t))
                                end if
                            end if
                            flush()
                        else if queueSize == 0 && !putsEmpty && !takesEmpty then
                            // Directly transfer a value from a producer to a
                            // consumer when the queue is empty.
                            val t = puts.poll()
                            if t != null then
                                val (v, p) = t
                                val p2     = takes.poll()
                                if !isNull(p2) && p2.complete(Result.success(v)) then
                                    // If the transfer is successful, complete
                                    // the put's promise. If the consumer's fiber
                                    // became interrupted, the completion will be
                                    // ignored.
                                    discard(p.complete(Result.success(())))
                                else
                                    // If the transfer to the consumer fails, requeue
                                    // the producer's operation.
                                    discard(puts.add(t))
                                end if
                            end if
                            flush()
                        end if
                    end flush
            }
        }
end Channel
