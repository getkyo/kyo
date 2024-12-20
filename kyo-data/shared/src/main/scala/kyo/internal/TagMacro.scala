package kyo.internal

import kyo.Ansi.red
import kyo.Tag
import scala.quoted.*
object TagMacro:
    def derive[A: Type](using q: Quotes): Expr[Tag[A]] =
        import q.reflect.*
        val raw = encodeType(TypeRepr.of[A])
        '{ Tag.fromRaw[A](${ Expr(raw) }) }
    end derive

    private def encodeType(using q: Quotes)(tpe: q.reflect.TypeRepr): String =
        import q.reflect.*

        case class Context(inheritanceChain: List[String] = Nil)
        object Context:
            extension (ctx: Context)
                def and(className: String): Context = ctx.copy(inheritanceChain = className :: ctx.inheritanceChain)
                def contains(className: String): Boolean =
                    ctx.inheritanceChain.contains(className)
            end extension
        end Context

        def encodeTypeImpl(tpe: TypeRepr, ctx: Context): String =
            tpe.dealias match
                case OrType(left, right) =>
                    s"U:${encodeTypeImpl(left, ctx)}|${encodeTypeImpl(right, ctx)}"
                case AndType(left, right) =>
                    s"I:${encodeTypeImpl(left, ctx)}&${encodeTypeImpl(right, ctx)}"
                case tpe if tpe.typeSymbol.isClassDef =>
                    val className = tpe.typeSymbol.fullName

                    if ctx.contains(className) then
                        s"R:$className"
                    else
                        val typeParams =
                            if tpe.typeArgs.isEmpty then ""
                            else
                                tpe.typeArgs.zipWithIndex.map { case (arg, idx) =>
                                    val variance = tpe.typeSymbol.typeMembers(idx).flags match
                                        case flags if flags.is(Flags.Covariant)     => "+"
                                        case flags if flags.is(Flags.Contravariant) => "-"
                                        case _                                      => "="

                                    // Important: Use original ctx for encoding type params.
                                    val encoded = encodeTypeImpl(arg, ctx)
                                    s"P:$variance:$encoded"
                                }.mkString("<", ",", ">")

                        val parentCtx = ctx.and(className) // Get parent types - here we add to inheritance chain
                        val parents = tpe.baseClasses.tail
                            .filterNot(_.fullName == className)
                            .map(sym => tpe.baseType(sym))
                            .distinct
                            .map { parentType =>
                                val parentName = parentType.typeSymbol.fullName
                                val parentParams =
                                    if parentType.typeArgs.isEmpty then ""
                                    else
                                        parentType.typeArgs
                                            .map(arg => encodeTypeImpl(arg, parentCtx))
                                            .mkString("<", ",", ">")
                                s":C:$parentName$parentParams"
                            }
                            .mkString

                        s"C:$className$typeParams$parents"
                    end if
                case _ =>
                    report.errorAndAbort(
                        s"""kyo.Tag: Macro failed to encode type.
                         |Please report a bug: https://github.com/getkyo/kyo/issues
                         |
                         |Type: [${tpe.show}]
                         |Context: [${ctx.inheritanceChain.mkString(",")}]
                         |""".stripMargin.red
                    )

        encodeTypeImpl(tpe, Context())
    end encodeType
end TagMacro
