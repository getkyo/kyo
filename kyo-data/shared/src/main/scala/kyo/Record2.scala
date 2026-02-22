package kyo

import kyo.Record2.*
import scala.language.dynamics
import scala.language.implicitConversions

final class Record2[F] private[Record2] (private val underlying: Map[String, Any]) extends Dynamic:

    def selectDynamic[Name <: String & Singleton](name: Name)(using f: Fields[F]): FieldValue[f.AsTuple, Name] =
        underlying(name).asInstanceOf[FieldValue[f.AsTuple, Name]]

    def &[A](other: Record2[A]): Record2[F & A] =
        new Record2(underlying ++ other.underlying)

    def update[Name <: String & Singleton, V](name: Name, value: V)(using F <:< (Name ~ V)): Record2[F] =
        new Record2(underlying.updated(name, value))

    def size: Int               = underlying.size
    def fields: List[String]    = underlying.keys.toList
    def toMap: Map[String, Any] = underlying

    def compact(using f: Fields[F]): Record2[F] =
        Record2.make(underlying.view.filterKeys(f.nameSet.contains).toMap)

    def map[G[_]](using
        f: Fields[F]
    )(
        fn: [t] => t => G[t]
    ): Record2[f.Map[~.MapValue[G]]] =
        val mapped = f.names.foldLeft(Map.empty[String, Any]): (acc, name) =>
            acc.updated(name, fn(underlying(name)))
        Record2.make[f.Map[~.MapValue[G]]](mapped)
    end map

    override def equals(that: Any): Boolean = that match
        case that: Record2[?] => underlying.equals(that.underlying)
        case _                => false

    override def hashCode: Int    = underlying.hashCode
    override def toString: String = underlying.iterator.map((k, v) => s"$k ~ $v").mkString(" & ")

end Record2

object Record2:

    final infix class ~[Name <: String, -Value] private () extends Serializable

    object `~`:
        type MapValue[G[_]] = [x] =>> x match
            case n ~ v => n ~ G[v]

    type FieldValue[T <: Tuple, Name <: String] = T match
        case (Name ~ v) *: _ => v
        case _ *: rest       => FieldValue[rest, Name]

    val empty: Record2[Any] = new Record2(Map.empty)

    private[kyo] def make[F](map: Map[String, Any]): Record2[F] = new Record2(map)

    given widen[A <: B, B]: Conversion[Record2[A], Record2[B]] =
        _.asInstanceOf[Record2[B]]

    extension (self: String)
        def ~[Value](value: Value): Record2[self.type ~ Value] =
            new Record2(Map(self -> value))

    given render[F](using f: Fields[F], sa: Fields.SummonAll[F, Render]): Render[Record2[F]] =
        Render.from: (value: Record2[F]) =>
            value.toMap.iterator.collect {
                case (name, v) if sa.map.contains(name) =>
                    val r = sa.map(name).asInstanceOf[Render[Any]]
                    name + " ~ " + r.asText(v)
            }.mkString(" & ")

end Record2
