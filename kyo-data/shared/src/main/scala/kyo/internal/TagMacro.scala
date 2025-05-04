package kyo.internal

import kyo.*
import kyo.Tag.*
import kyo.Tag.Type.*
import scala.collection.immutable.HashSet
import scala.quoted.{Type as SType, *}

private[kyo] object TagMacro:

    def deriveImpl[A: SType](dynamic: Boolean)(using Quotes): Expr[String | Type[A]] =
        import quotes.reflect.*
        try
            Expr(Tag.internal.encode(deriveStatic(TypeRepr.of[A], Set.empty)))
        catch
            case DynamicTypeDetected(missing) =>
                if !dynamic && FindEnclosing.isInternal then
                    report.errorAndAbort(
                        s"Dynamic tags aren't allowed in the kyo package for performance reasons. Static derivation failed for '$missing', please modify the method to take an implicit 'Tag[${TypeRepr.of[A].show}]'."
                    )
                end if
                def tpe = deriveDynamic(TypeRepr.of[A], Set.empty)
                try
                    if dynamic then
                        '{ Tag.internal.encode($tpe.asInstanceOf[Type[A]]) }
                    else
                        '{ $tpe.asInstanceOf[Type[A]] }
                catch
                    case MissingTag(missing) =>
                        report.errorAndAbort(
                            s"Can't derive Tag for type '${TypeRepr.of[A].show}'. Please provide an implicit kyo.Tag[$missing] parameter."
                        )
                end try
        end try
    end deriveImpl

    private case class DynamicTypeDetected(tpe: String) extends Exception
    private case class MissingTag(tpe: String)          extends Exception

    private def deriveStatic(using q: Quotes)(t: q.reflect.TypeRepr, seen: Set[q.reflect.Symbol]): Type[?] =
        import quotes.reflect.*
        t.dealias match
            case tpe if tpe =:= TypeRepr.of[Any]     => AnyType
            case tpe if tpe =:= TypeRepr.of[Nothing] => NothingType
            case tpe if tpe =:= TypeRepr.of[Null]    => NullType
            case AndType(a, b)                       => IntersectionType(deriveStatic(a, seen), deriveStatic(b, seen))
            case OrType(a, b)                        => UnionType(deriveStatic(a, seen), deriveStatic(b, seen))
            case tpe @ ConstantType(const)           => LiteralType[Any](deriveStatic(tpe.widen, seen).asInstanceOf[Type[Any]], const.value)
            case tpe if seen.contains(tpe.typeSymbol) => Recursive(tpe.typeSymbol.fullName.hashCode().toString())

            case tpe if tpe.typeSymbol.isClassDef =>
                val newSeen = seen + tpe.typeSymbol
                val bases =
                    tpe.baseClasses.map { base =>
                        val baseTpe = tpe.baseType(base)
                        val variances =
                            baseTpe.typeSymbol.declaredTypes.flatMap { v =>
                                if !v.isTypeParam then None
                                else if v.paramVariance.is(Flags.Contravariant) then Present(Variance.Contravariant)
                                else if v.paramVariance.is(Flags.Covariant) then Present(Variance.Covariant)
                                else Present(Variance.Invariant)
                                end if
                            }
                        val params = baseTpe.typeArgs.map(deriveStatic(_, newSeen))
                        if params.size != variances.size then
                            report.errorAndAbort("BUG: Tag derivation failed! params.size != variances.size")
                        ClassType.Base(base.fullName, Chunk.from(variances).toIndexed, Chunk.from(params).toIndexed)
                    }
                ClassType(tpe.typeSymbol.fullName.hashCode().toString(), Chunk.from(bases).toIndexed)

            case tpe @ TypeLambda(_, _, bodyType) =>
                deriveStatic(bodyType, seen)

            case tpe =>
                throw new DynamicTypeDetected(tpe.show)
        end match
    end deriveStatic

    private def deriveDynamic(using q: Quotes)(t: q.reflect.TypeRepr, seen: Set[q.reflect.Symbol]): Expr[Type[?]] =
        import quotes.reflect.*
        t.dealias match
            case tpe if tpe =:= TypeRepr.of[Any]     => '{ AnyType }
            case tpe if tpe =:= TypeRepr.of[Nothing] => '{ NothingType }
            case tpe if tpe =:= TypeRepr.of[Null]    => '{ NullType }
            case AndType(a, b) =>
                val ta = deriveDynamic(a, seen)
                val tb = deriveDynamic(b, seen)
                '{ IntersectionType(${ ta.asInstanceOf }, ${ tb.asInstanceOf }) }
            case OrType(a, b) =>
                val ta = deriveDynamic(a, seen)
                val tb = deriveDynamic(b, seen)
                '{ UnionType(${ ta.asInstanceOf }, ${ tb.asInstanceOf }) }
            case tpe @ ConstantType(const) =>
                val t = deriveDynamic(tpe.widen, seen)
                val value =
                    const.value match
                        case x: Int     => Expr(x)
                        case x: Long    => Expr(x)
                        case x: Float   => Expr(x)
                        case x: Double  => Expr(x)
                        case x: Boolean => Expr(x)
                        case x: Char    => Expr(x)
                        case x: String  => Expr(x)
                        case x          => report.errorAndAbort(s"Unsupported literal type: $x")
                '{ LiteralType[Any](${ t.asInstanceOf }, $value) }
            case tpe if seen.contains(tpe.typeSymbol) =>
                '{ Recursive(${ Expr(tpe.typeSymbol.fullName.hashCode().toString()) }) }
            case tpe if tpe.typeSymbol.isClassDef =>
                val newSeen = seen + tpe.typeSymbol
                val bases =
                    tpe.baseClasses.map { base =>
                        val baseTpe = tpe.baseType(base)
                        val variances =
                            baseTpe.typeSymbol.declaredTypes.flatMap { v =>
                                if !v.isTypeParam then None
                                else if v.paramVariance.is(Flags.Contravariant) then Present('{ Variance.Contravariant })
                                else if v.paramVariance.is(Flags.Covariant) then Present('{ Variance.Covariant })
                                else Present('{ Variance.Invariant })
                                end if
                            }
                        val params = baseTpe.typeArgs.map(deriveDynamic(_, newSeen))
                        require(params.size == variances.size)
                        '{
                            ClassType.Base(
                                ${ Expr(base.fullName) },
                                Chunk.from(${ Expr.ofSeq(variances) }).toIndexed,
                                Chunk.from(${ Expr.ofSeq(params) }).toIndexed
                            )
                        }
                    }
                '{ ClassType(${ Expr(tpe.typeSymbol.fullName.hashCode().toString()) }, Chunk.from(${ Expr.ofSeq(bases) }).toIndexed) }

            case tpe @ TypeLambda(_, _, bodyType) =>
                deriveDynamic(bodyType, seen)

            case tpe =>
                tpe.asType match
                    case '[t] =>
                        Expr.summon[Tag[t]] match
                            case Some(expr) => '{ $expr.tpe }
                            case _          => throw MissingTag(tpe.show)
        end match
    end deriveDynamic

    private def failMissing(using Quotes)(missing: quotes.reflect.TypeRepr) =
        import quotes.reflect.*
        report.errorAndAbort(
            report.errorAndAbort(s"Please provide an implicit kyo.Tag[${missing.show}] parameter.")
        )
    end failMissing
end TagMacro
