package kyo

import kyo.Schedule.done
import scala.language.implicitConversions

/** Provides Text representation of a type. Needed for customizing how to display opaque types as alternative to toString
  */
abstract class Render[A]:
    def asText(value: A): Text
    final def asString(value: A): String = asText(value).show

sealed trait LowPriorityRenders:
    given [A]: Render[A] with
        def asText(value: A): Text = value.toString

object Render extends LowPriorityRenders:
    inline def apply[A](using r: Render[A]): Render[A] = r

    def asText[A](value: A)(using r: Render[A]): Text = r.asText(value)

    import scala.compiletime.*

    private inline def sumRender[A, M <: scala.deriving.Mirror.ProductOf[A]](label: String, mir: M): Render[A] =
        val shows = summonAll[Tuple.Map[mir.MirroredElemTypes, Render]]
        new Render[A]:
            def asText(value: A): String =
                val builder = java.lang.StringBuilder()
                builder.append(label)
                builder.append("(")
                val valIter                         = value.asInstanceOf[Product].productIterator
                val showIter: Iterator[Render[Any]] = shows.productIterator.asInstanceOf
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
    end sumRender

    inline given [A](using mir: scala.deriving.Mirror.Of[A]): Render[A] = inline mir match
        case sumMir: scala.deriving.Mirror.SumOf[?] =>
            val shows = summonAll[Tuple.Map[sumMir.MirroredElemTypes, Render]]
            new Render[A]:
                def asText(value: A): Text =
                    val caseIndex                 = sumMir.ordinal(value)
                    val showInstance: Render[Any] = shows.productElement(caseIndex).asInstanceOf
                    showInstance.asText(value)
                end asText
            end new
        case singMir: scala.deriving.Mirror.Singleton =>
            val label: String = constValue[singMir.MirroredLabel]
            new Render[A]:
                def asText(value: A): Text = label
        case prodMir: scala.deriving.Mirror.ProductOf[?] => inline erasedValue[A] match
                case _: Tuple =>
                    inline erasedValue[prodMir.MirroredElemTypes] match
                        case _: EmptyTuple =>
                            new Render[A]:
                                def asText(value: A): Text = "()"
                        case _ =>
                            sumRender[A, prodMir.type]("", prodMir)
                case _ =>
                    val label: String = constValue[prodMir.MirroredLabel]
                    inline erasedValue[prodMir.MirroredElemTypes] match
                        case _: EmptyTuple =>
                            new Render[A]:
                                def asText(value: A): Text = label + "()"
                        case _ =>
                            sumRender[A, prodMir.type](label, prodMir)
                    end match

end Render

sealed trait Rendered:
    private[kyo] def textValue: Text

object Rendered:
    given [A](using r: Render[A]): Conversion[A, Rendered] with
        def apply(a: A): Rendered =
            new Rendered:
                private[kyo] def textValue: Text = r.asText(a)
    end given
end Rendered

extension (sc: StringContext)
    def t(args: Rendered*): Text =
        StringContext.checkLengths(args, sc.parts)
        val pi         = sc.parts.iterator
        val ai         = args.iterator
        var text: Text = pi.next()
        while ai.hasNext do
            text = text + ai.next.textValue
            text = text + StringContext.processEscapes(pi.next())
        text
