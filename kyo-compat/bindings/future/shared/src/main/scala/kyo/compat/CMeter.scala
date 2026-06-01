package kyo.compat

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success

/** Custom `final class` backing the Future binding's counting semaphore — Scala's standard library has no async semaphore, so this binding
  * supplies its own. The Future ecosystem has no `Frame` / `Trace` to propagate. `lift` and `lower` are identity on this `final class`.
  * Holds an `AtomicInteger` permit counter and a `ConcurrentLinkedQueue` of waiting `Promise[Unit]`s. `run` acquires a permit
  * asynchronously and releases on natural completion (success or failure). Cancellation isn't part of the CIO surface; if the body's Future
  * is abandoned (e.g. via `CIO.timeout`) the permit is leaked until release is triggered by some other completion. `availablePermits` and
  * `tryRun` are non-blocking.
  */
final class CMeter(
    avail: AtomicInteger,
    waiters: ConcurrentLinkedQueue[Promise[Unit]]
):

    /** Returns this meter. Identity on the carrier. */
    inline def lower: CMeter = this

    /** Acquires one permit, runs `c`, and releases on completion (success or failure). */
    inline def run[A](inline c: CIO[A]): CIO[A] =
        CIO.deferLift {
            acquirePermit().flatMap { _ =>
                c.lower.transform { t =>
                    release()
                    t
                }(using CMeter.parasiticEc)
            }(using CMeter.parasiticEc)
        }

    /** Attempts to acquire a permit without blocking; runs `c` if successful, otherwise returns `None`. */
    inline def tryRun[A](inline c: CIO[A]): CIO[Option[A]] =
        CIO.deferLift {
            if !tryAcquire() then Future.successful(None: Option[A])
            else
                c.lower.transform {
                    case Success(a) =>
                        release()
                        Success[Option[A]](Some(a))
                    case Failure(t) =>
                        release()
                        Failure(t)
                }(using CMeter.parasiticEc)
        }

    /** Current count of available permits. */
    inline def availablePermits: CIO[Int] =
        CIO.defer(math.max(0, avail.get()))

    private def acquirePermit(): Future[Unit] =
        if avail.getAndDecrement() > 0 then Future.unit
        else
            val p = Promise[Unit]()
            waiters.offer(p)
            if avail.get() >= 0 then drainWaiters()
            p.future
        end if
    end acquirePermit

    private def tryAcquire(): Boolean =
        var result = false
        var retry  = true
        while retry do
            val v = avail.get()
            if v <= 0 then retry = false
            else if avail.compareAndSet(v, v - 1) then
                result = true
                retry = false
            end if
        end while
        result
    end tryAcquire

    private def release(): Unit =
        val w = waiters.poll()
        if w ne null then
            if !w.trySuccess(()) then release()
        else
            val _ = avail.incrementAndGet()
            drainWaiters()
        end if
    end release

    private def drainWaiters(): Unit =
        var continue = true
        while continue do
            val w = waiters.peek()
            if w eq null then continue = false
            else if avail.get() > 0 then
                val taken = waiters.poll()
                if taken ne null then
                    if avail.getAndDecrement() > 0 then
                        if !taken.trySuccess(()) then
                            val _ = avail.incrementAndGet()
                    else
                        waiters.offer(taken)
                        continue = false
                    end if
                end if
            else continue = false
            end if
        end while
    end drainWaiters

end CMeter

object CMeter:

    private val parasiticEc = scala.concurrent.ExecutionContext.parasitic

    /** Allocates a counting semaphore with `permits` permits. */
    inline def init(inline permits: Int): CIO[CMeter] =
        CIO.defer(new CMeter(
            new AtomicInteger(if permits < 0 then 0 else permits),
            new ConcurrentLinkedQueue[Promise[Unit]]()
        ))

    /** Wraps a native `CMeter` as a `CMeter`. Identity on the carrier. */
    inline def lift(inline u: CMeter): CMeter = u

end CMeter
