package kyo

import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.collection.immutable.HashMap

sealed class DictBuilder[K, V]:
    private val builder = ChunkBuilder.init[K | V]

    final def add(key: K, value: V): this.type =
        builder.addOne(key)
        builder.addOne(value)
        this
    end add

    final def size: Int = builder.knownSize / 2

    final def clear(): Unit = builder.clear()

    final def result(): Dict[K, V] =
        val chunk = builder.result()
        val n     = chunk.length / 2
        if n == 0 then Dict.empty[K, V]
        else if n <= Dict.threshold then
            DictBuilder.buildSmall(chunk, n)
        else
            DictBuilder.buildLarge(chunk, n)
        end if
    end result

end DictBuilder

object DictBuilder:
    def init[K, V]: DictBuilder[K, V] = new DictBuilder[K, V]

    @nowarn("msg=anonymous")
    inline def initTransform[K, V, K2, V2](inline f: (DictBuilder[K2, V2], K, V) => Unit): ((K, V) => Unit) & DictBuilder[K2, V2] =
        new DictBuilder[K2, V2] with Function2[K, V, Unit]:
            def apply(k: K, v: V): Unit = f(this, k, v)

    // Build keys-first array with dedup via linear scan (n ≤ 8)
    private def buildSmall[K, V](chunk: Chunk[K | V], n: Int): Dict[K, V] =
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
                val key = chunk(i * 2)
                val kr  = key.asInstanceOf[AnyRef]
                val v   = chunk(i * 2 + 1)
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

    private def buildLarge[K, V](chunk: Chunk[K | V], n: Int): Dict[K, V] =
        @tailrec def loop(i: Int, map: HashMap[K, V]): HashMap[K, V] =
            if i >= n then map
            else loop(i + 1, map.updated(chunk(i * 2).asInstanceOf[K], chunk(i * 2 + 1).asInstanceOf[V]))

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
