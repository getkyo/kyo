package kyo.compat

import kyo.compat.*

class CChunkTest extends CompatTest:

    // CIO.foreach is the cross-binding way to build a CChunk[A] without
    // depending on the underlying carrier type (kyo.Chunk, Vector, zio.Chunk).

    "toSeq returns same elements in order" in run {
        CIO.foreach(Vector(1, 2, 3))(CIO.value).map { chunk =>
            val s: Seq[Int] = chunk.toSeq
            assert(s == Seq(1, 2, 3))
        }
    }

    "toIndexedSeq returns same elements in order" in run {
        CIO.foreach(Vector(1, 2, 3))(CIO.value).map { chunk =>
            val s: IndexedSeq[Int] = chunk.toIndexedSeq
            assert(s == IndexedSeq(1, 2, 3))
        }
    }

    "apply returns element at given index" in run {
        CIO.foreach(Vector(10, 20, 30))(CIO.value).map { chunk =>
            assert(chunk.apply(1) == 20)
        }
    }

    "size returns the number of elements" in run {
        CIO.foreach(Vector(1, 2, 3))(CIO.value).map { chunk =>
            assert(chunk.size == 3)
        }
    }

    "isEmpty is true for empty chunk and false for non-empty chunk" in run {
        val emptyC    = CIO.foreach(Vector.empty[Int])(CIO.value)
        val nonEmptyC = CIO.foreach(Vector(1))(CIO.value)
        emptyC.flatMap { emptyChunk =>
            nonEmptyC.flatMap { nonEmptyChunk =>
                CIO.value((emptyChunk.isEmpty, nonEmptyChunk.isEmpty))
            }
        }.map { case (empty, nonEmpty) =>
            assert(empty, "empty chunk must report isEmpty == true")
            assert(!nonEmpty, "non-empty chunk must report isEmpty == false")
        }
    }

    "iterator produces elements in order" in run {
        CIO.foreach(Vector(1, 2, 3))(CIO.value).map { chunk =>
            assert(chunk.iterator.toList == List(1, 2, 3))
        }
    }

    "CIO.foreach returns a CChunk with correct size and values" in run {
        val c: CIO[CChunk[Int]] = CIO.foreach(1 to 5)(i => CIO.value(i * 2))
        c.map { chunk =>
            assert(chunk.size == 5)
            assert(chunk.apply(0) == 2)
            assert(chunk.apply(4) == 10)
        }
    }

    "two CChunks built from same elements via different shapes are structurally equal" in run {
        val ch1 = CIO.foreach(Vector(1, 2, 3))(CIO.value)
        val ch2 = CIO.foreach((1 to 3).toSeq)(CIO.value)
        ch1.flatMap { c1 =>
            ch2.flatMap { c2 =>
                CIO.defer(c1.equals(c2)) // CChunk backing-type equality (bypasses opaque CanEqual restriction)
            }
        }.map(eq => assert(eq, "chunks built from different shapes should be equal"))
    }

    "large chunk of 10_000 elements has correct size, last element, and iterator count" in run {
        CIO.foreach(1 to 10_000)(CIO.value).map { chunk =>
            assert(chunk.size == 10_000)
            assert(chunk.apply(9_999) == 10_000)
            assert(chunk.iterator.size == 10_000)
        }
    }

end CChunkTest
