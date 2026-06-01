package kyo.compat

import kyo.*

/** Underlying carrier is `kyo.AtomicBoolean`, a lock-free atomic boolean cell. `lift` and `lower` are identity since the carrier is already
  * a kyo-native atomic. Operations match the `java.util.concurrent.atomic` contract.
  */
opaque type CAtomicBoolean = AtomicBoolean

object CAtomicBoolean:

    /** Allocates a fresh atomic boolean initialized to `v`. */
    inline def init(inline v: Boolean)(using inline frame: Frame): CIO[CAtomicBoolean] =
        CIO.lift(AtomicBoolean.init(v))

    /** Wraps a native `kyo.AtomicBoolean` as a `CAtomicBoolean`. Identity on the carrier. */
    inline def lift(inline u: AtomicBoolean): CAtomicBoolean = u

    extension (inline self: CAtomicBoolean)

        /** Unwraps to the native `kyo.AtomicBoolean`. Identity on the carrier. */
        inline def lower: AtomicBoolean = self

        /** Reads the current value. */
        inline def get(using inline frame: Frame): CIO[Boolean] = CIO.lift(self.get)

        /** Atomically sets the value to `v`. */
        inline def set(inline v: Boolean)(using inline frame: Frame): CIO[Unit] = CIO.lift(self.set(v))

        /** Atomically sets the value to `v` and returns the previous value. */
        inline def getAndSet(inline v: Boolean)(using inline frame: Frame): CIO[Boolean] = CIO.lift(self.getAndSet(v))

        /** Atomic compare-and-set: replaces `expected` with `updated` iff the current value equals `expected`. */
        inline def compareAndSet(inline expected: Boolean, inline updated: Boolean)(
            using inline frame: Frame
        ): CIO[Boolean] =
            CIO.lift(self.compareAndSet(expected, updated))

    end extension

end CAtomicBoolean
