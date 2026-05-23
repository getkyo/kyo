package kyo.compat

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.Promise

/** Custom `final class` backing the Future binding's bounded FIFO channel — Scala's standard library has no async queue, so this binding
  * supplies its own. The Future ecosystem has no `Frame` / `Trace` to propagate. `lift` and `lower` are identity on this `final class`.
  * Holds an `AtomicInteger` size counter plus three `ConcurrentLinkedQueue`s (items, takers, putters); `put` and `take` use Promise-queue
  * coordination — no OS-thread blocking. The compat surface exposes only `put`/`take`/`poll`: no `close`/`closed`/`size`/`offer`.
  * Cancellation isn't part of the CIO surface; a `put`/`take` that can't be satisfied holds onto its Promise indefinitely (use
  * `CIO.timeout` to bound it, accepting that the underlying waiter is orphaned). `poll` is non-blocking and always returns immediately.
  */
final class CChannel[A](
    capacity: Int,
    items: ConcurrentLinkedQueue[A],
    takers: ConcurrentLinkedQueue[Promise[A]],
    putters: ConcurrentLinkedQueue[(A, Promise[Unit])],
    size: AtomicInteger
):

    /** Returns this channel. Identity on the carrier. */
    inline def lower: CChannel[A] = this

    /** Enqueues `v`; suspends when the channel is full. */
    inline def put(inline v: A): CIO[Unit] = CIO.deferLift(doPut(v))

    /** Dequeues the next element; suspends when the channel is empty. */
    inline def take: CIO[A] = CIO.deferLift(doTake())

    /** Non-blocking dequeue: `Some(a)` if an element is available, `None` otherwise. */
    inline def poll: CIO[Option[A]] = CIO.defer(doPoll())

    private def doPut(v: A): Future[Unit] =
        if size.get() < capacity then
            if size.getAndIncrement() < capacity then
                items.offer(v)
                drainTakers()
                Future.unit
            else
                val _ = size.decrementAndGet()
                doPutSlow(v)
            end if
        else doPutSlow(v)
    end doPut

    private def doPutSlow(v: A): Future[Unit] =
        val p = Promise[Unit]()
        putters.offer((v, p))
        if size.get() < capacity then drainPutters()
        p.future
    end doPutSlow

    private def doTake(): Future[A] =
        val item = items.poll()
        if item.asInstanceOf[AnyRef] ne null then
            val _ = size.decrementAndGet()
            drainPutters()
            Future.successful(item)
        else doTakeSlow()
        end if
    end doTake

    private def doTakeSlow(): Future[A] =
        val p = Promise[A]()
        takers.offer(p)
        val retry = items.poll()
        if retry.asInstanceOf[AnyRef] ne null then
            val _ = size.decrementAndGet()
            drainPutters()
            if !p.trySuccess(retry) then
                items.offer(retry)
                val _ = size.incrementAndGet()
                drainTakers()
            end if
        end if
        p.future
    end doTakeSlow

    private def doPoll(): Option[A] =
        val item = items.poll()
        if item.asInstanceOf[AnyRef] ne null then
            val _ = size.decrementAndGet()
            drainPutters()
            Some(item)
        else None
        end if
    end doPoll

    private def drainTakers(): Unit =
        var continue = true
        while continue do
            val taker = takers.poll()
            if taker eq null then continue = false
            else
                val item = items.poll()
                if item.asInstanceOf[AnyRef] ne null then
                    val _ = size.decrementAndGet()
                    if !taker.trySuccess(item) then
                        items.offer(item)
                        val _ = size.incrementAndGet()
                    end if
                else
                    takers.offer(taker)
                    continue = false
                end if
            end if
        end while
    end drainTakers

    private def drainPutters(): Unit =
        var continue = true
        while continue do
            val entry = putters.peek()
            if entry eq null then continue = false
            else if size.get() < capacity then
                val taken = putters.poll()
                if taken ne null then
                    val (v, p) = taken
                    if p.trySuccess(()) then
                        items.offer(v)
                        val _ = size.incrementAndGet()
                        drainTakers()
                    end if
                end if
            else continue = false
            end if
        end while
    end drainPutters

end CChannel

object CChannel:

    /** Allocates a bounded FIFO channel of the given capacity. */
    inline def init[A](inline capacity: Int): CIO[CChannel[A]] =
        CIO.defer(new CChannel[A](
            capacity,
            new ConcurrentLinkedQueue[A](),
            new ConcurrentLinkedQueue[Promise[A]](),
            new ConcurrentLinkedQueue[(A, Promise[Unit])](),
            new AtomicInteger(0)
        ))

    /** Wraps a native `CChannel` as a `CChannel`. Identity on the carrier. */
    inline def lift[A](inline u: CChannel[A]): CChannel[A] = u

end CChannel
