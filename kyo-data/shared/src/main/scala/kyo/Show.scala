package kyo

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

    inline given [A](using mir: scala.deriving.Mirror.Of[A]): Show[A] = inline mir match
        case sumMir: scala.deriving.Mirror.SumOf[?] =>
            val shows: List[Show[Any]] = summonAll[Tuple.Map[sumMir.MirroredElemTypes, Show]].toList.asInstanceOf
            new Show[A]:
                def show(value: A): String =
                    val caseIndex    = sumMir.ordinal(value)
                    val showInstance = shows(caseIndex)
                    showInstance.show(value)
                end show
            end new
        case singMir: scala.deriving.Mirror.Singleton =>
            val label: String = constValue[singMir.MirroredLabel]
            new Show[A]:
                def show(value: A): String = label
        case prodMir: scala.deriving.Mirror.ProductOf[?] => inline erasedValue[A] match
                case _: Tuple =>
                    val shows         = summonAll[Tuple.Map[prodMir.MirroredElemTypes, Show]]
                    val label: String = ""
                    new Show[A]:
                        def show(value: A): String =
                            val innerValues: List[Any] = value.asInstanceOf[Product].productIterator.toList
                            val values: List[String] = shows.toList.zipWithIndex.map:
                                case (sh: Show[Any] @unchecked, i) =>
                                    sh.show(value.asInstanceOf[Product].productElement(i))
                            values.mkString(label + "(", ",", ")")
                        end show
                    end new
                case _ =>
                    val shows         = summonAll[Tuple.Map[prodMir.MirroredElemTypes, Show]]
                    val label: String = constValue[prodMir.MirroredLabel]
                    new Show[A]:
                        def show(value: A): String =
                            val innerValues: List[Any] = value.asInstanceOf[Product].productIterator.toList
                            val values: List[String] = shows.toList.zipWithIndex.map:
                                case (sh: Show[Any] @unchecked, i) =>
                                    sh.show(value.asInstanceOf[Product].productElement(i))
                            values.mkString(label + "(", ",", ")")
                        end show
                    end new
        case _ =>
            new Show[A]:

                def show(value: A): String =
                    throw Exception(s"TO STRING: $value")
                    value.toString

end Show

sealed trait Shown:
    private[kyo] def shownValue: String

given [A](using sh: Show[A]): Conversion[A, Shown] with
    def apply(a: A): Shown =
        new Shown:
            def shownValue: String = sh.show(a)
end given

extension (sc: StringContext)
    def k(args: Shown*): String = sc.s(args.map(_.shownValue)*)
