package kyo

import kyo.Record2.*
import scala.language.dynamics
import scala.language.implicitConversions

final class Record2[F](private[kyo] val dict: Dict[String, Any]) extends Dynamic:

    def selectDynamic[Name <: String & Singleton](name: Name)(using h: Fields.Have[F, Name]): h.Value =
        dict(name).asInstanceOf[h.Value]

    def getField[Name <: String & Singleton, V](name: Name)(using h: Fields.Have[F, Name]): h.Value =
        dict(name).asInstanceOf[h.Value]

    def &[A](other: Record2[A]): Record2[F & A] =
        new Record2(dict ++ other.dict)

    def update[Name <: String & Singleton, V](name: Name, value: V)(using F <:< (Name ~ V)): Record2[F] =
        new Record2(dict.update(name, value.asInstanceOf[Any]))

    def compact(using f: Fields[F]): Record2[F] =
        new Record2(dict.filter((k, _) => f.names.contains(k)))

    def fields(using f: Fields[F]): List[String] =
        f.fields.map(_.name)

    inline def values(using f: Fields[F]): f.Values =
        Record2.collectValues[f.AsTuple](dict).asInstanceOf[f.Values]

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

    def mapFields[G[_]](using
        f: Fields[F]
    )(
        fn: [t] => (Field[?, t], t) => G[t]
    ): Record2[f.Map[~.MapValue[G]]] =
        val result = DictBuilder.init[String, Any]
        f.fields.foreach: field =>
            dict.get(field.name) match
                case Present(v) =>
                    discard(result.add(field.name, fn(field.asInstanceOf[Field[?, Any]], v)))
                case _ =>
        new Record2(result.result())
    end mapFields

    inline def zip[F2](other: Record2[F2])(using
        f1: Fields[F],
        f2: Fields[F2]
    ): Record2[f1.Zipped[f2.AsTuple]] =
        val result = DictBuilder.init[String, Any]
        f1.fields.foreach: field =>
            dict.get(field.name) match
                case Present(v1) =>
                    other.dict.get(field.name) match
                        case Present(v2) =>
                            discard(result.add(field.name, (v1, v2)))
                        case _ =>
                case _ =>
        new Record2(result.result())
    end zip

    def size: Int = dict.size

    def toMap: Map[String, Any] = dict.toMap

    def is(other: Record2[F])(using Fields.Comparable[F]): Boolean =
        given CanEqual[Any, Any] = CanEqual.derived
        dict.is(other.dict)

    override def equals(that: Any): Boolean =
        that match
            case other: Record2[?] =>
                given CanEqual[Any, Any] = CanEqual.derived
                dict.is(other.dict)
            case _ => false

    override def hashCode(): Int = dict.toMap.hashCode()

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

    given [F](using Fields.Comparable[F]): CanEqual[Record2[F], Record2[F]] =
        CanEqual.derived

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

    import scala.compiletime.*

    inline def stage[A](using f: Fields[A]): StageOps[A, f.AsTuple] = new StageOps(())

    class StageOps[A, T <: Tuple](dummy: Unit) extends AnyVal:
        inline def apply[G[_]](fn: [v] => Field[?, v] => G[v])(using f: Fields[A]): Record2[f.Map[~.MapValue[G]]] =
            new Record2(stageLoop[f.AsTuple, G](fn)).asInstanceOf[Record2[f.Map[~.MapValue[G]]]]

        inline def using[TC[_]]: StageWith[A, T, TC] = new StageWith(())
    end StageOps

    class StageWith[A, T <: Tuple, TC[_]](dummy: Unit) extends AnyVal:
        inline def apply[G[_]](fn: [v] => (Field[?, v], TC[v]) => G[v])(using f: Fields[A]): Record2[f.Map[~.MapValue[G]]] =
            new Record2(stageLoopWith[f.AsTuple, TC, G](fn)).asInstanceOf[Record2[f.Map[~.MapValue[G]]]]

    private[kyo] inline def stageLoop[T <: Tuple, G[_]](fn: [v] => Field[?, v] => G[v]): Dict[String, Any] =
        inline erasedValue[T] match
            case _: EmptyTuple => Dict.empty[String, Any]
            case _: ((n ~ v) *: rest) =>
                val name  = constValue[n & String]
                val value = fn[v](Field(name, summonInline[Tag[v]]))
                stageLoop[rest, G](fn) ++ Dict[String, Any](name -> value)

    private[kyo] inline def stageLoopWith[T <: Tuple, TC[_], G[_]](fn: [v] => (Field[?, v], TC[v]) => G[v]): Dict[String, Any] =
        inline erasedValue[T] match
            case _: EmptyTuple => Dict.empty[String, Any]
            case _: ((n ~ v) *: rest) =>
                val name  = constValue[n & String]
                val value = fn[v](Field(name, summonInline[Tag[v]]), summonInline[TC[v]])
                stageLoopWith[rest, TC, G](fn) ++ Dict[String, Any](name -> value)

    transparent inline def fromProduct[A <: Product](value: A): Any =
        ${ internal.FieldsMacros.fromProductImpl[A]('value) }

    private[kyo] inline def collectValues[T <: Tuple](dict: Dict[String, Any]): Tuple =
        inline erasedValue[T] match
            case _: EmptyTuple => EmptyTuple
            case _: ((n ~ v) *: rest) =>
                dict(constValue[n & String]) *: collectValues[rest](dict)

    private[kyo] def make[F](map: Map[String, Any]): Record2[F] =
        new Record2(Dict.from(map))

end Record2
