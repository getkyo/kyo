package kyo

import scala.annotation.tailrec
import scala.collection.immutable.HashMap
import scala.reflect.ClassTag

opaque type Dict[K, V] = Span[K | V] | HashMap[K, V]

object Dict:

    private[kyo] val threshold = 8
    private val sentinel       = new AnyRef

    def empty[K, V]: Dict[K, V] = Span.empty[Any].asInstanceOf[Span[K | V]]

    def apply[K, V](entries: (K, V)*): Dict[K, V] =
        if entries.isEmpty then empty
        else
            val b = DictBuilder.init[K, V]
            entries.foreach((k, v) => discard(b.add(k, v)))
            b.result()

    def from[K, V](map: Map[K, V]): Dict[K, V] =
        if map.isEmpty then empty
        else
            map match
                case hm: HashMap[K, V] @unchecked => hm
                case _ =>
                    val b = DictBuilder.init[K, V]
                    map.foreach((k, v) => discard(b.add(k, v)))
                    b.result()

    private[kyo] def fromArrayUnsafe[K, V](arr: Array[K | V]): Dict[K, V] =
        Span.fromUnsafe(arr)

    private[kyo] def fromHashMap[K, V](map: HashMap[K, V]): Dict[K, V] =
        map

    extension [K, V](self: Dict[K, V])

        // Size

        def size: Int = reduce(
            span => Span.size(span) / 2,
            map => map.size
        )

        def isEmpty: Boolean = reduce(
            span => Span.isEmpty(span),
            map => map.isEmpty
        )

        def nonEmpty: Boolean = reduce(
            span => Span.nonEmpty(span),
            map => map.nonEmpty
        )

        // Access

        def apply(key: K): V = reduce(
            span =>
                val n  = Span.size(span) / 2
                val kr = key.asInstanceOf[AnyRef]
                @tailrec def loop(i: Int): V =
                    if i >= n then throw new NoSuchElementException(key.toString)
                    else
                        val k = Span.apply(span)(i)
                        if (k.asInstanceOf[AnyRef] eq kr) || k.equals(key) then Span.apply(span)(n + i).asInstanceOf[V]
                        else loop(i + 1)
                loop(0)
            ,
            map => map(key)
        )

        def get(key: K): Maybe[V] =
            val r = lookupOrSentinel(key)
            if r.asInstanceOf[AnyRef] eq sentinel then Maybe.empty
            else Maybe(r.asInstanceOf[V])
        end get

        inline def getOrElse(key: K, inline default: => V): V =
            lookup(key, v => v, default)

        def contains(key: K): Boolean =
            lookupOrSentinel(key).asInstanceOf[AnyRef] ne sentinel

        // Modification

        def update(key: K, value: V): Dict[K, V] = reduce(
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
                    var i = 0
                    while i < n do
                        discard(b.add(Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V]))
                        i += 1
                    discard(b.add(key, value))
                    b.result()
                end if
            ,
            map => map.updated(key, value)
        )

        def remove(key: K): Dict[K, V] = reduce(
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

        def concat(other: Dict[K, V]): Dict[K, V] =
            reduce(
                span1 =>
                    other.reduce(
                        span2 =>
                            val n1    = Span.size(span1) / 2
                            val n2    = Span.size(span2) / 2
                            val total = n1 + n2
                            if total <= threshold then
                                val src1 = Span.toArrayUnsafe(span1)
                                val src2 = Span.toArrayUnsafe(span2)
                                val arr  = new Array[Any](total * 2).asInstanceOf[Array[K | V]]
                                System.arraycopy(src1, 0, arr, 0, n1)
                                System.arraycopy(src2, 0, arr, n1, n2)
                                System.arraycopy(src1, n1, arr, total, n1)
                                System.arraycopy(src2, n2, arr, total + n1, n2)
                                Span.fromUnsafe(arr)
                            else
                                concatViaBuilder(other)
                            end if
                        ,
                        _ => concatViaBuilder(other)
                    ),
                _ => concatViaBuilder(other)
            )
        end concat

        infix def ++(other: Dict[K, V]): Dict[K, V] = concat(other)

        // Iteration

        inline def foreach(inline fn: (K, V) => Unit): Unit = reduce(
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

        inline def foreachKey(inline fn: K => Unit): Unit = reduce(
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

        inline def foreachValue(inline fn: V => Unit): Unit = reduce(
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

        inline def forall(inline fn: (K, V) => Boolean): Boolean = reduce(
            span =>
                val n = Span.size(span) / 2
                @tailrec def loop(i: Int): Boolean =
                    i >= n || (fn(Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V]) && loop(i + 1))
                loop(0)
            ,
            map =>
                var result = true
                map.foreachEntry { (k, v) =>
                    if result then result = fn(k, v)
                }
                result
        )

        inline def exists(inline fn: (K, V) => Boolean): Boolean = reduce(
            span =>
                val n = Span.size(span) / 2
                @tailrec def loop(i: Int): Boolean =
                    i < n && (fn(Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V]) || loop(i + 1))
                loop(0)
            ,
            map =>
                var result = false
                map.foreachEntry { (k, v) =>
                    if !result then result = fn(k, v)
                }
                result
        )

        inline def count(inline fn: (K, V) => Boolean): Int = reduce(
            span =>
                val n = Span.size(span) / 2
                @tailrec def loop(i: Int, acc: Int): Int =
                    if i >= n then acc
                    else if fn(Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V]) then loop(i + 1, acc + 1)
                    else loop(i + 1, acc)
                loop(0, 0)
            ,
            map =>
                var acc = 0
                map.foreachEntry { (k, v) =>
                    if fn(k, v) then acc += 1
                }
                acc
        )

        inline def find(inline fn: (K, V) => Boolean): Maybe[(K, V)] = reduce(
            span =>
                val n = Span.size(span) / 2
                @tailrec def loop(i: Int): Maybe[(K, V)] =
                    if i >= n then Maybe.empty
                    else
                        val k = Span.apply(span)(i).asInstanceOf[K]
                        val v = Span.apply(span)(n + i).asInstanceOf[V]
                        if fn(k, v) then Maybe((k, v))
                        else loop(i + 1)
                loop(0)
            ,
            map =>
                var result: Maybe[(K, V)] = Maybe.empty
                map.foreachEntry { (k, v) =>
                    if result.isEmpty && fn(k, v) then result = Maybe((k, v))
                }
                result
        )

        inline def foldLeft[B](z: B)(inline fn: (B, K, V) => B): B = reduce(
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

        // Transform

        inline def map[K2, V2](inline fn: (K, V) => (K2, V2)): Dict[K2, V2] =
            val b = DictBuilder.init[K2, V2]
            foreach { (k, v) =>
                val (k2, v2) = fn(k, v)
                discard(b.add(k2, v2))
            }
            b.result()
        end map

        inline def flatMap[K2, V2](inline fn: (K, V) => Dict[K2, V2]): Dict[K2, V2] =
            val b = DictBuilder.init[K2, V2]
            foreach { (k, v) =>
                fn(k, v).foreach { (k2, v2) =>
                    discard(b.add(k2, v2))
                }
            }
            b.result()
        end flatMap

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

        inline def filterNot(inline fn: (K, V) => Boolean): Dict[K, V] =
            filter((k, v) => !fn(k, v))

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
            ).asInstanceOf[Dict[K, V2]]
        end mapValues

        // Keys/Values

        def keys(using ClassTag[K]): Span[K] = reduce(
            span =>
                val n   = Span.size(span) / 2
                val arr = new Array[K](n)
                var i   = 0
                while i < n do
                    arr(i) = Span.apply(span)(i).asInstanceOf[K]
                    i += 1
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

        def values(using ClassTag[V]): Span[V] = reduce(
            span =>
                val n   = Span.size(span) / 2
                val arr = new Array[V](n)
                var i   = 0
                while i < n do
                    arr(i) = Span.apply(span)(n + i).asInstanceOf[V]
                    i += 1
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

        // Conversion

        def toMap: Map[K, V] = reduce(
            span =>
                val n   = Span.size(span) / 2
                var map = HashMap.empty[K, V]
                var i   = 0
                while i < n do
                    map = map.updated(Span.apply(span)(i).asInstanceOf[K], Span.apply(span)(n + i).asInstanceOf[V])
                    i += 1
                map
            ,
            map => map
        )

        // Equality/Display

        def is(other: Dict[K, V])(using CanEqual[K, K], CanEqual[V, V]): Boolean =
            size == other.size && forall { (k, v) =>
                val r = other.lookupOrSentinel(k)
                (r.asInstanceOf[AnyRef] ne sentinel) && r.equals(v)
            }

        def mkString(separator: String): String =
            val sb    = new java.lang.StringBuilder
            var first = true
            foreach { (k, v) =>
                if !first then discard(sb.append(separator))
                discard(sb.append(k).append(" -> ").append(v))
                first = false
            }
            sb.toString
        end mkString

        def mkString(start: String, sep: String, end: String): String =
            start + mkString(sep) + end

        def mkString: String = mkString("")

        // Private helpers

        private inline def reduce[B](
            inline small: Span[K | V] => B,
            inline large: HashMap[K, V] => B
        ): B =
            if self.isInstanceOf[HashMap[?, ?]] then large(self.asInstanceOf[HashMap[K, V]])
            else small(self.asInstanceOf[Span[K | V]])

        private inline def lookup[B](key: K, inline defined: V => B, inline undefined: => B): B =
            val r = lookupOrSentinel(key)
            if r.asInstanceOf[AnyRef] eq sentinel then undefined
            else defined(r.asInstanceOf[V])
        end lookup

        private def lookupOrSentinel(key: K): Any = reduce(
            span =>
                val n  = Span.size(span) / 2
                val kr = key.asInstanceOf[AnyRef]
                @tailrec def loop(i: Int): Any =
                    if i >= n then sentinel
                    else
                        val k = Span.apply(span)(i)
                        if (k.asInstanceOf[AnyRef] eq kr) || k.equals(key) then Span.apply(span)(n + i)
                        else loop(i + 1)
                loop(0)
            ,
            map => map.getOrElse(key, sentinel)
        )

        private def concatViaBuilder(other: Dict[K, V]): Dict[K, V] =
            val b = DictBuilder.init[K, V]
            foreach((k, v) => discard(b.add(k, v)))
            other.foreach((k, v) => discard(b.add(k, v)))
            b.result()
        end concatViaBuilder

    end extension

    private def trimFiltered[K, V](original: Dict[K, V], arr: Array[K | V], n: Int, j: Int): Dict[K, V] =
        if j == n then original
        else if j == 0 then empty
        else
            val trimmed = new Array[Any](j * 2).asInstanceOf[Array[K | V]]
            System.arraycopy(arr, 0, trimmed, 0, j)
            System.arraycopy(arr, n, trimmed, j, j)
            Span.fromUnsafe(trimmed)

end Dict
