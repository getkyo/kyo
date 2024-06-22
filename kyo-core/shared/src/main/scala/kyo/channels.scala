package kyo

import kyo.internal.Trace
import org.jctools.queues.MpmcUnboundedXaddArrayQueue
import scala.annotation.tailrec

abstract class Channel[T]:
    self =>

    def size(using Trace): Int < IOs

    def offer(v: T)(using Trace): Boolean < IOs

    def offerUnit(v: T)(using Trace): Unit < IOs

    def poll(using Trace): Option[T] < IOs

    def isEmpty(using Trace): Boolean < IOs

    def isFull(using Trace): Boolean < IOs

    def putFiber(v: T)(using Trace): Fiber[Unit] < IOs

    def takeFiber(using Trace): Fiber[T] < IOs

    def put(v: T)(using Trace): Unit < Fibers =
        putFiber(v).map(_.get)

    def take(using Trace): T < Fibers =
        takeFiber.map(_.get)

    def isClosed(using Trace): Boolean < IOs

    def drain(using Trace): Seq[T] < IOs

    def close(using Trace): Option[Seq[T]] < IOs
end Channel

object Channels:

    private val closed = IOs.fail("Channel closed!")

    def init[T: Flat](
        capacity: Int,
        access: Access = kyo.Access.Mpmc
    ): Channel[T] < IOs =
        Queues.init[T](capacity, access).map { queue =>
            IOs {
                new Channel[T]:

                    def u     = queue.unsafe
                    val takes = new MpmcUnboundedXaddArrayQueue[Promise[T]](8)
                    val puts  = new MpmcUnboundedXaddArrayQueue[(T, Promise[Unit])](8)

                    def size(using Trace)    = op(u.size())
                    def isEmpty(using Trace) = op(u.isEmpty())
                    def isFull(using Trace)  = op(u.isFull())

                    def offer(v: T)(using Trace) =
                        op {
                            try u.offer(v)
                            finally flush()
                        }
                    def offerUnit(v: T)(using Trace) =
                        op {
                            try discard(u.offer(v))
                            finally flush()
                        }
                    def poll(using Trace) =
                        op {
                            try Option(u.poll())
                            finally flush()
                        }

                    def putFiber(v: T)(using Trace) =
                        op {
                            try
                                if u.offer(v) then
                                    Fiber.unit
                                else
                                    val p = Fibers.unsafeInitPromise[Unit]
                                    puts.add((v, p))
                                    p
                            finally
                                flush()
                        }

                    def takeFiber(using Trace) =
                        op {
                            try
                                val v = u.poll()
                                if isNull(v) then
                                    val p = Fibers.unsafeInitPromise[T]
                                    takes.add(p)
                                    p
                                else
                                    Fiber.value(v)
                                end if
                            finally
                                flush()
                        }

                    inline def op[T](inline v: => T): T < IOs =
                        IOs {
                            if u.isClosed() then
                                closed
                            else
                                v
                        }

                    def isClosed(using Trace) = queue.isClosed

                    def drain(using Trace) = queue.drain

                    def close(using Trace) =
                        IOs {
                            u.close() match
                                case None =>
                                    None
                                case r: Some[Seq[T]] =>
                                    def dropTakes(): Unit < IOs =
                                        Loops.foreach {
                                            takes.poll() match
                                                case null => Loops.done
                                                case p    => p.interrupt.map(_ => Loops.continue)
                                        }
                                    def dropPuts(): Unit < IOs =
                                        Loops.foreach {
                                            puts.poll() match
                                                case null   => Loops.done
                                                case (_, p) => p.interrupt.map(_ => Loops.continue)
                                        }
                                    dropTakes()
                                        .andThen(dropPuts())
                                        .andThen(r)
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
                                else if !p.unsafeComplete(Result.success(v)) && !u.offer(v) then
                                    // If completing the take fails and the queue
                                    // cannot accept the value back, enqueue a
                                    // placeholder put operation to preserve the value.
                                    val placeholder = Fibers.unsafeInitPromise[Unit]
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
                                    discard(p.unsafeComplete(Result.success(())))
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
                                if p2 != null && p2.unsafeComplete(Result.success(v)) then
                                    // If the transfer is successful, complete
                                    // the put's promise. If the consumer's fiber
                                    // became interrupted, the completion will be
                                    // ignored.
                                    discard(p.unsafeComplete(Result.success(())))
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
end Channels
