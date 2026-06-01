package kyo.compat

import com.twitter.concurrent.AsyncSemaphore
import com.twitter.concurrent.Permit
import com.twitter.util.Future
import com.twitter.util.Return
import com.twitter.util.Throw
import java.util.concurrent.CancellationException

/** Underlying carrier is `com.twitter.concurrent.AsyncSemaphore`, a counting semaphore. There is no `Frame` / `Trace` to propagate. `lift`
  * and `lower` are identity since the carrier is already a native Twitter AsyncSemaphore. `run` calls `acquire(): Future[Permit]` and runs
  * the body once the permit is granted; no thread is blocked while waiting and the permit releases on both success and failure. `tryRun`
  * issues a synchronous `acquire()` and checks `isDefined` — if the permit was granted on the spot, the body runs; otherwise the queued
  * acquire is cancelled via `raise(CancellationException)` and `None` is returned.
  */
opaque type CMeter = AsyncSemaphore

object CMeter:

    /** Allocates a counting semaphore with `permits` permits. */
    inline def init(inline permits: Int): CIO[CMeter] =
        CIO.defer(new AsyncSemaphore(permits))

    /** Wraps a native `com.twitter.concurrent.AsyncSemaphore` as a `CMeter`. Identity on the carrier. */
    inline def lift(inline u: AsyncSemaphore): CMeter = u

    extension (inline self: CMeter)

        /** Unwraps to the native `com.twitter.concurrent.AsyncSemaphore`. Identity on the carrier. */
        inline def lower: AsyncSemaphore = self

        /** Acquires one permit, runs `c`, and releases on completion (success or failure). */
        inline def run[A](inline c: CIO[A]): CIO[A] =
            CIO.deferLift {
                self.acquire().flatMap { permit =>
                    c.lower().transform {
                        case Return(a) =>
                            permit.release()
                            Future.value(a)
                        case Throw(t) =>
                            permit.release()
                            Future.exception(t)
                    }
                }
            }

        /** Attempts to acquire a permit without blocking; runs `c` if successful, otherwise cancels the queued acquire and returns `None`.
          */
        inline def tryRun[A](inline c: CIO[A]): CIO[Option[A]] =
            CIO.deferLift {
                val acq = self.acquire()
                if acq.isDefined then
                    acq.flatMap { permit =>
                        c.lower().transform {
                            case Return(a) =>
                                permit.release()
                                Future.value[Option[A]](Some(a))
                            case Throw(t) =>
                                permit.release()
                                Future.exception[Option[A]](t)
                        }
                    }
                else
                    // Lost the race: no permit available now. Cancel the queued acquire via raise
                    // (AsyncSemaphore's interrupt handler removes the waiter). In the vanishingly
                    // small window where a permit arrives between isDefined-check and raise, the
                    // respond-callback releases it so no permit leaks.
                    acq.raise(new CancellationException("CMeter.tryRun: no permit available"))
                    val _ = acq.respond {
                        case Return(permit) => permit.release()
                        case _              => ()
                    }
                    Future.value(None: Option[A])
                end if
            }

        /** Current count of available permits. */
        inline def availablePermits: CIO[Int] =
            CIO.defer(self.numPermitsAvailable)

    end extension

end CMeter
