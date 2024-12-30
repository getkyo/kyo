package kyo.internal

import kyo.Ansi.red
import kyo.Tag
import scala.quoted.*
object TagMacro:
    def derive[A: Type](using q: Quotes): Expr[Tag[A]] =
        import q.reflect.*
        val raw = encodeType(TypeRepr.of[A])
        '{ Tag.fromRaw[A]($raw) }
    end derive

    private def encodeType(using q: Quotes)(tpe: q.reflect.TypeRepr): Expr[String] =
        import q.reflect.*

        case class Context(inheritanceChain: List[String] = Nil)
        object Context:
            extension (ctx: Context)
                def and(className: String): Context = ctx.copy(inheritanceChain = className :: ctx.inheritanceChain)
                def contains(className: String): Boolean =
                    ctx.inheritanceChain.contains(className)
            end extension
        end Context

        def encodeTypeImpl(tpe: TypeRepr, ctx: Context): Expr[String] =
            val dealiasedType = tpe.dealias
            dealiasedType match
                case OrType(left, right) =>
                    val leftStr  = encodeTypeImpl(left, ctx).valueOrAbort
                    val rightStr = encodeTypeImpl(right, ctx).valueOrAbort
                    Expr(s"U:$leftStr|$rightStr")

                case AndType(left, right) =>
                    val leftStr  = encodeTypeImpl(left, ctx).valueOrAbort
                    val rightStr = encodeTypeImpl(right, ctx).valueOrAbort
                    Expr(s"I:$leftStr&$rightStr")

                case tpe @ AppliedType(tycon, args) =>
                    val dealiasedTycon = tycon.dealias
                    val className      = dealiasedTycon.typeSymbol.fullName
                    val typeParams =
                        if args.isEmpty then ""
                        else
                            args.zipWithIndex.map { case (arg, idx) =>
                                // Get the type parameter for this specific index
                                val typeParams = dealiasedTycon.typeSymbol.typeMembers.filter(_.isTypeParam)
                                val typeParam  = typeParams(idx)
                                val variance = typeParam.flags match
                                    case flags if flags.is(Flags.Covariant)     => "+"
                                    case flags if flags.is(Flags.Contravariant) => "-"
                                    case _                                      => "="

                                if ctx.contains(arg.typeSymbol.fullName) then
                                    s"P:=:R:${arg.typeSymbol.fullName}"
                                else if arg match
                                        case AppliedType(_, _) => true // Check if it's a parameterized type
                                        case _                 => false
                                then
                                    // For nested parameterized types (like Box[Int]), encode the full type
                                    val encoded = encodeTypeImpl(arg, ctx.and(tpe.typeSymbol.fullName)).valueOrAbort
                                    s"P:$variance:$encoded"
                                else if arg.typeSymbol.isType && arg.typeSymbol.typeMembers.exists(_.isTypeParam) then
                                    // For type constructors (like List, Monad), just use the class name
                                    s"P:=:C:${arg.typeSymbol.fullName}"
                                else
                                    // For concrete types, encode the full type
                                    val encoded = encodeTypeImpl(arg, ctx.and(tpe.typeSymbol.fullName)).valueOrAbort
                                    s"P:$variance:$encoded"
                                end if
                            }.mkString("<", ",", ">")

                    Expr(s"C:$className$typeParams")

                case tpe if tpe.typeSymbol.isClassDef =>
                    val baseTypes = tpe.baseClasses.tail.map { sym =>
                        if ctx.contains(sym.fullName) then
                            s":C:${sym.fullName}"
                        else
                            val baseType = tpe.baseType(sym)
                            baseType match
                                case AppliedType(tycon, args) =>
                                    val params = args.zipWithIndex.map { case (arg, idx) =>
                                        if arg.typeSymbol.fullName == tpe.typeSymbol.fullName ||
                                            ctx.contains(arg.typeSymbol.fullName)
                                        then
                                            s"P:=:R:${arg.typeSymbol.fullName}"
                                        else if arg.typeSymbol.isType && arg.typeSymbol.typeMembers.exists(_.isTypeParam) then
                                            s"P:=:C:${arg.typeSymbol.fullName}"
                                        else
                                            val variance = tycon.typeSymbol.typeMembers(idx).flags match
                                                case flags if flags.is(Flags.Covariant)     => "+"
                                                case flags if flags.is(Flags.Contravariant) => "-"
                                                case _                                      => "="
                                            s"P:$variance:C:${arg.typeSymbol.fullName}"
                                    }.mkString("<", ",", ">")
                                    s":C:${sym.fullName}$params"
                                case _ =>
                                    s":C:${sym.fullName}"
                            end match
                    }.mkString("")

                    val fullName = tpe.typeSymbol.fullName.replaceAll("\\(\\)", "")
                    val result   = s"C:$fullName$baseTypes"
                    if result.length > 10000 then
                        report.errorAndAbort(s"Class type encoding too large (${result.length}): $result")
                    Expr(result)

                case _ =>
                    Expr(s"C:${tpe.show}")
            end match
        end encodeTypeImpl

        encodeTypeImpl(tpe, Context())
    end encodeType
end TagMacro
