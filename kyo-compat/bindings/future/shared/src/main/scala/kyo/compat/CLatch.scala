package kyo.compat

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.Promise

/** Backed by an `AtomicInteger` counter and a `ConcurrentLinkedQueue` of waiting `Promise[Unit]`s. `init(n <= 0)` produces an
  * already-released latch. `await` returns immediately when the count is already zero, otherwise enqueues a Promise and re-checks to guard
  * against a lost wakeup. `release` decrements the counter; when it reaches zero all waiters are drained and completed. No
  * platform-specific blocking primitives are used, so this links on JVM, JS, and Native.
  */
final class CLatch(
    count: AtomicInteger,
    waiters: ConcurrentLinkedQueue[Promise[Unit]]
):

    inline def lower: CLatch = this

    inline def await: CIO[Unit] =
        CIO.deferLift {
            if count.get() <= 0 then Future.unit
            else
                val p = Promise[Unit]()
                waiters.offer(p)
                // Re-check after offer to guard against a lost wakeup where
                // release() raced between the get() check above and the offer.
                if count.get() <= 0 then drainWaiters()
                p.future
            end if
        }

    inline def release: CIO[Unit] =
        CIO.defer {
            if count.decrementAndGet() <= 0 then drainWaiters()
        }

    private def drainWaiters(): Unit =
        var w = waiters.poll()
        while w ne null do
            val _ = w.trySuccess(())
            w = waiters.poll()
        end while
    end drainWaiters

end CLatch

object CLatch:

    inline def init(inline n: Int): CIO[CLatch] =
        CIO.defer(new CLatch(
            new AtomicInteger(if n <= 0 then 0 else n),
            new ConcurrentLinkedQueue[Promise[Unit]]()
        ))

    inline def lift(inline u: CLatch): CLatch = u

end CLatch
