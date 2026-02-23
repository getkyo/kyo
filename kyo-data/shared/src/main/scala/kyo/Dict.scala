package kyo

import scala.annotation.tailrec
import scala.collection.immutable.HashMap
import scala.reflect.ClassTag

/** An immutable dictionary mapping keys of type `K` to values of type `V`. Dict uses a dual representation optimized for both small and
  * large collections:
  *
  *   - For up to 8 entries, keys and values are stored in a flat [[Span]] with linear-scan lookup, avoiding hashing overhead and providing
  *     cache-friendly access.
  *   - For more than 8 entries, a `HashMap` is used for O(1) amortized lookups.
  *
  * This adaptive strategy makes Dict efficient across a wide range of sizes. Small dictionaries benefit from minimal allocation and fast
  * sequential scans, while large dictionaries automatically transition to hash-based storage.
  *
  * Operations are designed to avoid tuple allocations for key-value pairs. Iteration, transformation, and lookup methods accept separate
  * key and value parameters rather than `(K, V)` tuples, eliminating boxing overhead in hot paths.
  *
  * =Creation=
  * {{{
  * val empty = Dict.empty[String, Int]
  * val dict  = Dict("a" -> 1, "b" -> 2, "c" -> 3)
  * val fromMap = Dict.from(Map("x" -> 10))
  * }}}
  *
  * =Access=
  * {{{
  * dict("a")          // 1
  * dict.get("z")      // Maybe.empty
  * dict.contains("b") // true
  * }}}
  *
  * =Modification=
  * All modifications return a new Dict, leaving the original unchanged:
  * {{{
  * dict.update("a", 10)  // Dict("a" -> 10, "b" -> 2, "c" -> 3)
  * dict.remove("b")      // Dict("a" -> 1, "c" -> 3)
  * dict ++ Dict("d" -> 4)
  * }}}
  *
  * =Iteration and Transformation=
  * {{{
  * dict.foreach((k, v) => println(s"$k -> $v"))
  * dict.map((k, v) => (k.toUpperCase, v * 2))
  * dict.filter((k, v) => v > 1)
  * dict.foldLeft(0)((acc, k, v) => acc + v)
  * }}}
  *
  * Because Dict is an opaque type, it does not provide a `CanEqual` instance. Use the `is` method for structural equality comparison.
  *
  * Dict uses an unboxed representation through Scala 3's opaque types, so wrapping and unwrapping incurs no runtime overhead.
  *
  * @tparam K
  *   the type of keys
  * @tparam V
  *   the type of values
  * @see
  *   [[DictBuilder]] for incremental construction
  */
opaque type Dict[K, V] = Span[K | V] | HashMap[K, V]

