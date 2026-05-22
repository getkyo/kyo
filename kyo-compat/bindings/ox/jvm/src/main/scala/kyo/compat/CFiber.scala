package kyo.compat

import ox.CancellableFork

/** Backed by `ox.CancellableFork[? <: A]`. The `? <: A` wildcard keeps the covariant `CFiber[+A]` sound over Ox's invariant
  * `CancellableFork` (which only uses its type parameter in covariant `join` positions). `init` uses `forkCancellable` (unsupervised). Ox's
  * fork API is join-only (no completion callback) and its forks are scope-bound, so `onComplete` spawns a virtual thread via
  * `ox.oxThreadFactory` that calls `self.joinEither()` independently of the surrounding Ox scope; `joinEither` surfaces cancel as `Left(t)`
  * without re-interrupting the calling thread, so the callback runs in a fresh `ox.supervised` block and fires with `Failure(t)` on cancel
  * or error and `Success(a)` on success.
  */
opaque type CFiber[+A] = CancellableFork[? <: A]

object CFiber:

    inline def init[A](inline c: CIO[A]): CIO[CFiber[A]] =
        CIO.deferLift(ox.forkCancellable(c.lower))

    inline def lift[A](inline u: CancellableFork[A]): CFiber[A] = u

    extension [A](self: CFiber[A])

        inline def lower: CancellableFork[? <: A] = self

        inline def get: CIO[A] =
            CIO.deferLift(self.join())

        inline def onComplete(cb: scala.util.Try[A] => CIO[Unit]): CIO[Unit] =
            CIO.deferLift {
                // join the fork off the Ox scope, then run the callback in a fresh supervised scope.
                val t = ox.oxThreadFactory.newThread { () =>
                    val outcome: scala.util.Try[A] = self.joinEither() match
                        case Right(a) => scala.util.Success(a)
                        case Left(e)  => scala.util.Failure(e)
                    ox.supervised {
                        cb(outcome).lower
                    }
                }
                t.start()
                ()
            }
    end extension
end CFiber
