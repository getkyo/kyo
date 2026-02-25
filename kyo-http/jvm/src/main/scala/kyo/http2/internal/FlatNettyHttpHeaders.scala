package kyo.http2.internal

import io.netty.handler.codec.http.HttpHeaders as NettyHttpHeaders
import io.netty.handler.codec.http.HttpHeadersFactory
import kyo.Chunk
import kyo.discard
import kyo.http2.HttpHeaders

/** Lightweight Netty HttpHeaders backed by a flat interleaved String array.
  *
  * Replaces DefaultHttpHeaders (which allocates a hash table + HeaderEntry nodes per message) with a simple growable array. The backing
  * array is handed off directly to kyo HttpHeaders via toKyoHeaders — zero intermediate copies.
  *
  * All name lookups are linear scans, which is faster than hashing for the typical 5-15 headers per HTTP message (cache-friendly, no
  * hashing overhead).
  *
  * Instances are pooled per-thread via the companion object. After use, call `toKyoHeaders` or `release()` to return the instance to the
  * pool.
  */
final private[http2] class FlatNettyHttpHeaders private (private var arr: Array[String]) extends NettyHttpHeaders:

    private var len = 0

    private def ensureCapacity(needed: Int): Unit =
        if len + needed > arr.length then
            arr = java.util.Arrays.copyOf(arr, arr.length * 2)

    // --- Write path (hot: called by decoder for each header line) ---

    override def add(name: String, value: Object): NettyHttpHeaders =
        ensureCapacity(2)
        arr(len) = name
        arr(len + 1) = value.toString
        len += 2
        this
    end add

    override def add(name: CharSequence, value: Object): NettyHttpHeaders =
        add(name.toString, value)

    override def add(name: String, values: java.lang.Iterable[?]): NettyHttpHeaders =
        val iter = values.iterator()
        while iter.hasNext do discard(add(name, iter.next().asInstanceOf[Object]))
        this
    end add

    override def addInt(name: CharSequence, value: Int): NettyHttpHeaders =
        add(name.toString, Integer.toString(value))

    override def addShort(name: CharSequence, value: Short): NettyHttpHeaders =
        add(name.toString, java.lang.Short.toString(value))

    // --- Read path ---

    override def get(name: String): String =
        var i = 0
        while i < len do
            if arr(i).equalsIgnoreCase(name) then return arr(i + 1)
            i += 2
        null
    end get

    override def getAll(name: String): java.util.List[String] =
        val result = new java.util.ArrayList[String](4)
        var i      = 0
        while i < len do
            if arr(i).equalsIgnoreCase(name) then discard(result.add(arr(i + 1)))
            i += 2
        result
    end getAll

    override def getInt(name: CharSequence): java.lang.Integer =
        val v = get(name.toString)
        if v == null then null else java.lang.Integer.valueOf(v)

    override def getInt(name: CharSequence, defaultValue: Int): Int =
        val v = get(name.toString)
        if v == null then defaultValue
        else
            try v.toInt
            catch case _: NumberFormatException => defaultValue
        end if
    end getInt

    override def getShort(name: CharSequence): java.lang.Short =
        val v = get(name.toString)
        if v == null then null else java.lang.Short.valueOf(v.toShort)

    override def getShort(name: CharSequence, defaultValue: Short): Short =
        val v = get(name.toString)
        if v == null then defaultValue
        else
            try v.toShort
            catch case _: NumberFormatException => defaultValue
        end if
    end getShort

    override def getTimeMillis(name: CharSequence): java.lang.Long =
        val v = get(name.toString)
        if v == null then null
        else
            try java.lang.Long.valueOf(io.netty.handler.codec.http.HttpHeaderDateFormat.get().parse(v).getTime)
            catch case _: Exception => null
        end if
    end getTimeMillis

    override def getTimeMillis(name: CharSequence, defaultValue: Long): Long =
        val v = getTimeMillis(name)
        if v == null then defaultValue else v.longValue()

    override def contains(name: String): Boolean =
        get(name) != null

    // --- Mutation (rare: Content-Length normalization by decoder) ---

    override def set(name: String, value: Object): NettyHttpHeaders =
        discard(remove(name))
        add(name, value)

    override def set(name: String, values: java.lang.Iterable[?]): NettyHttpHeaders =
        discard(remove(name))
        add(name, values)

    override def setInt(name: CharSequence, value: Int): NettyHttpHeaders =
        set(name.toString, Integer.toString(value))

    override def setShort(name: CharSequence, value: Short): NettyHttpHeaders =
        set(name.toString, java.lang.Short.toString(value))

    override def remove(name: String): NettyHttpHeaders =
        var dst = 0
        var src = 0
        while src < len do
            if !arr(src).equalsIgnoreCase(name) then
                if dst != src then
                    arr(dst) = arr(src)
                    arr(dst + 1) = arr(src + 1)
                dst += 2
            end if
            src += 2
        end while
        // Null out removed slots to avoid retaining references
        var i = dst
        while i < len do
            arr(i) = null
            i += 1
        len = dst
        this
    end remove

    override def clear(): NettyHttpHeaders =
        var i = 0
        while i < len do
            arr(i) = null
            i += 1
        len = 0
        this
    end clear

    // --- Iteration ---

    override def iterator(): java.util.Iterator[java.util.Map.Entry[String, String]] =
        new java.util.Iterator[java.util.Map.Entry[String, String]]:
            private var i                 = 0
            override def hasNext: Boolean = i < len
            override def next(): java.util.Map.Entry[String, String] =
                if i >= len then throw new java.util.NoSuchElementException
                val entry = new java.util.AbstractMap.SimpleImmutableEntry(arr(i), arr(i + 1))
                i += 2
                entry
            end next

    override def iteratorCharSequence(): java.util.Iterator[java.util.Map.Entry[CharSequence, CharSequence]] =
        new java.util.Iterator[java.util.Map.Entry[CharSequence, CharSequence]]:
            private var i                 = 0
            override def hasNext: Boolean = i < len
            override def next(): java.util.Map.Entry[CharSequence, CharSequence] =
                if i >= len then throw new java.util.NoSuchElementException
                val entry = new java.util.AbstractMap.SimpleImmutableEntry[CharSequence, CharSequence](arr(i), arr(i + 1))
                i += 2
                entry
            end next

    override def entries(): java.util.List[java.util.Map.Entry[String, String]] =
        val result = new java.util.ArrayList[java.util.Map.Entry[String, String]](len / 2)
        var i      = 0
        while i < len do
            discard(result.add(new java.util.AbstractMap.SimpleImmutableEntry(arr(i), arr(i + 1))))
            i += 2
        result
    end entries

    override def names(): java.util.Set[String] =
        val result = new java.util.LinkedHashSet[String](len / 2)
        var i      = 0
        while i < len do
            discard(result.add(arr(i)))
            i += 2
        result
    end names

    override def size(): Int = len / 2

    override def isEmpty: Boolean = len == 0

    /** Release this instance back to the pool without producing HttpHeaders. */
    def release(): Unit =
        FlatNettyHttpHeaders.releaseInstance(this)

    // --- Zero-copy extraction to kyo HttpHeaders ---

    def toKyoHeaders: HttpHeaders =
        if len == 0 then
            FlatNettyHttpHeaders.releaseInstance(this)
            HttpHeaders.empty
        else
            val exact = java.util.Arrays.copyOf(arr, len)
            FlatNettyHttpHeaders.releaseInstance(this)
            HttpHeaders.fromChunk(Chunk.fromNoCopy(exact))

end FlatNettyHttpHeaders

object FlatNettyHttpHeaders:

    private val defaultCapacity = 32 // 16 headers * 2 (name + value)

    private val pool = new ThreadLocal[java.util.ArrayDeque[FlatNettyHttpHeaders]]:
        override def initialValue() = new java.util.ArrayDeque[FlatNettyHttpHeaders]

    private[http2] def acquire(): FlatNettyHttpHeaders =
        val instance = pool.get().poll()
        if instance != null then
            instance
        else
            new FlatNettyHttpHeaders(new Array[String](defaultCapacity))
        end if
    end acquire

    private[http2] def releaseInstance(h: FlatNettyHttpHeaders): Unit =
        // Null out references to avoid retaining strings while pooled
        var i = 0
        while i < h.len do
            h.arr(i) = null
            i += 1
        h.len = 0
        if h.arr.length <= 128 then // Don't pool instances with oversized arrays
            discard(pool.get().add(h))
    end releaseInstance

    val factory: HttpHeadersFactory = new HttpHeadersFactory:
        override def newHeaders(): NettyHttpHeaders      = acquire()
        override def newEmptyHeaders(): NettyHttpHeaders = acquire()

end FlatNettyHttpHeaders