object Dict:

    private[kyo] val threshold = 8
    private val sentinel       = new AnyRef

    /** Returns an empty Dict. */
    def empty[K, V]: Dict[K, V] = Span.empty[Any].asInstanceOf[Span[K | V]]

    /** Creates a Dict from the given key-value pairs. Duplicate keys are resolved by keeping the last value.
      *
      * @param entries
      *   the key-value pairs
      * @return
      *   a new Dict containing the entries
      */
    def apply[K, V](entries: (K, V)*): Dict[K, V] =
        if entries.isEmpty then empty
        else
            val b = DictBuilder.init[K, V]
            entries.foreach((k, v) => discard(b.add(k, v)))
            b.result()

    /** Creates a Dict from an existing Map. If the Map is already a `HashMap`, it is used directly without copying for collections above
      * the small-dict threshold.
      *
      * @param map
      *   the source map
      * @return
      *   a new Dict containing the map's entries
      */
    def from[K, V](map: Map[K, V]): Dict[K, V] =
        if map.isEmpty then empty
        else
            map match
                case hm: HashMap[K, V] @unchecked => hm
                case _ =>
                    val b = DictBuilder.initTransform[K, V, K, V]((b, k, v) => discard(b.add(k, v)))
                    map.foreachEntry(b)
                    b.result()

    extension [K, V](self: Dict[K, V])

        /** Returns the number of entries in this Dict. */
        def size: Int =
            reduce(
                span => Span.size(span) / 2,
                map => map.size
            )

        /** Returns true if this Dict contains no entries. */
        def isEmpty: Boolean =
            reduce(
                span => Span.isEmpty(span),
                map => map.isEmpty
            )

        /** Returns true if this Dict contains at least one entry. */
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

        /** Returns true if this Dict contains the given key. */
        def contains(key: K): Boolean =
            get(key).nonEmpty

        /** Returns a new Dict with the given key-value pair added or updated. If the key already exists, its value is replaced. */
        def update(key: K, value: V): Dict[K, V] =
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
                        val b = DictBuilder.init[K, V]
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

        /** Returns a new Dict with the given key removed. If the key is not present, returns this Dict unchanged. */
        def remove(key: K): Dict[K, V] =
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
                    else if n == 1 then Dict.empty
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

        /** Returns a new Dict containing all entries from both this Dict and `other`. If both contain the same key, the value from `other`
          * takes precedence.
          */
        def concat(other: Dict[K, V]): Dict[K, V] =
            if other.isEmpty then self
            else if isEmpty then other
            else
                val b = DictBuilder.init[K, V]
                foreach((k, v) => discard(b.add(k, v)))
                other.foreach((k, v) => discard(b.add(k, v)))
                b.result()
        end concat

        /** Alias for `concat`. */
        infix def ++(other: Dict[K, V]): Dict[K, V] = concat(other)

        /** Applies the given function to each key-value pair in this Dict. */
        inline def foreach(inline fn: (K, V) => Unit): Unit =
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

        /** Applies the given function to each key in this Dict. */
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

        /** Applies the given function to each value in this Dict. */
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

        /** Applies a binary operator to a start value and all entries, going left to right. The function receives the accumulator, key, and
          * value as separate arguments to avoid tuple allocation.
          */
        inline def foldLeft[B](z: B)(inline fn: (B, K, V) => B): B =
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

        /** Transforms each entry by applying the given function, returning a new Dict with the resulting key-value pairs. */
        inline def map[K2, V2](inline fn: (K, V) => (K2, V2)): Dict[K2, V2] =
            val b = DictBuilder.init[K2, V2]
            foreach { (k, v) =>
                val (k2, v2) = fn(k, v)
                discard(b.add(k2, v2))
            }
            b.result()
        end map

        /** Transforms each entry into a Dict and merges the results. */
        inline def flatMap[K2, V2](inline fn: (K, V) => Dict[K2, V2]): Dict[K2, V2] =
            val b = DictBuilder.init[K2, V2]
            foreach { (k, v) =>
                fn(k, v).foreach { (k2, v2) =>
                    discard(b.add(k2, v2))
                }
            }
            b.result()
        end flatMap

        /** Returns a new Dict containing only entries that satisfy the predicate. */
        inline def filter(inline fn: (K, V) => Boolean): Dict[K, V] =
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
                    val b = DictBuilder.initTransform[K, V, K, V] { (b, k, v) =>
                        if fn(k, v) then discard(b.add(k, v))
                    }
                    map.foreachEntry(b)
                    b.result()
            )
        end filter

        /** Returns a new Dict containing only entries that do not satisfy the predicate. */
        inline def filterNot(inline fn: (K, V) => Boolean): Dict[K, V] =
            filter((k, v) => !fn(k, v))

        /** Applies the partial function to each entry and collects the results into a new Dict. */
        def collect[K2, V2](pf: PartialFunction[(K, V), (K2, V2)]): Dict[K2, V2] =
            val b        = DictBuilder.init[K2, V2]
            val fallback = Dict.sentinel
            foreach { (k, v) =>
                val r = pf.applyOrElse((k, v), _ => fallback)
                if r.asInstanceOf[AnyRef] ne fallback then
                    val (k2, v2) = r.asInstanceOf[(K2, V2)]
                    discard(b.add(k2, v2))
            }
            b.result()
        end collect

        /** Returns a new Dict with the same keys and values transformed by the given function. */
        inline def mapValues[V2](inline fn: V => V2): Dict[K, V2] =
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
                    Span.fromUnsafe(arr.asInstanceOf[Array[K | V2]]).asInstanceOf[Dict[K, V2]]
                ,
                map =>
                    val b = DictBuilder.initTransform[K, V, K, V2] { (b, k, v) =>
                        discard(b.add(k, fn(v)))
                    }
                    map.foreachEntry(b)
                    b.result()
            )
        end mapValues

        /** Returns all keys as a [[Span]]. */
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

        /** Returns all values as a [[Span]]. */
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

        /** Converts this Dict to an immutable `Map`. */
        def toMap: Map[K, V] =
            reduce(
                span =>
                    val n = Span.size(span) / 2
                    @tailrec def loop(i: Int, map: HashMap[K, V]): HashMap[K, V] =
                        if i >= n then map
                        else loop(i + 1, map.updated(Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V]))
                    loop(0, HashMap.empty)
                ,
                map => map
            )

        /** Tests structural equality with another Dict. This is the primary way to compare Dicts, since Dict does not provide a `CanEqual`
          * instance due to its opaque type representation.
          */
        def is(other: Dict[K, V])(using CanEqual[K, K], CanEqual[V, V]): Boolean =
            size == other.size && forall { (k, v) =>
                other.get(k) match
                    case Present(v2) => v.equals(v2)
                    case _           => false
            }

        /** Formats all entries as a string with the given separator. Each entry is rendered as `key -> value`. */
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
            inline large: HashMap[K, V] => B
        ): B =
            if self.isInstanceOf[HashMap[?, ?]] then large(self.asInstanceOf[HashMap[K, V]])
            else small(self.asInstanceOf[Span[K | V]])

    end extension

    private[kyo] def fromArrayUnsafe[K, V](arr: Array[K | V]): Dict[K, V] =
        Span.fromUnsafe(arr)

    private[kyo] def fromHashMap[K, V](map: HashMap[K, V]): Dict[K, V] =
        map

    private def trimFiltered[K, V](original: Dict[K, V], arr: Array[K | V], n: Int, j: Int): Dict[K, V] =
        if j == n then original
        else if j == 0 then empty
        else
            val trimmed = new Array[Any](j * 2).asInstanceOf[Array[K | V]]
            System.arraycopy(arr, 0, trimmed, 0, j)
            System.arraycopy(arr, n, trimmed, j, j)
            Span.fromUnsafe(trimmed)

end Dict
