package kyo.internal

import kyo.*
import kyo.Tag.*
import scala.quoted.*

object TagMacro:

    def tagImpl[A: Type](using Quotes): Expr[String] =
        import quotes.reflect.*

        encodeType(TypeRepr.of[A])
    end tagImpl

    def tags[A: Type](using q: Quotes)(flatten: q.reflect.TypeRepr => Seq[q.reflect.TypeRepr]): Expr[Seq[String]] =
        import quotes.reflect.*
        Expr.ofSeq {
            flatten(TypeRepr.of[A]).foldLeft(Seq.empty[Expr[String]]) {
                (acc, repr) =>
                    acc :+ encodeType(repr)
            }
        }
    end tags

    def setImpl[A: Type](using q: Quotes): Expr[Set[A]] =
        import quotes.reflect.*
        TypeRepr.of[A] match
            case AndType(_, _) => intersectionImpl[A]
            case _             => unionImpl[A]
        end match
    end setImpl

    def unionImpl[A: Type](using q: Quotes): Expr[Union[A]] =
        import quotes.reflect.*

        def flatten(tpe: TypeRepr): Seq[TypeRepr] =
            tpe match
                case OrType(a, b) => flatten(a) ++ flatten(b)
                case tpe: AndType => report.errorAndAbort(s"Union tags don't support type intersections. Found: ${tpe.show}")
                case tpe          => Seq(tpe)

        '{ Union.raw[A](${ tags(using q)(flatten) }) }
    end unionImpl

    def intersectionImpl[A: Type](using q: Quotes): Expr[Intersection[A]] =
        import quotes.reflect.*

        def flatten(tpe: TypeRepr): Seq[TypeRepr] =
            tpe match
                case AndType(a, b) => flatten(a) ++ flatten(b)
                case tpe: OrType   => report.errorAndAbort(s"Intersection tags don't support type unions. Found: ${tpe.show}")
                case tpe           => Seq(tpe)

        '{ Intersection.raw[A](${ tags(using q)(flatten) }) }
    end intersectionImpl

    def encodeType(using Quotes)(tpe: quotes.reflect.TypeRepr): Expr[String] =
        import quotes.reflect.*

        tpe.dealias match
            case AndType(_, _) =>
                report.errorAndAbort(
                    s"Method doesn't accept intersection types. Found: ${tpe.show}."
                )
            case OrType(_, _) =>
                report.errorAndAbort(
                    s"Method doesn't accept union types. Found: ${tpe.show}."
                )
            case tpe if tpe.typeSymbol.fullName == "scala.Nothing" =>
                report.errorAndAbort(
                    s"Tag cannot be created for Nothing as it has no values. Use a different type or add explicit type parameters if needed."
                )
            case tpe if tpe.typeSymbol.isClassDef =>
                val sym = tpe.typeSymbol
                val path = tpe.dealias.baseClasses.map { sym =>
                    val name = toCompact.getOrElse(sym.fullName, sym.fullName)
                    val size = IntEncoder.encode(name.length())
                    val hash = IntEncoder.encodeHash(name.hashCode())
                    s"$size$hash$name"
                }.mkString(";") + ";"
                val variances = sym.typeMembers.flatMap { v =>
                    if !v.isTypeParam then None
                    else if v.flags.is(Flags.Contravariant) then Some("-")
                    else if v.flags.is(Flags.Covariant) then Some("+")
                    else Some("=")
                }
                val params = tpe.typeArgs.map(encodeType)
                if params.isEmpty then
                    Expr(path)
                else
                    concat(
                        Expr(s"$path[") ::
                            (variances.zip(params).map((v, p) => Expr(v) :: p :: Nil)
                                .reduce((a, b) =>
                                    a ::: Expr(",") :: b
                                ) :+ Expr("]"))
                    )
                end if
            case _ =>
                tpe.asType match
                    case '[tpe] =>
                        Expr.summon[Tag[tpe]] match
                            case None =>
                                failMissing(TypeRepr.of[tpe])
                            case Some(value) =>
                                '{ $value.raw }
        end match
    end encodeType

    def failMissing(using Quotes)(missing: quotes.reflect.TypeRepr) =
        import quotes.reflect.*
        report.errorAndAbort(
            report.errorAndAbort(s"Please provide an implicit kyo.Tag[${missing.show}] parameter.")
        )
    end failMissing

    def concat(l: List[Expr[String]])(using Quotes): Expr[String] =
        def loop(l: List[Expr[String]], acc: String, exprs: List[Expr[String]]): Expr[String] =
            l match
                case Nil =>
                    (Expr(acc) :: exprs).reverse match
                        case Nil => Expr("")
                        case exprs =>
                            exprs.filter {
                                case Expr("") => false
                                case _        => true
                            }.reduce((a, b) => '{ $a + $b })
                case Expr(s: String) :: next =>
                    loop(next, acc + s, exprs)
                case head :: next =>
                    loop(next, "", head :: Expr(acc) :: exprs)
        loop(l, "", Nil)
    end concat

    val toCompact = Map(
        "java.lang.Object"                -> "0",
        "scala.Matchable"                 -> "1",
        "scala.Any"                       -> "2",
        "scala.AnyVal"                    -> "3",
        "java.lang.String"                -> "4",
        "scala.Int"                       -> "5",
        "scala.Long"                      -> "6",
        "scala.Float"                     -> "7",
        "scala.Double"                    -> "8",
        "scala.Boolean"                   -> "9",
        "scala.Unit"                      -> "a",
        "scala.Option"                    -> "b",
        "scala.Some"                      -> "c",
        "scala.None"                      -> "d",
        "scala.Left"                      -> "e",
        "scala.Right"                     -> "f",
        "scala.Tuple2"                    -> "g",
        "scala.collection.immutable.List" -> "h",
        "scala.collection.immutable.Nil"  -> "i",
        "scala.collection.immutable.Map"  -> "j",
        "scala.Nothing"                   -> "k",
        "java.lang.CharSequence"          -> "l",
        "java.lang.Comparable"            -> "m",
        "java.io.Serializable"            -> "n",
        "scala.Product"                   -> "o",
        "scala.Equals"                    -> "p",
        "kyo.kernel.Effect"               -> "q",
        "kyo.kernel.ContextEffect"        -> "r",
        "kyo.kernel.ArrowEffect"          -> "s",
        "kyo.Abort"                       -> "t",
        "kyo.Async$package$.Async$.Join"  -> "u",
        "kyo.Emit"                        -> "v",
        "kyo.IO"                          -> "w",
        "scala.Char"                      -> "x",
        "java.lang.Throwable"             -> "y",
        "java.lang.Exception"             -> "z"
    )

    val fromCompact = toCompact.map(_.swap).toMap
end TagMacro
