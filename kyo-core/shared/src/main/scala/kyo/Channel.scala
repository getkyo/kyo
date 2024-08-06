package kyo

import kyo.scheduler.IOPromise
import org.jctools.queues.MpmcUnboundedXaddArrayQueue
import scala.annotation.tailrec
import scala.util.control.NoStackTrace

abstract class Channel[T]:
    self =>

    def size(using Frame): Int < (Abort[Closed] & IO)

    def offer(v: T)(using Frame): Boolean < IO

    def offerUnit(v: T)(using Frame): Unit < IO

    def poll(using Frame): Maybe[T] < IO

    private[kyo] def unsafePoll: Maybe[T]

    def isEmpty(using Frame): Boolean < (Abort[Closed] & IO)

    def isFull(using Frame): Boolean < (Abort[Closed] & IO)

    def putFiber(v: T)(using Frame): Fiber[Closed, Unit] < IO

    def takeFiber(using Frame): Fiber[Closed, T] < IO

    def put(v: T)(using Frame): Unit < (Abort[Closed] & Async)

    def take(using Frame): T < (Abort[Closed] & Async)

    def isClosed(using Frame): Boolean < IO

    def drain(using Frame): Seq[T] < (Abort[Closed] & IO)

    def close(using Frame): Maybe[Seq[T]] < IO
end Channel

object Channel:

    def init[T](
        capacity: Int,
        access: Access = Access.Mpmc
    )(using initFrame: Frame): Channel[T] < IO =
        Queue.init[T](capacity, access).map { queue =>
            IO {
                new Channel[T]:

                    def u     = queue.unsafe
                    val takes = new MpmcUnboundedXaddArrayQueue[IOPromise[Closed, T]](8)
                    val puts  = new MpmcUnboundedXaddArrayQueue[(T, IOPromise[Closed, Unit])](8)

                    def size(using Frame)    = op(u.size())
                    def isEmpty(using Frame) = op(u.isEmpty())
                    def isFull(using Frame)  = op(u.isFull())

                    def offer(v: T)(using Frame) =
                        IO {
                            !u.isClosed() && {
                                try u.offer(v)
                                finally flush()
                            }
                        }

                    def offerUnit(v: T)(using Frame) =
                        IO {
                            if !u.isClosed() then
                                try discard(u.offer(v))
                                finally flush()
                        }

                    def unsafePoll: Maybe[T] =
                        if u.isClosed() then
                            Maybe.empty
                        else
                            try Maybe(u.poll())
                            finally flush()

                    def poll(using Frame) =
                        IO(unsafePoll)

                    def put(v: T)(using Frame) =
                        IO {
                            try
                                if u.isClosed() then
                                    Abort.fail(closed)
                                else if u.offer(v) then
                                    ()
                                else
                                    val p = IOPromise[Closed, Unit]
                                    puts.add((v, p))
                                    Async.get(p)
                                end if
                            finally
                                flush()
                        }

                    def putFiber(v: T)(using frame: Frame) =
                        IO {
                            try
                                if u.isClosed() then
                                    Fiber.fail(closed)
                                else if u.offer(v) then
                                    Fiber.unit
                                else
                                    val p = IOPromise[Closed, Unit]
                                    puts.add((v, p))
                                    Fiber.initUnsafe(p)
                                end if
                            finally
                                flush()
                        }

                    def take(using Frame) =
                        IO {
                            try
                                if u.isClosed() then
                                    Abort.fail(closed)
                                else
                                    val v = u.poll()
                                    if isNull(v) then
                                        val p = IOPromise[Closed, T]
                                        takes.add(p)
                                        Async.get(p)
                                    else
                                        v
                                    end if
                            finally
                                flush()
                        }

                    def takeFiber(using frame: Frame) =
                        IO {
                            try
                                if u.isClosed() then
                                    Fiber.fail(closed)
                                else
                                    val v = u.poll()
                                    if isNull(v) then
                                        val p = IOPromise[Closed, T]
                                        takes.add(p)
                                        Fiber.initUnsafe(p)
                                    else
                                        Fiber.success(v)
                                    end if
                            finally
                                flush()
                        }

                    def closed(using frame: Frame): Closed = Closed("Channel", initFrame, frame)

                    inline def op[T](inline v: => T)(using inline frame: Frame): T < (Abort[Closed] & IO) =
                        IO {
                            if u.isClosed() then
                                Abort.fail(closed)
                            else
                                v
                        }

                    def isClosed(using Frame) = queue.isClosed

                    def drain(using Frame) = queue.drain

                    def close(using frame: Frame) =
                        IO {
                            u.close() match
                                case Maybe.Empty => Maybe.empty
                                case r =>
                                    val c = Result.fail(closed)
                                    def dropTakes(): Unit =
                                        takes.poll() match
                                            case null =>
                                            case p =>
                                                p.complete(c)
                                                dropTakes()
                                    def dropPuts(): Unit =
                                        puts.poll() match
                                            case null => ()
                                            case (_, p) =>
                                                p.complete(c)
                                                dropPuts()
                                    dropTakes()
                                    dropPuts()
                                    r
                        }

                    @tailrec private def flush(): Unit =
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
                                val v = u.poll()
                                if isNull(v) then
                                    // If the queue has been emptied before the
                                    // transfer, requeue the consumer's promise.
                                    discard(takes.add(p))
                                else if !p.complete(Result.success(v)) && !u.offer(v) then
                                    // If completing the take fails and the queue
                                    // cannot accept the value back, enqueue a
                                    // placeholder put operation to preserve the value.
                                    val placeholder = IOPromise[Closed, Unit]
                                    discard(puts.add((v, placeholder)))
                                end if
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
                                if p2 != null && p2.complete(Result.success(v)) then
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
