package kyo

import scala.annotation.nowarn
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
            val arr = new Array[Any](n * 2).asInstanceOf[Array[K | V]]
            var i   = 0
            while i < n do
                arr(i) = chunk(i * 2)
                arr(n + i) = chunk(i * 2 + 1)
                i += 1
            end while
            Dict.fromArrayUnsafe(arr)
        else
            var map = HashMap.empty[K, V]
            var i   = 0
            while i < n do
                map = map.updated(chunk(i * 2).asInstanceOf[K], chunk(i * 2 + 1).asInstanceOf[V])
                i += 1
            Dict.fromHashMap(map)
        end if
    end result

end DictBuilder

object DictBuilder:
    def init[K, V]: DictBuilder[K, V] = new DictBuilder[K, V]

    @nowarn("msg=anonymous")
    inline def initTransform[K, V, K2, V2](inline f: (DictBuilder[K2, V2], K, V) => Unit): ((K, V) => Unit) & DictBuilder[K2, V2] =
        new DictBuilder[K2, V2] with Function2[K, V, Unit]:
            def apply(k: K, v: V): Unit = f(this, k, v)

end DictBuilder
