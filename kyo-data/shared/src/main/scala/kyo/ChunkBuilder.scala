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
final class ChunkBuilder[A: ClassTag] private (var hint: Int) extends ReusableBuilder[A, Chunk.Indexed[A]]:
    private var builder = Maybe.empty[ArrayBuilder[A]]

    override def addOne(elem: A): this.type =
        builder match
            case Absent =>
                val arr = ArrayBuilder.make[A]
                if hint > 0 then arr.sizeHint(hint)
                arr.addOne(elem)
                builder = Maybe(arr)
            case Present(arr) => discard(arr.addOne(elem))
        end match
        this
    end addOne

    override def sizeHint(n: Int): Unit =
        hint = n
        builder.foreach(_.sizeHint(n))

    override def clear(): Unit = builder.foreach(_.clear())

    override def result(): Chunk.Indexed[A] =
        val chunk = builder.fold(Chunk.Indexed.empty[A])(b => Chunk.fromNoCopy(b.result()))
        builder.foreach(_.clear())
        chunk
    end result

    override def knownSize: Int = builder.fold(0)(_.knownSize)

    override def toString(): String =
        if hint > 0 then s"ChunkBuilder(size = $knownSize, hint = $hint)"
        else s"ChunkBuilder(size = $knownSize)"
end ChunkBuilder

object ChunkBuilder:

    /** Creates a new ChunkBuilder with no size hint.
      *
      * @tparam A
      *   the type of elements in the Chunk to be built
      * @return
      *   a new ChunkBuilder instance
      */
    def init[A: ClassTag]: ChunkBuilder[A] = init(0)

    def initBoxed[A]: ChunkBuilder[A] = init(using ClassTag.Any.asInstanceOf[ClassTag[A]])

    /** Creates a new ChunkBuilder with a size hint.
      *
      * @tparam A
      *   the type of elements in the Chunk to be built
      * @param hint
      *   the expected number of elements to be added
      * @return
      *   a new ChunkBuilder instance
      */
    def init[A: ClassTag](hint: Int): ChunkBuilder[A] =
        new ChunkBuilder[A](hint)

    def initBoxed[A](hint: Int): ChunkBuilder[A] =
        init[A](hint)(using ClassTag.Any.asInstanceOf[ClassTag[A]])

end ChunkBuilder
