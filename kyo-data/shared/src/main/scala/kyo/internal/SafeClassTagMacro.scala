package kyo.internal

import kyo.SafeClassTag
import kyo.SafeClassTag.*
import scala.quoted.*

private[kyo] object SafeClassTagMacro:

    def derive[A: Type](using Quotes): Expr[SafeClassTag[A]] =
        import quotes.reflect.*

        def checkType(tpe: TypeRepr) =
            if !tpe.dealias.typeSymbol.isClassDef then
                report.errorAndAbort(s"Expected a class type but got: ${tpe.show}")
            if tpe.typeArgs.nonEmpty then
                report.errorAndAbort(s"Type ${tpe.show} has type parameters. SafeClassTag only supports types without parameters.")
            if tpe =:= TypeRepr.of[Null] then
                report.errorAndAbort(s"Type ${tpe.show} is not a valid type. SafeClassTag does not support Null.")
        end checkType

        def create(tpe: TypeRepr): Expr[SafeClassTag[Any]] =
            tpe match
                case OrType(_, _) =>
                    def flatten(tpe: TypeRepr): Seq[TypeRepr] =
                        tpe match
                            case OrType(a, b) => flatten(a) ++ flatten(b)
                            case _            => Seq(tpe)
                    val types = flatten(tpe)
                    val exprs = types.map(create)
                    '{ Union(${ Expr.ofList(exprs) }) }
                case AndType(_, _) =>
                    def flatten(tpe: TypeRepr): Seq[TypeRepr] =
                        tpe match
                            case AndType(a, b) => flatten(a) ++ flatten(b)
                            case _             => Seq(tpe)
                    val types = flatten(tpe)
                    val exprs = types.map(create)
                    '{ Intersection(${ Expr.ofList(exprs) }) }
                case _ => createSingle(tpe)
            end match
        end create

        def createSingle(tpe: TypeRepr): Expr[SafeClassTag[Any]] =
            checkType(tpe)
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
