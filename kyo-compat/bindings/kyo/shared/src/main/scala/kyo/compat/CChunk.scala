package kyo.compat

/** Backed by kyo.Chunk[A]. */
opaque type CChunk[+A] = kyo.Chunk[A]

object CChunk:

    inline def lift[A](inline c: kyo.Chunk[A]): CChunk[A] = c

    extension [A](inline self: CChunk[A])

        inline def lower: kyo.Chunk[A]         = self
        inline def toSeq: Seq[A]               = self.toSeq
        inline def toIndexedSeq: IndexedSeq[A] = self.toIndexedSeq
        inline def apply(inline i: Int): A     = self(i)
        inline def size: Int                   = self.size
        inline def iterator: Iterator[A]       = self.iterator
        inline def isEmpty: Boolean            = self.isEmpty

    end extension

end CChunk
