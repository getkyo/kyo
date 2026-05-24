package kyo.compat

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.nowarn
import scala.concurrent.Future

/** Backed by `java.util.concurrent.atomic.AtomicReference[A]`. */
opaque type CAtomicRef[A] = AtomicReference[A]

object CAtomicRef:

    inline def init[A](inline a: A): CIO[CAtomicRef[A]] =
        CIO.defer(new AtomicReference[A](a))

    inline def lift[A](inline u: AtomicReference[A]): CAtomicRef[A] = u

    extension [A](inline self: CAtomicRef[A])

        inline def lower: AtomicReference[A] = self

        inline def get: CIO[A]                    = CIO.defer(self.get())
        inline def set(inline a: A): CIO[Unit]    = CIO.defer(self.set(a))
        inline def getAndSet(inline a: A): CIO[A] = CIO.defer(self.getAndSet(a))
        @nowarn("msg=anonymous")
        inline def updateAndGet(inline f: A => A): CIO[A] = CIO.defer(self.updateAndGet(f(_)))
        @nowarn("msg=anonymous")
        inline def getAndUpdate(inline f: A => A): CIO[A] = CIO.defer(self.getAndUpdate(f(_)))

        inline def compareAndSet(inline expected: A, inline updated: A): CIO[Boolean] =
            CIO.defer(self.compareAndSet(expected, updated))

    end extension

end CAtomicRef
