package kyo.compat

import java.util.concurrent.Semaphore
import ox.Ox

/** Underlying carrier is `java.util.concurrent.Semaphore`, a counting semaphore. `lift` and `lower` are identity since the carrier is
  * already a native `Semaphore`. `run` acquires a permit, runs the body, and releases via `try/finally` so the permit is freed on success
  * and failure. `acquire` blocks the calling thread when no permits are available (the native Ox idiom).
  */
opaque type CMeter = Semaphore

object CMeter:

    /** Allocates a counting semaphore with `permits` permits. */
    inline def init(inline permits: Int): CIO[CMeter] =
        CIO.defer(new Semaphore(permits))

    /** Wraps a native `Semaphore` as a `CMeter`. Identity on the carrier. */
    inline def lift(inline u: Semaphore): CMeter = u

    extension (inline self: CMeter)

        /** Unwraps to the native `Semaphore`. Identity on the carrier. */
        inline def lower: Semaphore = self

        /** Acquires one permit, runs `c`, and releases on completion (success or failure). */
        inline def run[A](inline c: CIO[A]): CIO[A] =
            CIO.deferLift {
                self.acquire()
                try c.lower
                finally self.release()
            }

        /** Attempts to acquire a permit without blocking; runs `c` if successful, otherwise returns `None`. */
        inline def tryRun[A](inline c: CIO[A]): CIO[Option[A]] =
            CIO.deferLift {
                if !self.tryAcquire() then None: Option[A]
                else
                    try Some(c.lower)
                    finally self.release()
                end if
            }

        /** Current count of available permits. */
        inline def availablePermits: CIO[Int] =
            CIO.defer(self.availablePermits())
    end extension

end CMeter
