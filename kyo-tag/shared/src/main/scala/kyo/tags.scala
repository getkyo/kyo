package kyo

import scala.annotation.switch
import scala.annotation.tailrec
import scala.quoted.*

case class Tag[T](tpe: String) extends AnyVal

object Tag:

    import internal.*

    inline given apply[T]: Tag[T] = ${ tagImpl[T] }

    extension [T](t1: Tag[T])

        def show: String =
            val decoded = t1.tpe.drop(2).takeWhile(_ != ';')
            fromCompact.getOrElse(decoded, decoded)

        infix def <:<[U](t2: Tag[U]): Boolean =
            t1 =:= t2 || isSubtype(t1, t2)

        infix def =:=[U](t2: Tag[U]): Boolean =
            (t1.tpe eq t2.tpe) || t1.tpe == t2.tpe

        infix def =!=[U](t2: Tag[U]): Boolean =
            (t1.tpe ne t2.tpe) || t1.tpe != t2.tpe

        infix def >:>[U](t2: Tag[U]): Boolean =
            t1 =:= t2 || isSubtype(t2, t1)

    end extension

    case class Union[T](tags: Seq[Tag[?]]) extends AnyVal

    object Union:

        inline given apply[T]: Union[T] = ${ unionImpl[T] }

        extension [T](t1: Union[T])
            def show: String =
                t1.tags.map(_.show).mkString(" | ")

            infix def <:<[U](t2: Tag[U]): Boolean =
                t1.tags.forall(_ <:< t2)

            infix def =:=[U](t2: Tag[U]): Boolean =
                t1.tags.forall(t2 =:= _)

            infix def =!=[U](t2: Tag[U]): Boolean =
                t1.tags.exists(t2 =!= _)

            infix def >:>[U](t2: Tag[U]): Boolean =
                t1.tags.exists(t2 <:< _)

            infix def <:<(t2: Union[?]): Boolean =
                t1.tags.forall(tag1 => t2.tags.exists(tag1 <:< _))

            infix def =:=(t2: Union[?]): Boolean =
                t1.tags.forall(tag1 => t2.tags.exists(tag1 =:= _)) &&
                    t2.tags.forall(tag2 => t1.tags.exists(tag2 =:= _))

            infix def =!=(t2: Union[?]): Boolean =
                !(t1 =:= t2)

            infix def >:>(t2: Union[?]): Boolean =
                t2.tags.forall(tag2 => t1.tags.exists(_ >:> tag2))
        end extension
    end Union

    case class Intersection[T](tags: Seq[Tag[?]]) extends AnyVal

    object Intersection:

        inline given apply[T]: Intersection[T] = ${ intersectionImpl[T] }

        extension [T](t1: Intersection[T])
            def show: String =
                t1.tags.map(_.show).mkString(" & ")

            infix def <:<[U](t2: Tag[U]): Boolean =
                t1.tags.exists(_ <:< t2)

            infix def =:=[U](t2: Tag[U]): Boolean =
                t1.tags.forall(_ =:= t2)

            infix def =!=[U](t2: Tag[U]): Boolean =
                !(t1 =:= t2)

            infix def >:>[U](t2: Tag[U]): Boolean =
                t1.tags.forall(t2 >:> _)

            infix def <:<(t2: Intersection[?]): Boolean =
                t2.tags.forall(tag2 => t1.tags.exists(_ <:< tag2))

            infix def =:=(t2: Intersection[?]): Boolean =
                t1.tags.forall(tag1 => t2.tags.exists(tag1 =:= _)) &&
                    t2.tags.forall(tag2 => t1.tags.exists(tag2 =:= _))

            infix def =!=(t2: Intersection[?]): Boolean =
                !(t1 =:= t2)

            infix def >:>(t2: Intersection[?]): Boolean =
                t1.tags.forall(tag1 => t2.tags.exists(tag1 >:> _))
        end extension
    end Intersection

    private[Tag] object internal:

        // Example Tag
        //
        // class Super
        // class Param1 extends Super
        // class Param2 extends Super
        // class Test[-T, +U]
        //
        // Tag[Test[Param1, Param2]]
        //
        // Test;Super;java.lang.Object;scala.Matchable;scala.Any;[-Param1;Super;java.lang.Object;scala.Matchable;scala.Any;,+Param2;Super;java.lang.Object;scala.Matchable;scala.Any;]
        // |--------------------Test segment---------------------|-|-------------------Param1 segment----------------------|+|-------------------Param2 segment----------------------|
        //                                                     variance                                                  variance

        val toCompact = Map(
            "java.lang.Object" -> "!O",
            "scala.Matchable"  -> "!M",
            "scala.Any"        -> "!A",
            "java.lang.String" -> "!S"
        )

        val fromCompact = toCompact.map(_.swap).toMap

        def isSubtype(subTag: Tag[?], superTag: Tag[?]): Boolean =
            checkType(subTag.tpe, superTag.tpe, 0, 0)

        def checkType(subTag: String, superTag: String, subIdx: Int, superIdx: Int): Boolean =
            checkSegment(subTag, superTag, subIdx, superIdx) && checkParams(subTag, superTag, subIdx, superIdx)

        def checkSegment(subTag: String, superTag: String, subIdx: Int, superIdx: Int): Boolean =
            val superSize = decodeInt(superTag.charAt(superIdx))
            @tailrec def loop(subIdx: Int): Boolean =
                if subIdx >= subTag.length() || superIdx >= superTag.length() then false
                else
                    val subSize = decodeInt(subTag.charAt(subIdx))
                    (subSize == superSize && subTag.regionMatches(subIdx + 1, superTag, superIdx + 1, subSize)) ||
                    loop(subIdx + subSize + 3)
                end if
            end loop
            loop(subIdx)
        end checkSegment

        def nextSegmentEntry(tag: String, idx: Int): Int =
            @tailrec def loop(idx: Int): Int =
                if idx >= tag.length() then -1
                else
                    (tag.charAt(idx): @switch) match
                        case ';'             => idx + 1
                        case '[' | ',' | ']' => -1
                        case _               => loop(idx + 1)
            loop(idx)
        end nextSegmentEntry

        def sameType(subTag: String, superTag: String, subIdx: Int, superIdx: Int): Boolean =
            val endSubIdx   = subTag.indexOf(';', subIdx)
            val endSuperIdx = superTag.indexOf(';', superIdx)
            if endSubIdx == -1 || endSuperIdx == -1 then false
            else
                val length = Math.min(endSubIdx - subIdx, endSuperIdx - superIdx)
                subTag.regionMatches(subIdx, superTag, superIdx, length) &&
                checkParams(subTag, superTag, endSubIdx + 1, endSuperIdx + 1)
            end if
        end sameType

        def checkParams(subTag: String, superTag: String, subIdx: Int, superIdx: Int): Boolean =
            val subStart   = subTag.indexOf('[', subIdx)
            val superStart = subTag.indexOf('[', superIdx)
            if subStart == -1 || superStart == -1 then
                superStart == superStart
            else
                @tailrec def loop(subIdx: Int, superIdx: Int): Boolean =
                    val variance = subTag.charAt(subIdx)
                    variance == superTag.charAt(superIdx) && {
                        (variance: @switch) match
                            case '=' =>
                                sameType(subTag, superTag, subIdx + 1, superIdx + 1)
                            case '+' =>
                                checkType(subTag, superTag, subIdx + 1, superIdx + 1)
                            case '-' =>
                                checkType(superTag, subTag, superIdx + 1, subIdx + 1)
                        end match
                    } && {
                        val nextSubParam   = nextParam(subTag, subIdx + 1)
                        val nextSuperParam = nextParam(superTag, superIdx + 1)
                        if nextSubParam == -1 || nextSuperParam == -1 then
                            nextSubParam == nextSuperParam
                        else
                            loop(nextSubParam, nextSuperParam)
                        end if
                    }
                end loop
                loop(subStart + 1, superStart + 1)
            end if
        end checkParams

        def nextParam(tag: String, idx: Int): Int =
            @tailrec def loop(opens: Int, idx: Int): Int =
                (tag.charAt(idx): @switch) match
                    case ',' =>
                        if opens == 0 then
                            idx + 1
                        else
                            loop(opens, idx + 1)
                    case ']' =>
                        if opens == 0 then
                            -1
                        else
                            loop(opens - 1, idx + 1)
                    case '[' => loop(opens + 1, idx + 1)
                    case _   => loop(opens, idx + 1)
            loop(0, idx)
        end nextParam

        // Macro methods

        def tagImpl[T: Type](using Quotes): Expr[Tag[T]] =
            import quotes.reflect.*

            val encoded = encodeType(TypeRepr.of[T])
            '{ new Tag($encoded) }
        end tagImpl

        def tags[T: Type](using q: Quotes)(flatten: q.reflect.TypeRepr => Seq[q.reflect.TypeRepr]): Expr[Seq[Tag[?]]] =
            import quotes.reflect.*
            Expr.ofSeq {
                flatten(TypeRepr.of[T]).foldLeft(Seq.empty[Expr[Tag[?]]]) {
                    (acc, tpe) =>
                        tpe.asType match
                            case '[t] =>
                                Expr.summon[Tag[t]] match
                                    case None =>
                                        failMissing(tpe)
                                    case Some(tag) =>
                                        acc :+ tag
                        end match
                }
            }
        end tags

        def unionImpl[T: Type](using q: Quotes): Expr[Union[T]] =
            import quotes.reflect.*

            def flatten(tpe: TypeRepr): Seq[TypeRepr] =
                tpe match
                    case OrType(a, b) => flatten(a) ++ flatten(b)
                    case tpe: AndType => report.errorAndAbort(s"Union tags don't support type intersections. Found: ${tpe.show}")
                    case tpe          => Seq(tpe)

            '{ Union(${ tags(using q)(flatten) }) }
        end unionImpl

        def intersectionImpl[T: Type](using q: Quotes): Expr[Intersection[T]] =
            import quotes.reflect.*

            def flatten(tpe: TypeRepr): Seq[TypeRepr] =
                tpe match
                    case AndType(a, b) => flatten(a) ++ flatten(b)
                    case tpe: OrType   => report.errorAndAbort(s"Intersection tags don't support type unions. Found: ${tpe.show}")
                    case tpe           => Seq(tpe)

            '{ Intersection(${ tags(using q)(flatten) }) }
        end intersectionImpl

        // printable ASCII chars
        val maxEncodableInt = ('~' - ' ').toInt

        def encodeInt(i: Int)(using Quotes): Char =
            import quotes.reflect.*
            if i >= maxEncodableInt then
                report.errorAndAbort("Encoded tag string exceeds supported limit: " + maxEncodableInt)
            (i + ' ').toChar
        end encodeInt

        def decodeInt(i: Char): Int =
            (i - ' ').toInt

        def encodeType(using Quotes)(tpe: quotes.reflect.TypeRepr): Expr[String] =
            import quotes.reflect.*

            tpe.dealias match
                case AndType(_, _) | OrType(_, _) =>
                    report.errorAndAbort(
                        s"Unsupported intersection or union type in Tag: ${tpe.show}. Only class types and type parameters are allowed."
                    )
                case tpe if tpe.typeSymbol.fullName == "scala.Nothing" =>
                    report.errorAndAbort(
                        s"Tag cannot be created for Nothing as it has no values. Use a different type or add explicit type parameters if needed."
                    )
                case tpe if tpe.typeSymbol.isClassDef =>
                    val sym = tpe.typeSymbol
                    val path = tpe.dealias.baseClasses.map { sym =>
                        val name = toCompact.getOrElse(sym.fullName, sym.fullName)
                        val size = encodeInt(name.length())
                        val hash = encodeInt(Math.abs(name.hashCode()) % maxEncodableInt)
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
                                    '{ $value.tpe }
                        case _ =>
                            report.errorAndAbort(s"Tag only supports class types and type parameters, but got: ${tpe.show}")
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
    end internal
end Tag
