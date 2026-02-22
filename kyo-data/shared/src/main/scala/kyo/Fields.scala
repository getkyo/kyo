package kyo

import kyo.Record2.*
import scala.compiletime.*
import scala.quoted.*

/** Reifies the metadata of an intersection of `~[Name, Value]` field types.
  *
  * Decomposes intersection types (e.g., `"name" ~ String & "age" ~ Int`) into a tuple of components, then provides operations over the
  * field structure: field names, Field instances, type-level mapping, and inline iteration.
  *
  * This is the single macro-powered abstraction — all other operations (Record2 field access, update, etc.) are built on pure Scala.
  */
sealed abstract class Fields[A] extends Serializable:

    /** Tuple representation: `"a" ~ Int & "b" ~ String` → `("a" ~ Int) *: ("b" ~ String) *: EmptyTuple` */
    type AsTuple <: Tuple

    /** Applies a type constructor to each component and re-intersects: `Fields["a" ~ Int & "b" ~ String].Map[F]` =
      * `F["a" ~ Int] & F["b" ~ String]`
      */
    type Map[F[_]] = Fields.Join[Tuple.Map[AsTuple, F]]

    /** Runtime field names, materialized by the macro. */
    val names: Set[String]

    /** Runtime Field descriptors, lazily materialized. */
    lazy val fields: List[Field[?, ?]]

end Fields

object Fields:

    private def create[A, T <: Tuple](_names: Set[String], _fields: => List[Field[?, ?]]): Fields.Aux[A, T] =
        new Fields[A]:
            type AsTuple = T
            val names       = _names
            lazy val fields = _fields

    private type Join[A <: Tuple] = Tuple.Fold[A, Any, [B, C] =>> B & C]

    type Aux[A, T] =
        Fields[A]:
            type AsTuple = T

    transparent inline given derive[A]: Fields[A] =
        ${ deriveImpl[A] }

    /** Summon Field instances for each component in A. */
    def fields[A](using f: Fields[A]): List[Field[?, ?]] = f.fields

    /** Collect field names from A. */
    def names[A](using f: Fields[A]): Set[String] = f.names

    opaque type SummonAll[A, F[_]] = Map[String, F[Any]]

    extension [A, F[_]](sa: SummonAll[A, F])
        def get(name: String): F[Any]            = sa(name)
        def contains(name: String): Boolean      = sa.contains(name)
        def iterator: Iterator[(String, F[Any])] = sa.iterator
    end extension

    object SummonAll:
        inline given [A, F[_]](using f: Fields[A]): SummonAll[A, F] =
            summonLoop[f.AsTuple, F]

        private inline def summonLoop[T <: Tuple, F[_]]: Map[String, F[Any]] =
            inline erasedValue[T] match
                case _: EmptyTuple => Map.empty
                case _: ((n ~ v) *: rest) =>
                    summonLoop[rest, F].updated(constValue[n & String], summonInline[F[v]].asInstanceOf[F[Any]])
    end SummonAll

    // --- Macro ---

    private def deriveImpl[A: Type](using Quotes): Expr[Fields[A]] =
        import quotes.reflect.*

        def decompose(tpe: TypeRepr): Vector[TypeRepr] =
            tpe match
                case AndType(l, r) =>
                    decompose(l) ++ decompose(r)
                case _ =>
                    if tpe =:= TypeRepr.of[Any] then Vector()
                    else
                        try
                            tpe.typeSymbol.tree match
                                case typeDef: TypeDef =>
                                    typeDef.rhs match
                                        case bounds: TypeBoundsTree =>
                                            val hi = bounds.hi.tpe
                                            if !(hi =:= TypeRepr.of[Any]) then decompose(hi)
                                            else Vector(tpe)
                                        case _ => Vector(tpe)
                                case _ => Vector(tpe)
                        catch case _: Exception => Vector(tpe)

        def tupled(typs: Vector[TypeRepr]): TypeRepr =
            typs match
                case h +: t => TypeRepr.of[*:].appliedTo(List(h, tupled(t)))
                case _      => TypeRepr.of[EmptyTuple]

        val components = decompose(TypeRepr.of[A].dealias)

        case class ComponentInfo(name: String, nameExpr: Expr[String], tagExpr: Expr[Any], nestedExpr: Expr[List[Field[?, ?]]])

        def extractComponent(tpe: TypeRepr): Option[ComponentInfo] =
            tpe match
                case AppliedType(_, List(ConstantType(StringConstant(name)), valueType)) =>
                    val nameExpr = Expr(name)
                    val tagExpr = valueType.asType match
                        case '[v] =>
                            Expr.summon[Tag[v]].getOrElse(
                                report.errorAndAbort(s"Cannot summon Tag for field '$name': ${valueType.show}")
                            )
                    val nestedExpr = valueType.asType match
                        case '[Record2[f]] =>
                            Expr.summon[Fields[f]] match
                                case Some(fields) => '{ $fields.fields }
                                case None         => '{ Nil: List[Field[?, ?]] }
                        case _ => '{ Nil: List[Field[?, ?]] }
                    Some(ComponentInfo(name, nameExpr, tagExpr, nestedExpr))
                case _ => None

        val infos    = components.flatMap(extractComponent)
        val nameArgs = infos.map(_.nameExpr).toList
        val namesSet = '{ Set(${ Varargs(nameArgs) }*) }
        val fieldsList = Expr.ofList(infos.map(ci =>
            '{ Field[String, Any](${ ci.nameExpr }, ${ ci.tagExpr }.asInstanceOf[Tag[Any]], ${ ci.nestedExpr }) }
        ).toList)

        tupled(components).asType match
            case '[type x <: Tuple; x] =>
                '{ create[A, x]($namesSet, $fieldsList) }
        end match
    end deriveImpl

end Fields
