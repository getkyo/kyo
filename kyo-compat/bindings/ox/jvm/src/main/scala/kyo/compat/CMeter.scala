package kyo.compat

import java.util.concurrent.Semaphore
import ox.Ox

/** Backed by `java.util.concurrent.Semaphore`. `run` acquires then releases via `try/finally`. */
opaque type CMeter = Semaphore

object CMeter:

    inline def init(inline permits: Int): CIO[CMeter] =
        CIO.defer(new Semaphore(permits))

    inline def lift(inline u: Semaphore): CMeter = u

    extension (inline self: CMeter)

        inline def lower: Semaphore = self

        inline def run[A](inline c: CIO[A]): CIO[A] =
            CIO.deferLift {
                self.acquire()
                try CIO.unsafeRun(c)
                finally self.release()
            }

        inline def tryRun[A](inline c: CIO[A]): CIO[Option[A]] =
            CIO.deferLift {
                if !self.tryAcquire() then None: Option[A]
                else
                    try Some(CIO.unsafeRun(c))
                    finally self.release()
                end if
            }

        inline def availablePermits: CIO[Int] =
            CIO.defer(self.availablePermits())
    end extension

end CMeter
