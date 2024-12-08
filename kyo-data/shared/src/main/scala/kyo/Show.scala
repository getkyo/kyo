package kyo

import kyo.Schedule.done
import scala.language.implicitConversions

/** Provides string representation of a type as an alternative to .toString. Needed for customizing how to display opaque types
  */
trait Show[A]:
    def show(value: A): String

sealed trait LowPriorityShows:
    given [A]: Show[A] with
        def show(value: A): String = value.toString

object Show extends LowPriorityShows:
    inline def apply[A](using sh: Show[A]): Show[A] = sh

    def show[A](value: A)(using sh: Show[A]): String = sh.show(value)

    import scala.compiletime.*

    private inline def sumShow[A, M <: scala.deriving.Mirror.ProductOf[A]](label: String, mir: M): Show[A] =
        val shows = summonAll[Tuple.Map[mir.MirroredElemTypes, Show]]
        new Show[A]:
            def show(value: A): String =
                val builder = java.lang.StringBuilder()
                builder.append(label)
                builder.append("(")
                val valIter                       = value.asInstanceOf[Product].productIterator
                val showIter: Iterator[Show[Any]] = shows.productIterator.asInstanceOf
                if valIter.hasNext then
                    builder.append(showIter.next().show(valIter.next()))
                    ()
                while valIter.hasNext do
                    builder.append(",")
                    builder.append(showIter.next().show(valIter.next()))
                    ()
                end while
                builder.append(")")
                builder.toString()
            end show
        end new
    end sumShow

    inline given [A](using mir: scala.deriving.Mirror.Of[A]): Show[A] = inline mir match
        case sumMir: scala.deriving.Mirror.SumOf[?] =>
            val shows = summonAll[Tuple.Map[sumMir.MirroredElemTypes, Show]]
            new Show[A]:
                def show(value: A): String =
                    val caseIndex               = sumMir.ordinal(value)
                    val showInstance: Show[Any] = shows.productElement(caseIndex).asInstanceOf
                    showInstance.show(value)
                end show
            end new
        case singMir: scala.deriving.Mirror.Singleton =>
            val label: String = constValue[singMir.MirroredLabel]
            new Show[A]:
                def show(value: A): String = label
        case prodMir: scala.deriving.Mirror.ProductOf[?] => inline erasedValue[A] match
                case _: Tuple =>
                    inline erasedValue[prodMir.MirroredElemTypes] match
                        case _: EmptyTuple =>
                            new Show[A]:
                                def show(value: A): String = "()"
                        case _ =>
                            sumShow[A, prodMir.type]("", prodMir)
                case _ =>
                    val label: String = constValue[prodMir.MirroredLabel]
                    inline erasedValue[prodMir.MirroredElemTypes] match
                        case _: EmptyTuple =>
                            new Show[A]:
                                def show(value: A): String = label + "()"
                        case _ =>
                            sumShow[A, prodMir.type](label, prodMir)
                    end match
        case _ =>
            new Show[A]:

                def show(value: A): String =
                    throw Exception(s"TO STRING: $value")
                    value.toString

end Show

sealed trait Shown:
    private[kyo] def shownValue: String

object Shown:
    given [A](using sh: Show[A]): Conversion[A, Shown] with
        def apply(a: A): Shown =
            new Shown:
                private[kyo] def shownValue: String = sh.show(a)
    end given
end Shown

extension (sc: StringContext)
    def k(args: Shown*): String = sc.s(args.map(_.shownValue)*)
