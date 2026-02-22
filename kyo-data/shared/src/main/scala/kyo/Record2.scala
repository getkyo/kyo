package kyo

import kyo.Record2.*
import scala.collection.immutable.HashMap
import scala.language.dynamics
import scala.language.implicitConversions

sealed abstract class Record2[F] extends Dynamic:

    transparent inline def selectDynamic[Name <: String & Singleton](name: Name): Any =
        ${ internal.Record2Macros.selectDynamic[F, Name]('this, 'name) }

    def &[A](other: Record2[A]): Record2[F & A] =
        Record2.combine(this, other)

    def update[Name <: String & Singleton, V](name: Name, value: V)(using F <:< (Name ~ V)): Record2[F] =
        Record2.updated(this, name, value)

    def compact(using f: Fields[F]): Record2[F] =
        Record2.compacted(this, f.names)

    def map[G[_]](using
        f: Fields[F]
    )(
        fn: [t] => t => G[t]
    ): Record2[f.Map[~.MapValue[G]]] =
        Record2.mapped(this, f.names, fn)

    def size: Int

    def toMap: Map[String, Any]

    override def equals(that: Any): Boolean = that match
        case that: Record2[?] => size == that.size && toMap.equals(that.toMap)
        case _                => false

    override def hashCode: Int = toMap.hashCode

    override def toString: String =
        val sb    = new StringBuilder
        var first = true
        foreach: (k, v) =>
            if !first then discard(sb.append(" & "))
            discard(sb.append(k).append(" ~ ").append(v))
            first = false
        sb.toString
    end toString

    private[kyo] def get(name: String): Any
    private[Record2] def foreach(fn: (String, Any) => Unit): Unit

end Record2

object Record2:

    // Threshold: up to this many fields use flat array, above use Map
    private val MapThreshold = 8

    final infix class ~[Name <: String, -Value] private () extends Serializable

    object `~`:
        type MapValue[G[_]] = [x] =>> x match
            case n ~ v => n ~ G[v]
    end `~`

    type FieldValue[T <: Tuple, Name <: String] = T match
        case (Name ~ v) *: _ => v
        case _ *: rest       => FieldValue[rest, Name]

    val empty: Record2[Any] = new SmallRecord(Span.empty[Any])

    opaque type FieldsComparable[A] = Unit
    object FieldsComparable:
        private[kyo] def unsafe[A]: FieldsComparable[A] = ()

    transparent inline given fieldsComparable[A]: FieldsComparable[A] =
        ${ internal.Record2Macros.fieldsComparableImpl[A] }

    given canEqual[A, B](using FieldsComparable[A], FieldsComparable[B]): CanEqual[Record2[A], Record2[B]] =
        CanEqual.derived

    inline given widen[A, B]: Conversion[Record2[A], Record2[B]] =
        ${ internal.Record2Macros.widenImpl[A, B] }

    extension (self: String)
        def ~[Value](value: Value): Record2[self.type ~ Value] =
            new SmallRecord(Span[Any](self, value))

    given render[F](using f: Fields[F], renders: Fields.SummonAll[F, Render]): Render[Record2[F]] =
        Render.from: (value: Record2[F]) =>
            val sb    = new StringBuilder
            var first = true
            value.foreach: (name, v) =>
                if renders.contains(name) then
                    if !first then discard(sb.append(" & "))
                    discard(sb.append(name).append(" ~ ").append(renders.get(name).asText(v)))
                    first = false
            sb.toString

    private[kyo] def make[F](map: Map[String, Any]): Record2[F] =
        if map.size <= MapThreshold then
            val arr = new Array[Any](map.size * 2)
            var i   = 0
            map.foreach: (k, v) =>
                arr(i) = k
                arr(i + 1) = v
                i += 2
            new SmallRecord(Span.fromUnsafe(arr))
        else
            val b = HashMap.newBuilder[String, Any]
            map.foreach((k, v) => b += (k -> v))
            new LargeRecord(b.result())

    private def combine[F, A](left: Record2[F], right: Record2[A]): Record2[F & A] =
        val total = left.size + right.size
        if total <= MapThreshold then
            val arr = new Array[Any](total * 2)
            var i   = 0
            left.foreach: (k, v) =>
                arr(i) = k; arr(i + 1) = v; i += 2
            right.foreach: (k, v) =>
                arr(i) = k; arr(i + 1) = v; i += 2
            new SmallRecord(Span.fromUnsafe(arr))
        else
            val b = HashMap.newBuilder[String, Any]
            left.foreach((k, v) => b += (k -> v))
            right.foreach((k, v) => b += (k -> v))
            new LargeRecord(b.result())
        end if
    end combine

    private def updated[F](record: Record2[F], name: String, value: Any): Record2[F] =
        record match
            case r: SmallRecord[F] =>
                val arr = new Array[Any](r.entries.size)
                var i   = 0
                while i < arr.length do
                    arr(i) = r.entries(i)
                    arr(i + 1) = if r.entries(i).asInstanceOf[String] == name then value else r.entries(i + 1)
                    i += 2
                end while
                new SmallRecord(Span.fromUnsafe(arr))
            case r: LargeRecord[F] =>
                new LargeRecord(r.map.updated(name, value))

    private def compacted[F](record: Record2[F], names: Set[String]): Record2[F] =
        val arr = new Array[Any](names.size * 2)
        var i   = 0
        record.foreach: (k, v) =>
            if names.contains(k) then
                arr(i) = k; arr(i + 1) = v; i += 2
        trimmed(arr, i)
    end compacted

    private def mapped[R](record: Record2[?], names: Set[String], fn: [t] => t => Any): Record2[R] =
        val arr = new Array[Any](names.size * 2)
        var i   = 0
        record.foreach: (k, v) =>
            if names.contains(k) then
                arr(i) = k; arr(i + 1) = fn(v); i += 2
        trimmed(arr, i)
    end mapped

    private def trimmed[F](arr: Array[Any], len: Int): Record2[F] =
        if len == arr.length then new SmallRecord(Span.fromUnsafe(arr))
        else
            val t = new Array[Any](len)
            java.lang.System.arraycopy(arr, 0, t, 0, len)
            new SmallRecord(Span.fromUnsafe(t))

    // --- Two implementations: bimorphic dispatch (JIT-friendly) ---

    final private class SmallRecord[F](
        private[Record2] val entries: Span[Any] // flat (k0, v0, k1, v1, ...)
    ) extends Record2[F]:
        def size = entries.size / 2
        def toMap =
            val b = HashMap.newBuilder[String, Any]
            foreach((k, v) => b += (k -> v))
            b.result()
        end toMap
        private[kyo] def get(name: String) =
            var i = 0
            while i < entries.size do
                if entries(i).asInstanceOf[String] == name then return entries(i + 1)
                i += 2
            throw new NoSuchElementException(name)
        end get
        private[Record2] def foreach(fn: (String, Any) => Unit) =
            var i = 0
            while i < entries.size do
                fn(entries(i).asInstanceOf[String], entries(i + 1))
                i += 2
        end foreach
    end SmallRecord

    final private class LargeRecord[F](
        private[Record2] val map: Map[String, Any]
    ) extends Record2[F]:
        def size                           = map.size
        def toMap                          = map
        private[kyo] def get(name: String) = map(name)
        private[Record2] def foreach(fn: (String, Any) => Unit) =
            map.foreach((k, v) => fn(k, v))
    end LargeRecord

end Record2
