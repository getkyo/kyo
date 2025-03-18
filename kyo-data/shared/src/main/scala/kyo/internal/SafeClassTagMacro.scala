package kyo.internal

import kyo.Chunk
import kyo.SafeClassTag
import kyo.SafeClassTag.*
import scala.quoted.*

private[kyo] object SafeClassTagMacro:

    def derive[A: Type](using Quotes): Expr[SafeClassTag[A]] =
        import quotes.reflect.*

        def checkType(tpe: TypeRepr) =
            if !tpe.dealias.typeSymbol.isClassDef then
                report.errorAndAbort(
                    s"""This method requires a SafeClassTag, but the type ${tpe.show} is not a class.
                       |Consider using a concrete class instead of an abstract type or type parameter.
                       |For generic types, you can provide an evidence of SafeClassTag explicitly.
                       |Example: def method[A](a: A)(using SafeClassTag[A]) = ???""".stripMargin
                )
            end if
            if tpe.typeArgs.nonEmpty then
                report.errorAndAbort(
                    s"""This method requires a SafeClassTag, but the type ${tpe.show} has type parameters.
                       |This is a current limitation that may be lifted in future versions.
                       |For now, use a non-generic type.""".stripMargin
                )
            end if
            if tpe =:= TypeRepr.of[Null] then
                report.errorAndAbort(
                    s"""This method requires a SafeClassTag, but Null is not a valid type for SafeClassTag.
                       |SafeClassTag does not support Null to prevent runtime errors.""".stripMargin
                )
            end if
        end checkType

        def create(tpe: TypeRepr): Expr[SafeClassTag[Any]] =
            tpe match
                case OrType(_, _) =>
                    def flatten(tpe: TypeRepr): Chunk[TypeRepr] =
                        tpe match
                            case OrType(a, b) => flatten(a).concat(flatten(b))
                            case _            => Chunk(tpe)
                    val exprs = flatten(tpe).map(create)
                    '{ Union(Set(${ Varargs(exprs) }*)) }
                case AndType(_, _) =>
                    def flatten(tpe: TypeRepr): Chunk[TypeRepr] =
                        tpe match
                            case AndType(a, b) => flatten(a).concat(flatten(b))
                            case _             => Chunk(tpe)
                    val exprs = flatten(tpe).map(create)
                    '{ Intersection(Set(${ Varargs(exprs) }*)) }
                case _ => createSingle(tpe)
            end match
        end create

        def createSingle(tpe: TypeRepr): Expr[SafeClassTag[Any]] =
            checkType(tpe)
            tpe match
                case ConstantType(const) =>
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
                    '{ LiteralTag($value) }
                case _ =>
                    tpe.asType match
                        case '[Nothing]                       => '{ NothingTag }
                        case '[Unit]                          => '{ UnitTag }
                        case '[Int]                           => '{ IntTag }
                        case '[Long]                          => '{ LongTag }
                        case '[Double]                        => '{ DoubleTag }
                        case '[Float]                         => '{ FloatTag }
                        case '[Byte]                          => '{ ByteTag }
                        case '[Short]                         => '{ ShortTag }
                        case '[Char]                          => '{ CharTag }
                        case '[Boolean]                       => '{ BooleanTag }
                        case _ if tpe =:= TypeRepr.of[AnyVal] => '{ AnyValTag }
                        case '[t] =>
                            val classOfSym = Symbol.requiredMethod("scala.Predef.classOf")
                            val classOfExpr = Select(Ref(Symbol.requiredModule("scala.Predef")), classOfSym)
                                .appliedToType(TypeRepr.of[t])
                            val expr = classOfExpr.asExprOf[Any]
                            '{ $expr.asInstanceOf[SafeClassTag[Any]] }
            end match
        end createSingle

        val result = create(TypeRepr.of[A].dealias)
        '{ $result.asInstanceOf[SafeClassTag[A]] }
    end derive
end SafeClassTagMacro
