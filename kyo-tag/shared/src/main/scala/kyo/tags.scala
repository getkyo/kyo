package kyo

import kyo.internal.IntEncoder
import scala.annotation.switch
import scala.annotation.tailrec
import scala.collection.immutable
import scala.quoted.*

opaque type Tag[A] = String

object Tag:

    type Full[A] = Tag[A] | Set[A]

    given [A, B]: CanEqual[Full[A], Full[B]] = CanEqual.derived

    import internal.*

    inline given apply[A]: Tag[A] = ${ tagImpl[A] }

    extension [A](t1: Tag[A])
        inline def erased: Tag[Any] = t1.asInstanceOf[Tag[Any]]
        def showTpe: String =
            val decoded = t1.drop(2).takeWhile(_ != ';')
            fromCompact.getOrElse(decoded, decoded)
    end extension

    extension [A](t1: Full[A])

        def showType: String =
            t1 match
                case s: Tag[?] => s.showTpe
                case s: Set[?] => s.showTpe
        def show: String = s"Tag[${t1.showType}]"

        infix def <:<[B](t2: Full[B]): Boolean =
            t1 match
                case t1: String =>
                    t2 match
                        case t2: String => isSubtype(t1, t2)
                        case t2: Set[?] => t2 >:> t1
                case t1: Set[A] =>
                    t1 <:< t2

        infix def =:=[B](t2: Full[B]): Boolean =
            (t1.asInstanceOf[AnyRef] eq t2.asInstanceOf[AnyRef]) || t1 == t2

        infix def =!=[B](t2: Full[B]): Boolean =
            !(t1 =:= t2)

        infix def >:>[B](t2: Full[B]): Boolean =
            t2 <:< t1

        def erased: Full[Any] = t1.asInstanceOf[Full[Any]]

    end extension

    sealed trait Set[A] extends Any:
        infix def <:<[B](t2: Full[B]): Boolean
        infix def =:=[B](t2: Full[B]): Boolean
        infix def =!=[B](t2: Full[B]): Boolean =
            !(this =:= t2)
        infix def >:>[B](t2: Full[B]): Boolean
        def erased: Set[Any] = this.asInstanceOf[Set[Any]]
        def showTpe: String
    end Set

    object Set:
        inline given apply[A]: Set[A] = ${ setImpl[A] }

    case class Union[A](tags: Seq[Tag[Any]]) extends AnyVal with Set[A]:
        infix def <:<[B](t2: Full[B]): Boolean =
            t2 match
                case t2: Union[?] =>
                    tags.forall(tag1 => t2.tags.exists(tag1 <:< _))
                case _ =>
                    tags.forall(_ <:< t2)

        infix def >:>[B](t2: Full[B]): Boolean =
            t2 match
                case t2: Union[?] =>
                    t2.tags.forall(tag2 => tags.exists(_ >:> tag2))
                case _ =>
                    tags.exists(t2 <:< _)

        infix def =:=[B](t2: Full[B]): Boolean =
            t2 match
                case t2: Union[?] =>
                    tags.forall(tag1 => t2.tags.exists(tag1 =:= _)) &&
                    t2.tags.forall(tag2 => tags.exists(tag2 =:= _))
                case _ =>
                    tags.forall(t2 =:= _)
        def showTpe: String = tags.map(_.showTpe).mkString(" | ")
    end Union

    object Union:
        private[Tag] def raw[A](tags: Seq[String]) = new Union[A](tags.asInstanceOf[Seq[Tag[Any]]])
        inline given apply[A]: Union[A]            = ${ unionImpl[A] }

    case class Intersection[A](tags: Seq[Tag[Any]]) extends AnyVal with Set[A]:

        infix def <:<[B](t2: Full[B]): Boolean =
            t2 match
                case t2: Intersection[?] =>
                    t2.tags.forall(tag2 => tags.exists(_ <:< tag2))
                case _ =>
                    tags.exists(_ <:< t2)

        infix def >:>[B](t2: Full[B]): Boolean =
            t2 match
                case t2: Intersection[?] =>
                    tags.forall(tag1 => t2.tags.exists(tag1 >:> _))
                case _ =>
                    tags.forall(t2 >:> _)

        infix def =:=[B](t2: Full[B]): Boolean =
            t2 match
                case t2: Intersection[?] =>
                    tags.forall(tag1 => t2.tags.exists(tag1 =:= _)) &&
                    t2.tags.forall(tag2 => tags.exists(tag2 =:= _))
                case _ =>
                    tags.forall(_ =:= t2)

        def showTpe: String =
            tags.map(_.showTpe).mkString(" & ")

    end Intersection

    object Intersection:
        private[Tag] def raw[A](tags: Seq[String]) = new Intersection[A](tags.asInstanceOf[Seq[Tag[Any]]])
        inline given apply[A]: Intersection[A]     = ${ intersectionImpl[A] }

    private[Tag] object internal:

        // Example Tag
        //
        // class Super
        // class Param1 extends Super
        // class Param2 extends Super
        // class Test[-A, +B]
        //
        // Tag[Test[Param1, Param2]]
        //
        // Test;Super;java.lang.Object;scala.Matchable;scala.Any;[-Param1;Super;java.lang.Object;scala.Matchable;scala.Any;,+Param2;Super;java.lang.Object;scala.Matchable;scala.Any;]
        // |--------------------Test segment---------------------|-|-------------------Param1 segment----------------------|+|-------------------Param2 segment----------------------|
        //                                                     variance                                                  variance

        def isSubtype(subTag: String, superTag: String): Boolean =
            checkType(subTag, superTag, 0, 0)

        def checkType(subTag: String, superTag: String, subIdx: Int, superIdx: Int): Boolean =
            checkSegment(subTag, superTag, subIdx, superIdx) && checkParams(subTag, superTag, subIdx, superIdx)

        def checkSegment(subTag: String, superTag: String, subIdx: Int, superIdx: Int): Boolean =
            val superSize = IntEncoder.decode(superTag.charAt(superIdx))
            @tailrec def loop(subIdx: Int): Boolean =
                if subIdx >= subTag.length() || superIdx >= superTag.length() then false
                else
                    val subSize = IntEncoder.decode(subTag.charAt(subIdx))
                    (subSize == superSize && subTag.regionMatches(subIdx + 1, superTag, superIdx + 1, subSize)) ||
                    loop(subIdx + subSize + 3)
                end if
            end loop
            loop(subIdx)
        end checkSegment

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
            val superStart = superTag.indexOf('[', superIdx)
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

        def tagImpl[A: Type](using Quotes): Expr[Tag[A]] =
            import quotes.reflect.*

            encodeType(TypeRepr.of[A])
        end tagImpl

        def tags[A: Type](using q: Quotes)(flatten: q.reflect.TypeRepr => Seq[q.reflect.TypeRepr]): Expr[Seq[String]] =
            import quotes.reflect.*
            Expr.ofSeq {
                flatten(TypeRepr.of[A]).foldLeft(Seq.empty[Expr[Tag[Any]]]) {
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
                                    value.asExprOf[String]
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
            "java.lang.Object"                              -> "0",
            "scala.Matchable"                               -> "1",
            "scala.Any"                                     -> "2",
            "scala.AnyVal"                                  -> "3",
            "java.lang.String"                              -> "4",
            "scala.Int"                                     -> "5",
            "scala.Long"                                    -> "6",
            "scala.Float"                                   -> "7",
            "scala.Double"                                  -> "8",
            "scala.Boolean"                                 -> "9",
            "scala.Unit"                                    -> "a",
            "scala.Option"                                  -> "b",
            "scala.Some"                                    -> "c",
            "scala.None"                                    -> "d",
            "scala.Left"                                    -> "e",
            "scala.Right"                                   -> "f",
            "scala.Tuple2"                                  -> "g",
            "scala.collection.immutable.List"               -> "h",
            "scala.collection.immutable.Nil"                -> "i",
            "scala.collection.immutable.Map"                -> "j",
            "scala.Nothing"                                 -> "k",
            "java.lang.CharSequence"                        -> "l",
            "java.lang.Comparable"                          -> "m",
            "java.io.Serializable"                          -> "n",
            "scala.Product"                                 -> "o",
            "scala.Equals"                                  -> "p",
            "kyo.core$.Effect"                              -> "q",
            "kyo.fibersInternal$.FiberGets"                 -> "r",
            "kyo.Streams"                                   -> "s",
            "kyo.aborts$package$.Aborts$.internal$.DoAbort" -> "t",
            "kyo.zios$package$.ZIOs$.internal$.Tasks"       -> "u",
            "kyo.IOs"                                       -> "v",
            "scala.Char"                                    -> "x",
            "java.lang.Throwable"                           -> "y",
            "java.lang.Exception"                           -> "z"
        )

        val fromCompact = toCompact.map(_.swap).toMap
    end internal
end Tag
