package kyo

import java.util.ArrayDeque
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuilder
import scala.collection.mutable.Builder
import scala.collection.mutable.ReusableBuilder
import scala.reflect.ClassTag

/** A **mutable** builder for creating Chunks.
  *
  * ChunkBuilder provides an efficient way to construct Chunks by incrementally adding elements. It can be reused after calling result().
  *
  * @tparam A
  *   the type of elements in the Chunk being built
  */
sealed class ChunkBuilder[A] extends ReusableBuilder[A, Chunk.Indexed[A]] with Serializable:
    private var builder = Maybe.empty[ArrayDeque[A]]

    final override def addOne(elem: A): this.type =
        builder match
            case Absent =>
                val buffer = ChunkBuilder.acquireBuffer[A]()
                buffer.add(elem)
                builder = Present(buffer)
            case Present(buffer) =>
                discard(buffer.add(elem))
        end match
        this
    end addOne

    override def addAll(elems: IterableOnce[A]): this.type =
        elems match
            case elems: Chunk[A] =>
                elems.foreach(addOne)
            case _ =>
                val it = elems.iterator
                @tailrec def loop(): Unit =
                    if it.hasNext then
                        addOne(it.next())
                        loop()
                loop()
        end match
        this
    end addAll

    final override def clear(): Unit = builder.foreach(_.clear())

    final override def result(): Chunk.Indexed[A] =
        val chunk = builder.fold(Chunk.Indexed.empty[A])(b => Chunk.fromNoCopy(b.toArray.asInstanceOf[Array[A]]))
        builder.foreach(ChunkBuilder.releaseBuffer)
        builder = Absent
        chunk
    end result

    final override def knownSize: Int = builder.fold(0)(_.size)

    override def toString(): String =
        s"ChunkBuilder(size = $knownSize)"
end ChunkBuilder

object ChunkBuilder:

    private val bufferCache = ThreadLocal.withInitial(() => new ArrayDeque[ArrayDeque[?]])

    private[kyo] def acquireBuffer[A](): ArrayDeque[A] =
        Maybe(bufferCache.get().poll()).getOrElse(new ArrayDeque).asInstanceOf[ArrayDeque[A]]

    private[kyo] def releaseBuffer(buffer: ArrayDeque[?]): Unit =
        buffer.clear()
        discard(bufferCache.get().add(buffer))

    /** Creates a new ChunkBuilder with no size hint.
      *
      * @tparam A
      *   the type of elements in the Chunk to be built
      * @return
      *   a new ChunkBuilder instance
      */
    def init[A]: ChunkBuilder[A] =
        new ChunkBuilder[A]

    @nowarn("msg=anonymous")
    inline def initTransform[A, B](inline f: (ChunkBuilder[B], A) => Unit): (A => Unit) & ChunkBuilder[B] =
        new ChunkBuilder[B] with Function1[A, Unit]:
            def apply(elem: A): Unit = f(this, elem)

end ChunkBuilder
