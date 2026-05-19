package kyo.compat

/** Backed by `Vector[A]`. */
opaque type CChunk[+A] = Vector[A]

object CChunk:

    inline def lift[A](inline c: Vector[A]): CChunk[A] = c

    extension [A](inline self: CChunk[A])

        inline def lower: Vector[A]            = self
        inline def toSeq: Seq[A]               = self
        inline def toIndexedSeq: IndexedSeq[A] = self
        inline def apply(inline i: Int): A     = self(i)
        inline def size: Int                   = self.size
        inline def iterator: Iterator[A]       = self.iterator
        inline def isEmpty: Boolean            = self.isEmpty

    end extension

end CChunk
