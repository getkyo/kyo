package kyo

import java.util.ArrayDeque
import java.util.ArrayList
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.collection.immutable.HashMap

/** A mutable builder for constructing [[Dict]] instances incrementally. Entries are accumulated via `add` and the final Dict is produced by
  * calling `result`. Duplicate keys are resolved by keeping the last added value.
  *
  * DictBuilder uses a thread-local buffer pool to reduce allocation pressure. After calling `result`, the builder resets and its internal
  * buffer is returned to the pool for reuse.
  *
  * {{{
  * val b = DictBuilder.init[String, Int]
  * b.add("a", 1).add("b", 2)
  * val dict = b.result() // Dict("a" -> 1, "b" -> 2)
  * }}}
  *
  * @tparam K
  *   the type of keys
  * @tparam V
  *   the type of values
  * @see
  *   [[Dict]] for the immutable dictionary type
  */
sealed class DictBuilder[K, V] extends Serializable:
    private var buffer = Maybe.empty[ArrayList[Any]]

    /** Adds a key-value pair to this builder. Returns `this` for chaining. */
    final def add(key: K, value: V): this.type =
        buffer match
            case Absent =>
                val buf = DictBuilder.acquireBuffer()
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

    /** Builds and returns the resulting [[Dict]], then resets this builder. The internal buffer is returned to the thread-local pool. */
    final def result(): Dict[K, V] =
        val dict = buffer.fold(Dict.empty[K, V]) { buf =>
            val n = buf.size / 2
            if n == 0 then Dict.empty[K, V]
            else if n <= Dict.threshold then
                DictBuilder.buildSmall(buf, n)
            else
                DictBuilder.buildLarge(buf, n)
            end if
        }
        buffer.foreach(DictBuilder.releaseBuffer)
        buffer = Absent
        dict
    end result

end DictBuilder

object DictBuilder:

    private val bufferCache =
        new ThreadLocal[ArrayDeque[ArrayList[?]]]:
            override def initialValue() = new ArrayDeque[ArrayList[?]]

    private[kyo] def acquireBuffer(): ArrayList[Any] =
        Maybe(bufferCache.get().poll()).getOrElse(new ArrayList).asInstanceOf[ArrayList[Any]]

    private[kyo] def releaseBuffer(buffer: ArrayList[?]): Unit =
        buffer.clear()
        discard(bufferCache.get().add(buffer))

    /** Creates a new empty DictBuilder. */
    def init[K, V]: DictBuilder[K, V] = new DictBuilder[K, V]

    /** Creates a DictBuilder that also implements `(K, V) => Unit`, allowing it to be passed directly to `foreachEntry`-style methods. The
      * inline transform function receives the builder, key, and value, and is responsible for calling `add`.
      */
    @nowarn("msg=anonymous")
    inline def initTransform[K, V, K2, V2](inline f: (DictBuilder[K2, V2], K, V) => Unit): ((K, V) => Unit) & DictBuilder[K2, V2] =
        new DictBuilder[K2, V2] with Function2[K, V, Unit]:
            def apply(k: K, v: V): Unit = f(this, k, v)

    // Build keys-first array with dedup via linear scan (n ≤ 8)
    private def buildSmall[K, V](entries: ArrayList[Any], n: Int): Dict[K, V] =
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
        if j == n then Dict.fromArrayUnsafe(arr)
        else
            val compact = new Array[Any](j * 2).asInstanceOf[Array[K | V]]
            System.arraycopy(arr, 0, compact, 0, j)
            System.arraycopy(arr, n, compact, j, j)
            Dict.fromArrayUnsafe(compact)
        end if
    end buildSmall

    private def buildLarge[K, V](entries: ArrayList[Any], n: Int): Dict[K, V] =
        @tailrec def loop(i: Int, map: HashMap[K, V]): HashMap[K, V] =
            if i >= n then map
            else loop(i + 1, map.updated(entries.get(i * 2).asInstanceOf[K], entries.get(i * 2 + 1).asInstanceOf[V]))

        val map  = loop(0, HashMap.empty)
        val size = map.size
        if size <= Dict.threshold then
            val arr = new Array[Any](size * 2).asInstanceOf[Array[K | V]]
            var j   = 0
            map.foreachEntry { (k, v) =>
                arr(j) = k
                arr(size + j) = v
                j += 1
            }
            Dict.fromArrayUnsafe(arr)
        else
            Dict.fromHashMap(map)
        end if
    end buildLarge

end DictBuilder
