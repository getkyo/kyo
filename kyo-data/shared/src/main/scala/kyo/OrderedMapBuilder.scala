package kyo

import java.util.ArrayDeque
import java.util.ArrayList
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.collection.immutable.TreeSeqMap

/** A mutable builder for constructing [[OrderedMap]] instances incrementally. Entries are accumulated via `add` and the final OrderedMap is
  * produced by calling `result`. Insertion order is preserved: a new key appends at the end, and an already-added key has its value
  * replaced in place, keeping its first-add position. Duplicate keys resolve to the last added value.
  *
  * OrderedMapBuilder uses a thread-local buffer pool to reduce allocation pressure. After calling `result`, the builder resets and its
  * internal buffer is returned to the pool for reuse. Distinct builders acquire distinct buffers, so nested or interleaved builders (for
  * example one per open document while encoding) do not alias.
  *
  * {{{
  * val b = OrderedMapBuilder.init[String, Int]
  * b.add("a", 1).add("b", 2)
  * val map = b.result() // OrderedMap("a" -> 1, "b" -> 2)
  * }}}
  *
  * @tparam K
  *   the type of keys
  * @tparam V
  *   the type of values
  * @see
  *   [[OrderedMap]] for the immutable insertion-ordered map type
  */
sealed class OrderedMapBuilder[K, V] extends Serializable:
    private var buffer = Maybe.empty[ArrayList[Any]]

    /** Adds a key-value pair to this builder. Returns `this` for chaining. */
    final def add(key: K, value: V): this.type =
        buffer match
            case Absent =>
                val buf = OrderedMapBuilder.acquireBuffer()
                buf.add(key)
                buf.add(value)
                buffer = Present(buf)
            case Present(buf) =>
                discard(buf.add(key))
                discard(buf.add(value))
        end match
        this
    end add

    /** Returns the number of entries added so far. */
    final def size: Int = buffer.fold(0)(_.size / 2)

    /** Removes all accumulated entries from this builder. */
    final def clear(): Unit = buffer.foreach(_.clear())

    /** Builds and returns the resulting [[OrderedMap]] in insertion order, then resets this builder. The internal buffer is returned to the
      * thread-local pool.
      */
    final def result(): OrderedMap[K, V] =
        val map = buffer.fold(OrderedMap.empty[K, V]) { buf =>
            val n = buf.size / 2
            if n == 0 then OrderedMap.empty[K, V]
            else if n <= OrderedMap.threshold then
                OrderedMapBuilder.buildSmall(buf, n)
            else
                OrderedMapBuilder.buildLarge(buf, n)
            end if
        }
        buffer.foreach(OrderedMapBuilder.releaseBuffer)
        buffer = Absent
        map
    end result

end OrderedMapBuilder

object OrderedMapBuilder:

    private val bufferCache =
        new ThreadLocal[ArrayDeque[ArrayList[?]]]:
            override def initialValue() = new ArrayDeque[ArrayList[?]]

    private[kyo] def acquireBuffer(): ArrayList[Any] =
        Maybe(bufferCache.get().poll()).getOrElse(new ArrayList).asInstanceOf[ArrayList[Any]]

    private[kyo] def releaseBuffer(buffer: ArrayList[?]): Unit =
        buffer.clear()
        discard(bufferCache.get().add(buffer))

    /** Creates a new empty OrderedMapBuilder. */
    def init[K, V]: OrderedMapBuilder[K, V] = new OrderedMapBuilder[K, V]

    /** Creates an OrderedMapBuilder that also implements `(K, V) => Unit`, allowing it to be passed directly to `foreachEntry`-style
      * methods. The inline transform function receives the builder, key, and value, and is responsible for calling `add`.
      */
    @nowarn("msg=anonymous")
    inline def initTransform[K, V, K2, V2](inline f: (OrderedMapBuilder[K2, V2], K, V) => Unit)
        : ((K, V) => Unit) & OrderedMapBuilder[K2, V2] =
        new OrderedMapBuilder[K2, V2] with Function2[K, V, Unit]:
            def apply(k: K, v: V): Unit = f(this, k, v)

    // Build keys-first array with insertion-order dedup via linear scan (n <= 8): a duplicate key keeps its first position and takes the
    // last value.
    private def buildSmall[K, V](entries: ArrayList[Any], n: Int): OrderedMap[K, V] =
        val arr = new Array[Any](n * 2).asInstanceOf[Array[K | V]]

        @tailrec def findDup(arr: Array[K | V], kr: AnyRef, key: Any, j: Int, idx: Int): Int =
            if idx >= j then -1
            else
                val k = arr(idx)
                if (k.asInstanceOf[AnyRef] eq kr) || k.equals(key) then idx
                else findDup(arr, kr, key, j, idx + 1)

        @tailrec def loop(i: Int, j: Int): Int =
            if i >= n then j
            else
                val key = entries.get(i * 2).asInstanceOf[K | V]
                val kr  = key.asInstanceOf[AnyRef]
                val v   = entries.get(i * 2 + 1).asInstanceOf[K | V]
                val dup = findDup(arr, kr, key, j, 0)
                if dup >= 0 then
                    arr(n + dup) = v
                    loop(i + 1, j)
                else
                    arr(j) = key
                    arr(n + j) = v
                    loop(i + 1, j + 1)
                end if

        val j = loop(0, 0)
        if j == n then OrderedMap.fromArrayUnsafe(arr)
        else
            val compact = new Array[Any](j * 2).asInstanceOf[Array[K | V]]
            System.arraycopy(arr, 0, compact, 0, j)
            System.arraycopy(arr, n, compact, j, j)
            OrderedMap.fromArrayUnsafe(compact)
        end if
    end buildSmall

    private def buildLarge[K, V](entries: ArrayList[Any], n: Int): OrderedMap[K, V] =
        @tailrec def loop(i: Int, map: TreeSeqMap[K, V]): TreeSeqMap[K, V] =
            if i >= n then map
            else loop(i + 1, map.updated(entries.get(i * 2).asInstanceOf[K], entries.get(i * 2 + 1).asInstanceOf[V]))

        val map  = loop(0, TreeSeqMap.empty[K, V])
        val size = map.size
        if size <= OrderedMap.threshold then
            val arr = new Array[Any](size * 2).asInstanceOf[Array[K | V]]
            var j   = 0
            map.foreachEntry { (k, v) =>
                arr(j) = k
                arr(size + j) = v
                j += 1
            }
            OrderedMap.fromArrayUnsafe(arr)
        else
            OrderedMap.fromTreeSeqMap(map)
        end if
    end buildLarge

end OrderedMapBuilder
