package kyo

import scala.language.implicitConversions

/** Provides String representation of a type. Needed for customizing how to display opaque types as alternative to toString
  */
abstract class Render[A] extends Serializable:
    def asString(value: A): String

object Render extends kyo.internal.LowPriorityRenders:
    inline def apply[A](using r: Render[A]): Render[A] = r

    def from[A](impl: A => String): Render[A] =
        new Render[A]:
            def asString(value: A): String = impl(value)

    def asString[A](value: A)(using r: Render[A]): String = r.asString(value)

    import scala.compiletime.*

    private inline def sumRender[A, M <: scala.deriving.Mirror.ProductOf[A]](label: String, mir: M): Render[A] =
        val shows = summonAll[Tuple.Map[mir.MirroredElemTypes, Render]]
        Render.from: (value: A) =>
            val builder = java.lang.StringBuilder()
            builder.append(label)
            builder.append("(")
            val valIter                         = value.asInstanceOf[Product].productIterator
            val showIter: Iterator[Render[Any]] = shows.productIterator.asInstanceOf
            if valIter.hasNext then
                builder.append(showIter.next().asString(valIter.next()))
                ()
            while valIter.hasNext do
                builder.append(",")
                builder.append(showIter.next().asString(valIter.next()))
                ()
            end while
            builder.append(")")
            builder.toString()
    end sumRender

    inline given [A](using mir: scala.deriving.Mirror.Of[A]): Render[A] = inline mir match
        case sumMir: scala.deriving.Mirror.SumOf[?] =>
            val shows = summonAll[Tuple.Map[sumMir.MirroredElemTypes, Render]]
            Render.from: (value: A) =>
                val caseIndex                 = sumMir.ordinal(value)
                val showInstance: Render[Any] = shows.productElement(caseIndex).asInstanceOf
                showInstance.asString(value)
        case singMir: scala.deriving.Mirror.Singleton =>
            val label: String = constValue[singMir.MirroredLabel]
            Render.from(_ => label)
        case prodMir: scala.deriving.Mirror.ProductOf[?] => inline erasedValue[A] match
                case _: Tuple =>
                    inline erasedValue[prodMir.MirroredElemTypes] match
                        case _: EmptyTuple =>
                            Render.from(_ => "()")
                        case _ =>
                            sumRender[A, prodMir.type]("", prodMir)
                case _ =>
                    val label: String = constValue[prodMir.MirroredLabel]
                    inline erasedValue[prodMir.MirroredElemTypes] match
                        case _: EmptyTuple =>
                            Render.from(_ => label + "()")
                        case _ =>
                            sumRender[A, prodMir.type](label, prodMir)
                    end match

    type Rendered = Rendered.Value

    object Rendered:
        opaque type Value <: String = String
        implicit def apply[A](value: A)(using render: Render[A]): Rendered =
            render.asString(value)
    end Rendered

end Render

extension (sc: StringContext)
    def render(args: Render.Rendered*): String =
        StringContext.checkLengths(args, sc.parts)
        val pi      = sc.parts.iterator
        val ai      = args.iterator
        val builder = java.lang.StringBuilder(pi.next())
        while ai.hasNext do
            discard(builder.append(ai.next()))
            discard(builder.append(StringContext.processEscapes(pi.next())))
        builder.toString
end extension
