package kyo.compat

/** Underlying carrier is `kyo.Chunk[A]`, kyo's native bulk collection type. `lift` and `lower` are identity since the carrier is already a
  * kyo-native chunk. The portable accessor surface (`toSeq`, `toIndexedSeq`, `apply`, `size`, `iterator`, `isEmpty`) is exposed on every
  * backend.
  */
opaque type CChunk[+A] = kyo.Chunk[A]

object CChunk:

    /** Wraps a native `kyo.Chunk` as a `CChunk`. Identity on the carrier. */
    inline def lift[A](inline c: kyo.Chunk[A]): CChunk[A] = c

    extension [A](inline self: CChunk[A])

        /** Unwraps to the native `kyo.Chunk`. Identity on the carrier. */
        inline def lower: kyo.Chunk[A] = self

        /** Views the chunk as a `Seq[A]`. */
        inline def toSeq: Seq[A] = self.toSeq

        /** Views the chunk as an `IndexedSeq[A]`. */
        inline def toIndexedSeq: IndexedSeq[A] = self.toIndexedSeq

        /** Returns the element at index `i`. */
        inline def apply(inline i: Int): A = self(i)

        /** Number of elements. */
        inline def size: Int = self.size

        /** Iterator over the chunk's elements. */
        inline def iterator: Iterator[A] = self.iterator

        /** `true` if the chunk has no elements. */
        inline def isEmpty: Boolean = self.isEmpty

    end extension

end CChunk
