package kyo

import scala.collection.mutable.ArrayBuilder
import scala.collection.mutable.ReusableBuilder
import scala.reflect.ClassTag

/** A **mutable** builder for creating Chunks.
  *
  * ChunkBuilder provides an efficient way to construct Chunks by incrementally adding elements. It can be reused after calling result().
  *
  * @tparam A
  *   the type of elements in the Chunk being built
  */
sealed abstract class ChunkBuilder[A] extends ReusableBuilder[A, Chunk.Indexed[A]]
object ChunkBuilder:

    /** Creates a new ChunkBuilder with no size hint.
      *
      * @tparam A
      *   the type of elements in the Chunk to be built
      * @return
      *   a new ChunkBuilder instance
      */
    def init[A]: ChunkBuilder[A] = init(0)

    /** Creates a new ChunkBuilder with a size hint.
      *
      * @tparam A
      *   the type of elements in the Chunk to be built
      * @param hint
      *   the expected number of elements to be added
      * @return
      *   a new ChunkBuilder instance
      */
    def init[A](hint: Int): ChunkBuilder[A] =
        new ChunkBuilder[A]:
            var builder = Maybe.empty[ArrayBuilder[A]]
            var _hint   = hint

            override def addOne(elem: A): this.type =
                builder match
                    case Absent =>
                        val arr = ArrayBuilder.make[A](using ClassTag.Any.asInstanceOf[ClassTag[A]])
                        if _hint > 0 then arr.sizeHint(_hint)
                        arr.addOne(elem)
                        builder = Maybe(arr)
                    case Present(arr) => discard(arr.addOne(elem))
                end match
                this
            end addOne

            override def sizeHint(n: Int): Unit =
                _hint = n
                builder.foreach(_.sizeHint(n))

            override def clear(): Unit = builder.foreach(_.clear())

            override def result(): Chunk.Indexed[A] =
                val chunk = builder.fold(Chunk.indexedEmpty[A])(b => Chunk.fromNoCopy(b.result()))
                chunk
            end result

            override def knownSize: Int = builder.fold(0)(_.knownSize)

            override def toString(): String =
                if _hint > 0 then s"ChunkBuilder(size = $knownSize, hint = $_hint)"
                else s"ChunkBuilder(size = $knownSize)"
    end init
end ChunkBuilder
