package kyo

import kyo.internal.IntEncoder
import kyo.internal.TagMacro
import scala.annotation.switch
import scala.annotation.tailrec
import scala.collection.immutable
import scala.quoted.*

opaque type Tag[A] = String

object Tag:

    type Full[A] = Tag[A] | Set[A]

    given [A, B]: CanEqual[Full[A], Full[B]] = CanEqual.derived

    import internal.*

    inline given apply[A]: Tag[A] = ${ TagMacro.tagImpl[A] }

    private[kyo] def fromRaw[A](tag: String): Tag[A] = tag

    extension [A](t1: Tag[A])

        inline def erased: Tag[Any] = t1.asInstanceOf[Tag[Any]]

        def showTpe: String =
            val decoded = t1.drop(2).takeWhile(_ != ';')
            TagMacro.fromCompact.getOrElse(decoded, decoded)

        private[kyo] def raw: String = t1

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
        inline given apply[A]: Set[A] = ${ TagMacro.setImpl[A] }

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
        private[kyo] def raw[A](tags: Seq[String]) = new Union[A](tags.asInstanceOf[Seq[Tag[Any]]])
        inline given apply[A]: Union[A]            = ${ TagMacro.unionImpl[A] }

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
        private[kyo] def raw[A](tags: Seq[String]) = new Intersection[A](tags.asInstanceOf[Seq[Tag[Any]]])
        inline given apply[A]: Intersection[A]     = ${ TagMacro.intersectionImpl[A] }

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

    end internal
end Tag
