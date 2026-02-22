package kyo

import kyo.Record2.*
import scala.language.dynamics
import scala.language.implicitConversions

final class Record2[F](private[kyo] val dict: Dict[String, Any]) extends Dynamic:

    def selectDynamic[Name <: String & Singleton](name: Name)(using h: Fields.Have[F, Name]): h.Value =
        dict(name).asInstanceOf[h.Value]

    def &[A](other: Record2[A]): Record2[F & A] =
        new Record2(dict ++ other.dict)

    def update[Name <: String & Singleton, V](name: Name, value: V)(using F <:< (Name ~ V)): Record2[F] =
        new Record2(dict.update(name, value.asInstanceOf[Any]))

    def compact(using f: Fields[F]): Record2[F] =
        new Record2(dict.filter((k, _) => f.names.contains(k)))

    def map[G[_]](using
        f: Fields[F]
    )(
        fn: [t] => t => G[t]
    ): Record2[f.Map[~.MapValue[G]]] =
        new Record2(
            dict
                .filter((k, _) => f.names.contains(k))
                .mapValues(v => fn(v))
        )

    def size: Int = dict.size

    def toMap: Map[String, Any] = dict.toMap

    def is(other: Record2[F])(using Fields.Comparable[F]): Boolean =
        given CanEqual[Any, Any] = CanEqual.derived
        dict.is(other.dict)

    def show: String =
        val sb    = new StringBuilder
        var first = true
        dict.foreach: (k, v) =>
            if !first then discard(sb.append(" & "))
            discard(sb.append(k).append(" ~ ").append(v))
            first = false
        sb.toString
    end show

end Record2

object Record2:

    final infix class ~[Name <: String, -Value] private () extends Serializable

    object `~`:
        type MapValue[G[_]] = [x] =>> x match
            case n ~ v => n ~ G[v]
    end `~`

    type FieldValue[T <: Tuple, Name <: String] = T match
        case (Name ~ v) *: _ => v
        case _ *: rest       => FieldValue[rest, Name]

    val empty: Record2[Any] = new Record2(Dict.empty[String, Any])

    implicit def widen[A <: B, B](r: Record2[A]): Record2[B] =
        r.asInstanceOf[Record2[B]]

    extension (self: String)
        def ~[Value](value: Value): Record2[self.type ~ Value] =
            new Record2(Dict[String, Any](self -> value))

    given render[F](using f: Fields[F], renders: Fields.SummonAll[F, Render]): Render[Record2[F]] =
        Render.from: (value: Record2[F]) =>
            val sb    = new StringBuilder
            var first = true
            value.dict.foreach: (name, v) =>
                if renders.contains(name) then
                    if !first then discard(sb.append(" & "))
                    discard(sb.append(name).append(" ~ ").append(renders.get(name).asText(v)))
                    first = false
            sb.toString

    private[kyo] def make[F](map: Map[String, Any]): Record2[F] =
        new Record2(Dict.from(map))

end Record2
