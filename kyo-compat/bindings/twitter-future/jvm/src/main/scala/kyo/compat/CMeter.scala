package kyo.compat

import com.twitter.concurrent.AsyncSemaphore
import com.twitter.concurrent.Permit
import com.twitter.util.Future
import com.twitter.util.Return
import com.twitter.util.Throw
import java.util.concurrent.CancellationException

/** Backed by `com.twitter.concurrent.AsyncSemaphore`. `run` calls `acquire(): Future[Permit]` and runs the body once the permit is granted;
  * no thread is blocked while waiting. `tryRun` issues a synchronous `acquire()` and checks `isDefined` — if the permit was granted on the
  * spot, the body runs; otherwise the queued acquire is cancelled via `raise` and `None` is returned.
  */
opaque type CMeter = AsyncSemaphore

object CMeter:

    inline def init(inline permits: Int): CIO[CMeter] =
        CIO.defer(new AsyncSemaphore(permits))

    inline def lift(inline u: AsyncSemaphore): CMeter = u

    extension (inline self: CMeter)

        inline def lower: AsyncSemaphore = self

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

        inline def availablePermits: CIO[Int] =
            CIO.defer(self.numPermitsAvailable)

    end extension

end CMeter
