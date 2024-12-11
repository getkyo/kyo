package kyo

import kyo.Schedule.done
import scala.language.implicitConversions

/** Provides Text representation of a type. Needed for customizing how to display opaque types as alternative to toString
  */
abstract class AsText[A]:
    def asText(value: A): Text

sealed trait LowPriorityAsTexts:
    given [A]: AsText[A] with
        def asText(value: A): Text = value.toString

object AsText extends LowPriorityAsTexts:
    inline def apply[A](using sh: AsText[A]): AsText[A] = sh

    def asText[A](value: A)(using sh: AsText[A]): Text = sh.asText(value)

    import scala.compiletime.*

    private inline def sumAsText[A, M <: scala.deriving.Mirror.ProductOf[A]](label: String, mir: M): AsText[A] =
        val shows = summonAll[Tuple.Map[mir.MirroredElemTypes, AsText]]
        new AsText[A]:
            def asText(value: A): String =
                val builder = java.lang.StringBuilder()
                builder.append(label)
                builder.append("(")
                val valIter                         = value.asInstanceOf[Product].productIterator
                val showIter: Iterator[AsText[Any]] = shows.productIterator.asInstanceOf
                if valIter.hasNext then
                    builder.append(showIter.next().asText(valIter.next()))
                    ()
                while valIter.hasNext do
                    builder.append(",")
                    builder.append(showIter.next().asText(valIter.next()))
                    ()
                end while
                builder.append(")")
                builder.toString()
            end asText
        end new
    end sumAsText

    inline given [A](using mir: scala.deriving.Mirror.Of[A]): AsText[A] = inline mir match
        case sumMir: scala.deriving.Mirror.SumOf[?] =>
            val shows = summonAll[Tuple.Map[sumMir.MirroredElemTypes, AsText]]
            new AsText[A]:
                def asText(value: A): Text =
                    val caseIndex                 = sumMir.ordinal(value)
                    val showInstance: AsText[Any] = shows.productElement(caseIndex).asInstanceOf
                    showInstance.asText(value)
                end asText
            end new
        case singMir: scala.deriving.Mirror.Singleton =>
            val label: String = constValue[singMir.MirroredLabel]
            new AsText[A]:
                def asText(value: A): Text = label
        case prodMir: scala.deriving.Mirror.ProductOf[?] => inline erasedValue[A] match
                case _: Tuple =>
                    inline erasedValue[prodMir.MirroredElemTypes] match
                        case _: EmptyTuple =>
                            new AsText[A]:
                                def asText(value: A): Text = "()"
                        case _ =>
                            sumAsText[A, prodMir.type]("", prodMir)
                case _ =>
                    val label: String = constValue[prodMir.MirroredLabel]
                    inline erasedValue[prodMir.MirroredElemTypes] match
                        case _: EmptyTuple =>
                            new AsText[A]:
                                def asText(value: A): Text = label + "()"
                        case _ =>
                            sumAsText[A, prodMir.type](label, prodMir)
                    end match

end AsText

sealed trait IsText:
    private[kyo] def textValue: Text

object IsText:
    given [A](using at: AsText[A]): Conversion[A, IsText] with
        def apply(a: A): IsText =
            new IsText:
                private[kyo] def textValue: Text = at.asText(a)
    end given
end IsText

extension (sc: StringContext)
    def txt(args: IsText*): Text =
        StringContext.checkLengths(args, sc.parts)
        val pi         = sc.parts.iterator
        val ai         = args.iterator
        var text: Text = pi.next()
        while ai.hasNext do
            text = text + ai.next.textValue
            text = text + StringContext.processEscapes(pi.next())
        text
