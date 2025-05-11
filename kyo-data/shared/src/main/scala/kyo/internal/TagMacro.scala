package kyo.internal

import kyo.*
import kyo.Tag.*
import kyo.Tag.internal.*
import kyo.Tag.internal.Type.*
import kyo.Tag.internal.Type.Entry.*
import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.immutable.HashMap
import scala.quoted.{Type as SType, *}

private[kyo] object TagMacro:

    private val compactNames = true

    def deriveImpl[A: SType](using Quotes): Expr[String | Tag.internal.Dynamic] =
        import quotes.reflect.*
        val (staticDB, dynamicDB) = deriveDB[A]
        val encoded               = Expr(Tag.internal.encode(staticDB))
        if dynamicDB.isEmpty then
            encoded
        else if FindEnclosing.isInternal then
            val missing =
                dynamicDB.map {
                    case (_, (tpe, _)) =>
                        tpe.show
                }
            report.errorAndAbort(
                s"Dynamic tags aren't allowed in the kyo package for performance reasons. Please modify the method to take an implicit 'Tag[${TypeRepr.of[A].show}]'. Dynamic types: ${missing.mkString(", ")}."
            )
        else
            val reifiedDB =
                dynamicDB.foldLeft('{ Map.empty[Entry.Id, Tag[Any]] }) {
                    case (map, (id, (_, tag))) =>
                        '{ $map.updated(${ Expr(id) }, $tag) }
                }
            '{ Tag.internal.Dynamic($encoded, $reifiedDB) }
        end if
    end deriveImpl

    private def deriveDB[A: SType](using
        q: Quotes
    ): (Map[Type.Entry.Id, Type.Entry], Map[Type.Entry.Id, (q.reflect.TypeRepr, Expr[Tag[Any]])]) =
        import quotes.reflect.*
        var nextId  = 0
        var seen    = Map.empty[TypeRepr | Symbol, (TypeRepr, String)]
        var static  = HashMap.empty[Type.Entry.Id, Type.Entry]
        var dynamic = HashMap.empty[Type.Entry.Id, (TypeRepr, Expr[Tag[Any]])]

        def visit(t: TypeRepr): Type.Entry.Id =

            val tpe = t.dealiasKeepOpaques.simplified.dealiasKeepOpaques
            val key =
                tpe.typeSymbol.isNoSymbol match
                    case true => tpe
                    case false =>
                        seen.get(tpe.typeSymbol) match
                            case None                          => tpe.typeSymbol
                            case Some((t, _)) if t.equals(tpe) => tpe.typeSymbol
                            case _                             => tpe
            if seen.contains(key) then
                seen(key)._2
            else
                val id = nextId.toString
                nextId += 1
                seen += key -> (tpe, id)

                def loop(tpe: TypeRepr): Entry =
                    tpe match
                        case tpe if tpe =:= TypeRepr.of[Any]     => AnyEntry
                        case tpe if tpe =:= TypeRepr.of[Nothing] => NothingEntry
                        case tpe if tpe =:= TypeRepr.of[Null]    => NullEntry

                        case tpe @ AndType(_, _) =>
                            IntersectionEntry(KArray.from(flattenAnd(tpe).map(visit)))

                        case tpe @ OrType(_, _) =>
                            UnionEntry(KArray.from(flattenOr(tpe).map(visit)))

                        case tpe @ ConstantType(const) =>
                            LiteralEntry(visit(tpe.widen), const.value.toString())

                        case TypeLambda(names, bounds, body) if body.typeSymbol.equals(tpe.typeSymbol) =>
                            loop(body.dealias.simplified)

                        case TypeLambda(names, bounds, body) =>
                            val params = names.map(_.toString)
                            val lowerBounds = bounds.map {
                                case TypeBounds(low, high) => visit(low)
                            }
                            val higherBounds = bounds.map {
                                case TypeBounds(low, high) => visit(high)
                            }
                            LambdaEntry(
                                KArray.from(params),
                                KArray.from(lowerBounds),
                                KArray.from(higherBounds),
                                visit(body)
                            )

                        case tpe if tpe.typeSymbol.isClassDef =>
                            val symbol = tpe.typeSymbol
                            val name   = symbol.fullName
                            val params = tpe.typeArgs.map(visit)
                            val variances =
                                symbol.declaredTypes.flatMap { v =>
                                    if !v.isTypeParam then None
                                    else if v.paramVariance.is(Flags.Contravariant) then Present(Variance.Contravariant)
                                    else if v.paramVariance.is(Flags.Covariant) then Present(Variance.Covariant)
                                    else Present(Variance.Invariant)
                                    end if
                                }
                            require(params.size == variances.size)
                            ClassEntry(
                                name,
                                KArray.from(variances),
                                KArray.from(params),
                                KArray.from(immediateParents(tpe).map(visit))
                            )

                        case tpe if tpe.typeSymbol.flags.is(Flags.Opaque) && tpe.typeSymbol.isTypeDef =>
                            val name = tpe.typeSymbol.fullName
                            tpe.typeSymbol.tree.asInstanceOf[TypeDef].rhs.asInstanceOf[TypeTree].tpe match
                                case tpe @ TypeBounds(lower, upper) =>
                                    val symbol = tpe.typeSymbol
                                    val params = tpe.typeArgs.map(visit)
                                    val variances =
                                        symbol.declarations.flatMap { v =>
                                            if !v.isTypeParam then None
                                            else if v.paramVariance.is(Flags.Contravariant) then Present(Variance.Contravariant)
                                            else if v.paramVariance.is(Flags.Covariant) then Present(Variance.Covariant)
                                            else Present(Variance.Invariant)
                                            end if
                                        }
                                    require(params.size == variances.size)
                                    OpaqueEntry(name, visit(lower), visit(upper), KArray.from(variances), KArray.from(params))
                            end match

                        case tpe =>
                            tpe.asType match
                                case '[t] =>
                                    Expr.summon[Tag[t]] match
                                        case Some(tag) =>
                                            dynamic = dynamic.updated(id, tpe -> '{ $tag.asInstanceOf[Tag[Any]] })
                                            null
                                        case None =>
                                            report.errorAndAbort(s"Please provide an implicit kyo.Tag[${tpe.show}] parameter.")

                val entry = loop(tpe)
                if entry != null then
                    static = static.updated(id, loop(tpe))
                id
            end if
        end visit

        discard(visit(TypeRepr.of[A]))
        (static, dynamic)
    end deriveDB

    private def immediateParents(using Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
        import quotes.reflect.*
        val all = tpe.baseClasses.tail.map(tpe.baseType)
        all.filter { parent =>
            !all.exists { otherAncestor =>
                !otherAncestor.equals(parent) && otherAncestor.baseClasses.contains(parent.typeSymbol)
            }
        }
    end immediateParents

    private def flattenAnd(using q: Quotes)(tpe: q.reflect.TypeRepr): Seq[q.reflect.TypeRepr] =
        import quotes.reflect.*
        def loop(tpe: TypeRepr): Seq[TypeRepr] =
            tpe match
                case AndType(a, b) => loop(a) ++ loop(b)
                case tpe           => Seq(tpe)
        loop(tpe).sortBy(_.show)
    end flattenAnd

    private def flattenOr(using q: Quotes)(tpe: q.reflect.TypeRepr): Seq[q.reflect.TypeRepr] =
        import quotes.reflect.*
        def loop(tpe: TypeRepr): Seq[TypeRepr] =
            tpe match
                case OrType(a, b) => loop(a) ++ loop(b)
                case tpe          => Seq(tpe)
        loop(tpe).sortBy(_.show)
    end flattenOr

end TagMacro
