package kyo.compat

import ox.CancellableFork

/** Underlying carrier is `ox.CancellableFork[? <: A]`. The `? <: A` wildcard keeps the covariant `CFiber[+A]` sound over Ox's invariant
  * `CancellableFork` (which only uses its type parameter in covariant `join` positions). `init` uses `ox.forkCancellable`; the fork's
  * lifetime is bounded by the surrounding `ox.supervised` block. Ox's fork API is join-only (no completion callback) and its forks are
  * scope-bound, so `onComplete` spawns a virtual thread via `ox.oxThreadFactory` that calls `self.joinEither()` independently of the
  * surrounding Ox scope; `joinEither` surfaces cancel as `Left(t)` without re-interrupting the calling thread, so the callback runs in a
  * fresh `ox.supervised` block on a daemon thread and fires with `Failure(t)` on cancel or error and `Success(a)` on success.
  */
opaque type CFiber[+A] = CancellableFork[? <: A]

object CFiber:

    /** Forks `c` as a new fiber; the fork's lifetime is bounded by the surrounding `ox.supervised` scope. */
    inline def init[A](inline c: CIO[A]): CIO[CFiber[A]] =
        CIO.deferLift(ox.forkCancellable(c.lower))

    /** Wraps a native `ox.CancellableFork` as a `CFiber`. Identity on the carrier. */
    inline def lift[A](inline u: CancellableFork[A]): CFiber[A] = u

    extension [A](self: CFiber[A])

        /** Unwraps to the native `ox.CancellableFork`. Identity on the carrier. */
        inline def lower: CancellableFork[? <: A] = self

        /** Joins the fiber and returns its result. */
        inline def get: CIO[A] =
            CIO.deferLift(self.join())

        /** Registers `cb` to fire when the fiber completes; success and failure are reified as `scala.util.Try`. The observer runs on a
          * daemon thread independent of the surrounding `ox.supervised` scope.
          */
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
