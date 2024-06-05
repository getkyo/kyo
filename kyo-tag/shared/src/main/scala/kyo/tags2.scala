package kyo2

import scala.annotation.tailrec
import scala.collection.immutable
import scala.quoted.*

opaque type Tag[T] = String

object Tag:

    import internal.*

    inline given apply[T]: Tag[T] = ${ tagImpl[T] }

    extension [T](t1: Tag[T])

        def show: String =
            val decoded = t1.drop(2).takeWhile(_ != ';')
            fromCompact.getOrElse(decoded, decoded)

        infix def <:<[U](t2: Tag[U]): Boolean =
            t1 =:= t2 || isSubtype(t1, t2)

        infix def =:=[U](t2: Tag[U]): Boolean =
            (t1 eq t2) || t1 == t2

        infix def =!=[U](t2: Tag[U]): Boolean =
            !(t1 =:= t2)

        infix def >:>[U](t2: Tag[U]): Boolean =
            t2 <:< t1

        def erased: Tag[Any] = t1.asInstanceOf[Tag[Any]]

    end extension

    private object internal:

        ////////////////////
        // Sub-type check //
        ////////////////////

        def isSubtype(subTag: String, superTag: String): Boolean =
            checkType(subTag, superTag, 0, 0, false).isValid

        def checkType(subTag: String, superTag: String, subIdx: Int, superIdx: Int, equality: Boolean): Position =
            val subKind   = subTag.charAt(subIdx)
            val superKind = superTag.charAt(superIdx)

            subKind match
                case Kind.single =>
                    superKind match
                        case Kind.single       => checkSingle(subTag, superTag, subIdx + 1, superIdx + 1, equality)
                        case Kind.union        => ??? // t2.tags.exists(t1 <:< _)
                        case Kind.intersection => ??? // t2.tags.forall(_ <:< t1)
                case Kind.union =>
                    superKind match
                        case Kind.union => ??? // t1.tags.forall(tag1 => t2.tags.exists(tag1 <:< _))
                        case _          => ??? // t1.tags.forall(_ <:< t2)
                case Kind.intersection =>
                    superKind match
                        case Kind.intersection => ??? // t2.tags.forall(tag2 => t1.tags.exists(_ <:< tag2))
                        case _                 => ??? // t1.tags.exists(_ <:< t2)
            end match
        end checkType

        // def checkExists(subTag: String, superTag: String, subIdx: Int, superIdx: Int, equality: Boolean): Position =
        //     val subSize = IntEncoder.decode(subTag.charAt(subIdx))
        //     @tailrec def loop(i: Int, subIdx: Int, superIdx: Int, found: Boolean): Position =
        //         if i < subSize then
        //             val nextPos = checkType(subTag, superTag, subIdx, superIdx, equality)
        //             loop(i + 1, nextPos.subIdx, superIdx, found || nextPos.isValid)
        //         else if found then Position(subIdx, superIdx)
        //         else Position.invalid(subIdx, superIdx)
        //     loop(0, subIdx + 1, superIdx, false)
        // end checkExists

        // def checkForall(subTag: String, superTag: String, subIdx: Int, superIdx: Int, equality: Boolean): Position =
        //     val subSize = IntEncoder.decode(subTag.charAt(subIdx))
        //     @tailrec def loop(i: Int, pos: Position): Position =
        //         if i < subSize then
        //             val nextPos = checkType(subTag, superTag, pos.subIdx, superIdx, equality)
        //             if nextPos.isValid then
        //                 loop(i + 1, nextPos)
        //             else
        //                 nextPos
        //             end if
        //         else pos
        //     loop(0, Position(subIdx + 1, superIdx))
        // end checkForall

        def checkSingle(subTag: String, superTag: String, subIdx: Int, superIdx: Int, equality: Boolean): Position =
            val subTotalBasesSize   = IntEncoder.decode(subTag.charAt(subIdx))
            val subParamsSize       = IntEncoder.decode(subTag.charAt(subIdx + 1))
            val superTotalBasesSize = IntEncoder.decode(superTag.charAt(superIdx))
            val superParamsSize     = IntEncoder.decode(superTag.charAt(superIdx + 1))
            val subBasesEnd         = subIdx + subTotalBasesSize + 2
            val superBasesEnd       = superIdx + superTotalBasesSize + 2
            @tailrec def checkBases(subIdx: Int, superIdx: Int): Position =
                if subIdx >= subBasesEnd then
                    Position.invalid(subIdx, superIdx)
                else
                    val subHash   = subTag.charAt(subIdx)
                    val subSize   = IntEncoder.decode(subTag.charAt(subIdx + 1))
                    val superHash = superTag.charAt(superIdx)
                    if subHash == superHash && subTag.regionMatches(subIdx + 2, superTag, superIdx + 2, subSize) then
                        checkParams(subTag, superTag, subBasesEnd, superBasesEnd, subParamsSize, superParamsSize, equality)
                    else if !equality then
                        checkBases(subIdx + subSize + 2, superIdx)
                    else
                        Position.invalid(subIdx, superIdx)
                    end if
            checkBases(subIdx + 2, superIdx + 2)
        end checkSingle

        def checkParams(
            subTag: String,
            superTag: String,
            subIdx: Int,
            superIdx: Int,
            subParamsSize: Int,
            superParamsSize: Int,
            equality: Boolean
        ): Position =
            @tailrec def loop(i: Int, j: Int, pos: Position): Position =
                if i >= subParamsSize || j >= superParamsSize then
                    if i >= subParamsSize && j >= superParamsSize then pos else Position.invalid(i, j)
                else
                    val idx1 = pos.subIdx
                    val idx2 = pos.superIdx
                    subTag.charAt(idx1) match
                        case Variance.covariant =>
                            val nextPos = checkType(subTag, superTag, idx1 + 1, idx2 + 1, equality)
                            if nextPos.isValid then loop(i + 1, j + 1, nextPos) else nextPos
                        case Variance.invariant =>
                            val nextPos = checkType(subTag, superTag, idx1 + 1, idx2 + 1, true)
                            if nextPos.isValid then loop(i + 1, j + 1, nextPos) else nextPos
                        case Variance.contravariant =>
                            val nextPos = checkType(superTag, subTag, idx2 + 1, idx1 + 1, equality)
                            if nextPos.isValid then loop(i + 1, j + 1, Position(nextPos.superIdx, nextPos.subIdx)) else nextPos
                    end match
            loop(0, 0, Position(subIdx, superIdx))
        end checkParams

        ///////////
        // Utils //
        ///////////

        opaque type Position = Long

        object Position:
            def apply(subIdx: Int, superIdx: Int, isValid: Boolean = true): Position =
                val validBit = if isValid then 0L else 1L
                (validBit << 63) | (subIdx.toLong << 32) | superIdx.toLong
            def invalid(subIdx: Int, superIdx: Int): Position =
                Position(subIdx, superIdx, false)
        end Position

        extension (pos: Position)
            def subIdx: Int       = ((pos >> 32) & 0x7fffffff).toInt
            def superIdx: Int     = (pos & 0xffffffff).toInt
            def isValid: Boolean  = (pos >>> 63) == 0L
            def invalid: Position = Position(subIdx, superIdx, false)
            def valid: Position   = Position(subIdx, superIdx, true)
            def swap: Position    = Position(superIdx, subIdx)
        end extension

        object Kind:
            val single       = 't'
            val union        = 'u'
            val intersection = 'i'
        end Kind

        object Variance:
            val invariant     = '='
            val covariant     = '+'
            val contravariant = '-'
        end Variance

        object IntEncoder:
            private val latin1Chars = ('\u0000' to '\u00FF').toArray

            def encode(i: Int): Char =
                if i >= latin1Chars.length || i < 0 then
                    throw new Exception(s"Encoded tag 'Int($i)' exceeds supported limit: " + latin1Chars.length)
                latin1Chars(i)
            end encode

            def decode(c: Char): Int =
                c - '\u0000'

            def encodeHash(hash: Int): Char =
                encode(Math.abs(hash) % latin1Chars.length)
        end IntEncoder

        ///////////////////
        // Macro methods //
        ///////////////////

        def tagImpl[T: Type](using Quotes): Expr[Tag[T]] =
            import quotes.reflect.*
            encodeType(TypeRepr.of[T])
        end tagImpl

        def encodeType(using Quotes)(tpe: quotes.reflect.TypeRepr): Expr[String] =
            import quotes.reflect.*

            tpe.dealias match
                case tpe @ AndType(_, _) =>
                    val types =
                        flatten(tpe) {
                            case AndType(a, b) => List(a, b)
                        }.map(encodeType)
                    concat(Expr(s"${Kind.intersection}${IntEncoder.encode(types.size)}") :: types)
                case OrType(_, _) =>
                    val types =
                        flatten(tpe) {
                            case OrType(a, b) => List(a, b)
                        }.map(encodeType)
                    concat(Expr(s"${Kind.union}${IntEncoder.encode(types.size)}") :: types)
                case tpe if tpe.typeSymbol.isClassDef =>
                    val bases          = encodeBases(tpe)
                    val totalBasesSize = bases.map(_.size).sum

                    val variances  = encodeVariances(tpe)
                    val paramTypes = tpe.typeArgs.map(encodeType)
                    require(variances.size == paramTypes.size)
                    val params = variances.zip(paramTypes).flatMap((v, t) => Expr(v) :: t :: Nil)

                    val header = Expr(s"${Kind.single}${IntEncoder.encode(totalBasesSize)}${IntEncoder.encode(paramTypes.size)}")
                    concat(header :: bases.map(Expr(_)) ::: params)
                case _ =>
                    tpe.asType match
                        case '[tpe] =>
                            Expr.summon[Tag[tpe]] match
                                case None =>
                                    report.errorAndAbort(s"Please provide an implicit kyo.Tag[${tpe.show}] parameter.")
                                case Some(value) =>
                                    value
            end match
        end encodeType

        def encodeBases(using Quotes)(tpe: quotes.reflect.TypeRepr): List[String] =
            tpe.baseClasses.map { sym =>
                val name = toCompact.getOrElse(sym.fullName, sym.fullName)
                val size = IntEncoder.encode(name.length())
                val hash = IntEncoder.encodeHash(name.hashCode())
                s"$hash$size$name"
            }

        def encodeVariances(using Quotes)(tpe: quotes.reflect.TypeRepr): List[String] =
            import quotes.reflect.*
            tpe.typeSymbol.typeMembers.flatMap { v =>
                if !v.isTypeParam then None
                else if v.flags.is(Flags.Contravariant) then Some(Variance.contravariant)
                else if v.flags.is(Flags.Covariant) then Some(Variance.covariant)
                else Some(Variance.invariant)
            }.map(_.toString)
        end encodeVariances

        def flatten(using
            Quotes
        )(tpe: quotes.reflect.TypeRepr)(pf: PartialFunction[quotes.reflect.TypeRepr, List[quotes.reflect.TypeRepr]]) =
            import quotes.reflect.*
            def loop(tpe: TypeRepr): List[TypeRepr] =
                tpe match
                    case pf(l) => l.flatMap(loop)
                    case tpe   => List(tpe)

            loop(tpe)
        end flatten

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

        val toCompact = Map[String, String](
            // "java.lang.Object"                              -> "0",
            // "scala.Matchable"                               -> "1",
            // "scala.Any"                                     -> "2",
            // "scala.AnyVal"                                  -> "3",
            // "java.lang.String"                              -> "4",
            // "scala.Int"                                     -> "5",
            // "scala.Long"                                    -> "6",
            // "scala.Float"                                   -> "7",
            // "scala.Double"                                  -> "8",
            // "scala.Boolean"                                 -> "9",
            // "scala.Unit"                                    -> "a",
            // "scala.Option"                                  -> "b",
            // "scala.Some"                                    -> "c",
            // "scala.None"                                    -> "d",
            // "scala.Left"                                    -> "e",
            // "scala.Right"                                   -> "f",
            // "scala.Tuple2"                                  -> "g",
            // "scala.collection.immutable.List"               -> "h",
            // "scala.collection.immutable.Nil"                -> "i",
            // "scala.collection.immutable.Map"                -> "j",
            // "scala.Nothing"                                 -> "k",
            // "java.lang.CharSequence"                        -> "l",
            // "java.lang.Comparable"                          -> "m",
            // "java.io.Serializable"                          -> "n",
            // "scala.Product"                                 -> "o",
            // "scala.Equals"                                  -> "p",
            // "kyo.core$.Effect"                              -> "q",
            // "kyo.fibersInternal$.FiberGets"                 -> "r",
            // "kyo.Streams"                                   -> "s",
            // "kyo.aborts$package$.Aborts$.internal$.DoAbort" -> "t",
            // "kyo.zios$package$.ZIOs$.internal$.Tasks"       -> "u",
            // "kyo.IOs"                                       -> "v",
            // "scala.Char"                                    -> "x",
            // "java.lang.Throwable"                           -> "y",
            // "java.lang.Exception"                           -> "z"
        )

        val fromCompact = toCompact.map(_.swap).toMap
    end internal

end Tag
