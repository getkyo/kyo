package kyo.compat

import zio.*

/** Backed by `zio.Ref[Boolean]`. `compareAndSet` is composed via `Ref.modify` since ZIO's `Ref` has no native CAS. */
opaque type CAtomicBoolean = Ref[Boolean]

object CAtomicBoolean:

    inline def init(inline v: Boolean)(using inline trace: Trace): CIO[CAtomicBoolean] =
        CIO.lift(Ref.make[Boolean](v))

    inline def lift(inline u: Ref[Boolean]): CAtomicBoolean = u

    extension (inline self: CAtomicBoolean)

        inline def lower: Ref[Boolean] = self

        inline def get(using inline trace: Trace): CIO[Boolean] =
            CIO.lift(self.get)

        inline def set(inline v: Boolean)(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.set(v))

        inline def getAndSet(inline v: Boolean)(using inline trace: Trace): CIO[Boolean] =
            CIO.lift(self.getAndSet(v))

        inline def compareAndSet(inline expected: Boolean, inline updated: Boolean)(
            using inline trace: Trace
        ): CIO[Boolean] =
            CIO.lift(self.modify(cur => if cur == expected then (true, updated) else (false, cur)))
    end extension

end CAtomicBoolean
