package kyo.compat

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.publicInBinary
import scala.concurrent.Future
import scala.concurrent.Promise

/** Backed by an `AtomicInteger` size counter plus three `ConcurrentLinkedQueue`s (items, takers, putters). `put` and `take` use
  * Promise-queue coordination — no OS-thread blocking. Cancellation isn't part of the CIO surface; a `put`/`take` that can't be satisfied
  * holds onto its Promise indefinitely (use `CIO.timeout` to bound it, accepting that the underlying waiter is orphaned). `poll` is
  * non-blocking and always returns immediately.
  */
final class CChannel[A] @publicInBinary private[compat] (
    capacity: Int,
    items: ConcurrentLinkedQueue[A],
    takers: ConcurrentLinkedQueue[Promise[A]],
    putters: ConcurrentLinkedQueue[(A, Promise[Unit])],
    size: AtomicInteger
):

    inline def lower: CChannel[A] = this

    inline def put(inline v: A): CIO[Unit] = CIO.deferLift(doPut(v))
    inline def take: CIO[A]                = CIO.deferLift(doTake())
    inline def poll: CIO[Option[A]]        = CIO.defer(doPoll())

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

    inline def init[A](inline capacity: Int): CIO[CChannel[A]] =
        CIO.defer(new CChannel[A](
            capacity,
            new ConcurrentLinkedQueue[A](),
            new ConcurrentLinkedQueue[Promise[A]](),
            new ConcurrentLinkedQueue[(A, Promise[Unit])](),
            new AtomicInteger(0)
        ))

    inline def lift[A](inline u: CChannel[A]): CChannel[A] = u

end CChannel
