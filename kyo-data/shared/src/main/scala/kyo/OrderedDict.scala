package kyo

import scala.annotation.tailrec
import scala.collection.immutable.TreeSeqMap
import scala.reflect.ClassTag

/** An immutable map that preserves the insertion order of its keys at every size. OrderedDict uses a dual representation optimized for both
  * small and large collections:
  *
  *   - For up to 8 entries, keys and values are stored in a flat [[Span]] with linear-scan lookup, avoiding hashing overhead and providing
  *     cache-friendly access.
  *   - For more than 8 entries, a `TreeSeqMap` ordered by insertion is used, keeping key order defined while still offering efficient
  *     lookup.
  *
  * Order is insertion order (the `LinkedHashMap` default): a new key appends at the end, updating an existing key replaces its value and
  * keeps the key at its existing position, and removing a key preserves the relative order of the remaining keys. Re-adding a previously
  * removed key appends it at the end as a fresh insertion. This differs from [[Dict]], whose large hash-backed representation leaves
  * iteration order unspecified.
  *
  * Operations are designed to avoid tuple allocations for key-value pairs. Iteration, transformation, and lookup methods accept separate
  * key and value parameters rather than `(K, V)` tuples, eliminating boxing overhead in hot paths.
  *
  * #### Creation
  *
  * ```scala
  * val empty = OrderedDict.empty[String, Int]
  * val doc   = OrderedDict("a" -> 1, "b" -> 2, "c" -> 3)
  * val fromMap = OrderedDict.from(Map("x" -> 10))
  * ```
  *
  * #### Access
  *
  * ```scala
  * doc("a")          // 1
  * doc.get("z")      // Maybe.empty
  * doc.contains("b") // true
  * ```
  *
  * #### Modification
  *
  * All modifications return a new OrderedDict, leaving the original unchanged:
  *
  * ```scala
  * doc.update("a", 10)  // OrderedDict("a" -> 10, "b" -> 2, "c" -> 3)
  * doc.remove("b")      // OrderedDict("a" -> 1, "c" -> 3)
  * doc ++ OrderedDict("d" -> 4)
  * ```
  *
  * #### Iteration and Transformation
  *
  * ```scala
  * doc.foreach((k, v) => println(s"$k -> $v"))
  * doc.map((k, v) => (k.toUpperCase, v * 2))
  * doc.filter((k, v) => v > 1)
  * doc.foldLeft(0)((acc, k, v) => acc + v)
  * ```
  *
  * Because OrderedDict is an opaque type, it does not provide a `CanEqual` instance. Use the `is` method for structural equality comparison;
  * equality is order-independent.
  *
  * OrderedDict uses an unboxed representation through Scala 3's opaque types, so wrapping and unwrapping incurs no runtime overhead.
  *
  * @tparam K
  *   the type of keys
  * @tparam V
  *   the type of values
  * @see
  *   [[OrderedDictBuilder]] for incremental construction
  */
opaque type OrderedDict[K, V] = Span[K | V] | TreeSeqMap[K, V]

object OrderedDict:

    private[kyo] val threshold = 8
    private val sentinel       = new AnyRef

    /** Returns an empty OrderedDict. */
    def empty[K, V]: OrderedDict[K, V] = Span.empty[Any].asInstanceOf[Span[K | V]]

    /** Creates an OrderedDict from the given key-value pairs in insertion order. Duplicate keys resolve to the last value while keeping the
      * first occurrence's position.
      *
      * @param entries
      *   the key-value pairs
      * @return
      *   a new OrderedDict containing the entries
      */
    def apply[K, V](entries: (K, V)*): OrderedDict[K, V] =
        if entries.isEmpty then empty
        else
            val b = OrderedDictBuilder.init[K, V]
            entries.foreach((k, v) => discard(b.add(k, v)))
            b.result()

    /** Creates an OrderedDict from an existing Map, preserving the source map's iteration order.
      *
      * @param map
      *   the source map
      * @return
      *   a new OrderedDict containing the map's entries
      */
    def from[K, V](map: Map[K, V]): OrderedDict[K, V] =
        if map.isEmpty then empty
        else
            val b = OrderedDictBuilder.initTransform[K, V, K, V]((b, k, v) => discard(b.add(k, v)))
            map.foreachEntry(b)
            b.result()

    extension [K, V](self: OrderedDict[K, V])

        /** Returns the number of entries in this OrderedDict. */
        def size: Int =
            reduce(
                span => Span.size(span) / 2,
                map => map.size
            )

        /** Returns true if this OrderedDict contains no entries. */
        def isEmpty: Boolean =
            reduce(
                span => Span.isEmpty(span),
                map => map.isEmpty
            )

        /** Returns true if this OrderedDict contains at least one entry. */
        def nonEmpty: Boolean =
            reduce(
                span => Span.nonEmpty(span),
                map => map.nonEmpty
            )

        /** Returns the value associated with the given key.
          *
          * @param key
          *   the key to look up
          * @return
          *   the value associated with `key`
          * @throws NoSuchElementException
          *   if the key is not present
          */
        def apply(key: K): V =
            reduce(
                span =>
                    val n = Span.size(span) / 2
                    @tailrec def loop(i: Int): V =
                        if i >= n then throw new NoSuchElementException(key.toString)
                        else
                            val k = Span.apply(span)(i)
                            if (k.asInstanceOf[AnyRef] eq key.asInstanceOf[AnyRef]) || k.equals(key) then
                                Span.apply(span)(n + i).asInstanceOf[V]
                            else
                                loop(i + 1)
                            end if
                    loop(0)
                ,
                map => map(key)
            )

        /** Optionally returns the value associated with the given key.
          *
          * @param key
          *   the key to look up
          * @return
          *   a [[Maybe]] containing the value if found, or `Absent` otherwise
          */
        def get(key: K): Maybe[V] =
            reduce(
                span =>
                    val n  = Span.size(span) / 2
                    val kr = key.asInstanceOf[AnyRef]
                    @tailrec def loop(i: Int): Maybe[V] =
                        if i >= n then Maybe.empty
                        else
                            val k = Span.apply(span)(i)
                            if (k.asInstanceOf[AnyRef] eq kr) || k.equals(key) then Maybe(Span.apply(span)(n + i).asInstanceOf[V])
                            else loop(i + 1)
                    loop(0)
                ,
                map =>
                    val r = map.getOrElse(key, sentinel)
                    if r.asInstanceOf[AnyRef] eq sentinel then Maybe.empty
                    else Maybe(r.asInstanceOf[V])
            )

        /** Returns the value associated with the given key, or the default value if not found. */
        inline def getOrElse(key: K, inline default: => V): V =
            get(key) match
                case Present(v) => v
                case _          => default

        /** Returns true if this OrderedDict contains the given key. */
        def contains(key: K): Boolean =
            get(key).nonEmpty

        /** Returns a new OrderedDict with the given key-value pair added or updated. A new key appends at the end; an existing key has its
          * value replaced in place, keeping its position.
          */
        def update(key: K, value: V): OrderedDict[K, V] =
            reduce(
                span =>
                    val n   = Span.size(span) / 2
                    val src = Span.toArrayUnsafe(span)
                    val kr  = key.asInstanceOf[AnyRef]
                    @tailrec def indexOf(i: Int): Int =
                        if i >= n then -1
                        else
                            val k = Span.apply(span)(i)
                            if (k.asInstanceOf[AnyRef] eq kr) || k.equals(key) then i
                            else indexOf(i + 1)
                    val idx = indexOf(0)
                    if idx >= 0 then
                        val arr = new Array[Any](n * 2).asInstanceOf[Array[K | V]]
                        System.arraycopy(src, 0, arr, 0, n * 2)
                        arr(n + idx) = value
                        Span.fromUnsafe(arr)
                    else
                        val b = OrderedDictBuilder.init[K, V]
                        @tailrec def loop(i: Int): Unit =
                            if i < n then
                                discard(b.add(Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V]))
                                loop(i + 1)
                        loop(0)
                        discard(b.add(key, value))
                        b.result()
                    end if
                ,
                map => map.updated(key, value)
            )

        /** Returns a new OrderedDict with the given key removed, preserving the relative order of the remaining keys. If the key is not
          * present, returns this OrderedDict unchanged.
          */
        def remove(key: K): OrderedDict[K, V] =
            reduce(
                span =>
                    val n   = Span.size(span) / 2
                    val src = Span.toArrayUnsafe(span)
                    @tailrec def indexOf(i: Int): Int =
                        if i >= n then -1
                        else
                            val k = Span.apply(span)(i)
                            if (k.asInstanceOf[AnyRef] eq key.asInstanceOf[AnyRef]) || k.equals(key) then i
                            else indexOf(i + 1)
                    val idx = indexOf(0)
                    if idx < 0 then self
                    else if n == 1 then OrderedDict.empty
                    else
                        val arr = new Array[Any]((n - 1) * 2).asInstanceOf[Array[K | V]]
                        System.arraycopy(src, 0, arr, 0, idx)
                        System.arraycopy(src, idx + 1, arr, idx, n - 1 - idx)
                        System.arraycopy(src, n, arr, n - 1, idx)
                        System.arraycopy(src, n + idx + 1, arr, n - 1 + idx, n - 1 - idx)
                        Span.fromUnsafe(arr)
                    end if
                ,
                map => map.removed(key)
            )

        /** Returns a new OrderedDict containing all entries from both this OrderedDict and `other`. Keys present in both keep this map's
          * position and take `other`'s value; keys only in `other` append at the end in `other`'s order.
          */
        def concat(other: OrderedDict[K, V]): OrderedDict[K, V] =
            if other.isEmpty then self
            else if isEmpty then other
            else
                val b = OrderedDictBuilder.init[K, V]
                foreach((k, v) => discard(b.add(k, v)))
                other.foreach((k, v) => discard(b.add(k, v)))
                b.result()
        end concat

        /** Alias for `concat`. */
        infix def ++(other: OrderedDict[K, V]): OrderedDict[K, V] = concat(other)

        /** Applies the given function to each key-value pair in insertion order. */
        def foreach(fn: (K, V) => Unit): Unit =
            reduce(
                span =>
                    val n = Span.size(span) / 2
                    @tailrec def loop(i: Int): Unit =
                        if i < n then
                            fn(Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V])
                            loop(i + 1)
                    loop(0)
                ,
                map => map.foreachEntry(fn)
            )

        /** Applies the given function to each key in insertion order. */
        inline def foreachKey(inline fn: K => Unit): Unit =
            reduce(
                span =>
                    val n = Span.size(span) / 2
                    @tailrec def loop(i: Int): Unit =
                        if i < n then
                            fn(Span.apply(span)(i).asInstanceOf[K])
                            loop(i + 1)
                    loop(0)
                ,
                map => map.foreachEntry((k, _) => fn(k))
            )

        /** Applies the given function to each value in insertion order. */
        inline def foreachValue(inline fn: V => Unit): Unit =
            reduce(
                span =>
                    val n = Span.size(span) / 2
                    @tailrec def loop(i: Int): Unit =
                        if i < n then
                            fn(Span.apply(span)(n + i).asInstanceOf[V])
                            loop(i + 1)
                    loop(0)
                ,
                map => map.foreachEntry((_, v) => fn(v))
            )

        // forall, exists, count, find use while loops due to a Scala 3 @tailrec-inside-inline bug

        /** Returns true if the predicate holds for all entries. */
        inline def forall(inline fn: (K, V) => Boolean): Boolean =
            reduce(
                span =>
                    val n      = Span.size(span) / 2
                    var i      = 0
                    var result = true
                    while i < n && result do
                        result = fn(Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V])
                        i += 1
                    result
                ,
                map =>
                    var result = true
                    map.foreachEntry { (k, v) =>
                        if result then result = fn(k, v)
                    }
                    result
            )

        /** Returns true if the predicate holds for at least one entry. */
        inline def exists(inline fn: (K, V) => Boolean): Boolean =
            reduce(
                span =>
                    val n      = Span.size(span) / 2
                    var i      = 0
                    var result = false
                    while i < n && !result do
                        result = fn(Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V])
                        i += 1
                    result
                ,
                map =>
                    var result = false
                    map.foreachEntry { (k, v) =>
                        if !result then result = fn(k, v)
                    }
                    result
            )

        /** Returns the number of entries satisfying the predicate. */
        inline def count(inline fn: (K, V) => Boolean): Int =
            reduce(
                span =>
                    val n   = Span.size(span) / 2
                    var i   = 0
                    var acc = 0
                    while i < n do
                        if fn(Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V]) then acc += 1
                        i += 1
                    acc
                ,
                map =>
                    var acc = 0
                    map.foreachEntry { (k, v) =>
                        if fn(k, v) then acc += 1
                    }
                    acc
            )

        /** Returns the first entry satisfying the predicate, or `Absent` if none is found. */
        inline def find(inline fn: (K, V) => Boolean): Maybe[(K, V)] =
            reduce(
                span =>
                    val n                     = Span.size(span) / 2
                    var i                     = 0
                    var result: Maybe[(K, V)] = Maybe.empty
                    while i < n && result.isEmpty do
                        val k = Span.apply(span)(i).asInstanceOf[K]
                        val v = Span.apply(span)(n + i).asInstanceOf[V]
                        if fn(k, v) then result = Maybe((k, v))
                        i += 1
                    end while
                    result
                ,
                map =>
                    var result: Maybe[(K, V)] = Maybe.empty
                    map.foreachEntry { (k, v) =>
                        if result.isEmpty && fn(k, v) then result = Maybe((k, v))
                    }
                    result
            )

        /** Applies a binary operator to a start value and all entries, going left to right in insertion order. The function receives the
          * accumulator, key, and value as separate arguments to avoid tuple allocation.
          */
        def foldLeft[B](z: B)(fn: (B, K, V) => B): B =
            reduce(
                span =>
                    val n = Span.size(span) / 2
                    @tailrec def loop(i: Int, acc: B): B =
                        if i >= n then acc
                        else loop(i + 1, fn(acc, Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V]))
                    loop(0, z)
                ,
                map =>
                    var acc = z
                    map.foreachEntry { (k, v) => acc = fn(acc, k, v) }
                    acc
            )

        /** Transforms each entry by applying the given function, returning a new OrderedDict with the resulting key-value pairs in source
          * insertion order.
          */
        inline def map[K2, V2](inline fn: (K, V) => (K2, V2)): OrderedDict[K2, V2] =
            val b = OrderedDictBuilder.init[K2, V2]
            foreach { (k, v) =>
                val (k2, v2) = fn(k, v)
                discard(b.add(k2, v2))
            }
            b.result()
        end map

        /** Transforms each entry into an OrderedDict and merges the results in source insertion order. */
        inline def flatMap[K2, V2](inline fn: (K, V) => OrderedDict[K2, V2]): OrderedDict[K2, V2] =
            val b = OrderedDictBuilder.init[K2, V2]
            foreach { (k, v) =>
                fn(k, v).foreach { (k2, v2) =>
                    discard(b.add(k2, v2))
                }
            }
            b.result()
        end flatMap

        /** Returns a new OrderedDict containing only entries that satisfy the predicate, preserving their relative order. */
        inline def filter(inline fn: (K, V) => Boolean): OrderedDict[K, V] =
            reduce(
                span =>
                    val n   = Span.size(span) / 2
                    val src = Span.toArrayUnsafe(span)
                    val arr = new Array[Any](n * 2).asInstanceOf[Array[K | V]]
                    var j   = 0
                    var i   = 0
                    while i < n do
                        if fn(Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V]) then
                            arr(j) = src(i)
                            arr(n + j) = src(n + i)
                            j += 1
                        end if
                        i += 1
                    end while
                    trimFiltered(self, arr, n, j)
                ,
                map =>
                    val b = OrderedDictBuilder.initTransform[K, V, K, V] { (b, k, v) =>
                        if fn(k, v) then discard(b.add(k, v))
                    }
                    map.foreachEntry(b)
                    b.result()
            )
        end filter

        /** Returns a new OrderedDict containing only entries that do not satisfy the predicate. */
        inline def filterNot(inline fn: (K, V) => Boolean): OrderedDict[K, V] =
            filter((k, v) => !fn(k, v))

        /** Applies the partial function to each entry and collects the results into a new OrderedDict in source insertion order. */
        def collect[K2, V2](pf: PartialFunction[(K, V), (K2, V2)]): OrderedDict[K2, V2] =
            val b        = OrderedDictBuilder.init[K2, V2]
            val fallback = OrderedDict.sentinel
            foreach { (k, v) =>
                val r = pf.applyOrElse((k, v), _ => fallback)
                if r.asInstanceOf[AnyRef] ne fallback then
                    val (k2, v2) = r.asInstanceOf[(K2, V2)]
                    discard(b.add(k2, v2))
            }
            b.result()
        end collect

        /** Returns a new OrderedDict with the same keys and values transformed by the given function. */
        inline def mapValues[V2](inline fn: V => V2): OrderedDict[K, V2] =
            reduce(
                span =>
                    val n   = Span.size(span) / 2
                    val src = Span.toArrayUnsafe(span)
                    val arr = new Array[Any](n * 2)
                    System.arraycopy(src, 0, arr, 0, n)
                    var i = 0
                    while i < n do
                        arr(n + i) = fn(Span.apply(span)(n + i).asInstanceOf[V])
                        i += 1
                    Span.fromUnsafe(arr.asInstanceOf[Array[K | V2]]).asInstanceOf[OrderedDict[K, V2]]
                ,
                map =>
                    val b = OrderedDictBuilder.initTransform[K, V, K, V2] { (b, k, v) =>
                        discard(b.add(k, fn(v)))
                    }
                    map.foreachEntry(b)
                    b.result()
            )
        end mapValues

        /** Returns all keys as a [[Span]] in insertion order. */
        def keys(using ClassTag[K]): Span[K] =
            reduce(
                span =>
                    val n   = Span.size(span) / 2
                    val arr = new Array[K](n)
                    @tailrec def loop(i: Int): Unit =
                        if i < n then
                            arr(i) = Span.apply(span)(i).asInstanceOf[K]
                            loop(i + 1)
                    loop(0)
                    Span.fromUnsafe(arr)
                ,
                map =>
                    val arr = new Array[K](map.size)
                    var i   = 0
                    map.foreachEntry { (k, _) =>
                        arr(i) = k; i += 1
                    }
                    Span.fromUnsafe(arr)
            )

        /** Returns all values as a [[Span]] in insertion order. */
        def values(using ClassTag[V]): Span[V] =
            reduce(
                span =>
                    val n   = Span.size(span) / 2
                    val arr = new Array[V](n)
                    @tailrec def loop(i: Int): Unit =
                        if i < n then
                            arr(i) = Span.apply(span)(n + i).asInstanceOf[V]
                            loop(i + 1)
                    loop(0)
                    Span.fromUnsafe(arr)
                ,
                map =>
                    val arr = new Array[V](map.size)
                    var i   = 0
                    map.foreachEntry { (_, v) =>
                        arr(i) = v; i += 1
                    }
                    Span.fromUnsafe(arr)
            )

        /** Builds a `Chunk[(K, V)]` of all entries in insertion order at all sizes. */
        def toChunk: Chunk[(K, V)] =
            reduce(
                span =>
                    val n = Span.size(span) / 2
                    if n == 0 then Chunk.empty
                    else
                        val b = Chunk.newBuilder[(K, V)]
                        @tailrec def loop(i: Int): Unit =
                            if i < n then
                                b += ((Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V]))
                                loop(i + 1)
                        loop(0)
                        b.result()
                    end if
                ,
                map =>
                    val b = Chunk.newBuilder[(K, V)]
                    map.foreachEntry((k, v) => b += ((k, v)))
                    b.result()
            )

        /** Converts this OrderedDict to an immutable `Map`. The stdlib `Map` type does not carry order in its interface; use `toChunk`,
          * `keys`, or `foreach` when order matters.
          */
        def toMap: Map[K, V] =
            reduce(
                span =>
                    val n = Span.size(span) / 2
                    @tailrec def loop(i: Int, map: TreeSeqMap[K, V]): TreeSeqMap[K, V] =
                        if i >= n then map
                        else loop(i + 1, map.updated(Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V]))
                    loop(0, TreeSeqMap.empty[K, V])
                ,
                map => map
            )

        /** Tests structural equality with another OrderedDict. This is the primary way to compare OrderedDicts, since OrderedDict does not
          * provide a `CanEqual` instance due to its opaque type representation. Equality is order-independent.
          */
        def is(other: OrderedDict[K, V])(using CanEqual[K, K], CanEqual[V, V]): Boolean =
            size == other.size && forall { (k, v) =>
                other.get(k) match
                    case Present(v2) => v.equals(v2)
                    case _           => false
            }

        /** Formats all entries as a string with the given separator, in insertion order. Each entry is rendered as `key -> value`. */
        def mkString(separator: String): String =
            val sb    = new java.lang.StringBuilder(self.size * 4 + 8)
            var first = true
            foreach { (k, v) =>
                if !first then discard(sb.append(separator))
                discard(sb.append(k).append(" -> ").append(v))
                first = false
            }
            sb.toString
        end mkString

        /** Formats all entries as a string with the given start, separator, and end. */
        def mkString(start: String, sep: String, end: String): String =
            start + mkString(sep) + end

        /** Formats all entries as a concatenated string with no separator. */
        def mkString: String = mkString("")

        private inline def reduce[B](
            inline small: Span[K | V] => B,
            inline large: TreeSeqMap[K, V] => B
        ): B =
            if self.isInstanceOf[TreeSeqMap[?, ?]] then large(self.asInstanceOf[TreeSeqMap[K, V]])
            else small(self.asInstanceOf[Span[K | V]])

    end extension

    private[kyo] def fromArrayUnsafe[K, V](arr: Array[K | V]): OrderedDict[K, V] =
        Span.fromUnsafe(arr)

    private[kyo] def fromTreeSeqMap[K, V](map: TreeSeqMap[K, V]): OrderedDict[K, V] =
        map

    private def trimFiltered[K, V](original: OrderedDict[K, V], arr: Array[K | V], n: Int, j: Int): OrderedDict[K, V] =
        if j == n then original
        else if j == 0 then empty
        else
            val trimmed = new Array[Any](j * 2).asInstanceOf[Array[K | V]]
            System.arraycopy(arr, 0, trimmed, 0, j)
            System.arraycopy(arr, n, trimmed, j, j)
            Span.fromUnsafe(trimmed)

    /** Parses comma-separated key=value pairs into an OrderedDict in the order they appear. */
    given [K, V](using rk: Flag.Reader.Scalar[K], rv: Flag.Reader.Scalar[V]): Flag.Reader[OrderedDict[K, V]] with
        def apply(s: String): Either[Throwable, OrderedDict[K, V]] =
            if s.trim.isEmpty then Right(OrderedDict.empty[K, V])
            else
                val entries          = s.split(",")
                val builder          = OrderedDictBuilder.init[K, V]
                var error: Throwable = null
                var i                = 0
                while i < entries.length && (error eq null) do
                    val trimmed = entries(i).trim
                    val eqIdx   = trimmed.indexOf('=')
                    if eqIdx < 0 then
                        error = new IllegalArgumentException(s"Invalid OrderedDict entry (missing '='): $trimmed")
                    else
                        rk(trimmed.substring(0, eqIdx).trim) match
                            case Left(e) => error = e
                            case Right(key) =>
                                rv(trimmed.substring(eqIdx + 1).trim) match
                                    case Left(e)      => error = e
                                    case Right(value) => discard(builder.add(key, value))
                    end if
                    i += 1
                end while
                if error ne null then Left(error)
                else Right(builder.result())
        end apply

        def typeName: String = s"OrderedDict[${rk.typeName}, ${rv.typeName}]"
    end given

end OrderedDict
