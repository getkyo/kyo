package kyo.kernel

import kyo.Ansi
import kyo.Maybe
import scala.annotation.tailrec
import scala.quoted.*

opaque type Frame <: AnyRef = String

object Frame:

    private val snippetShortMaxChars = 50

    given CanEqual[Frame, Frame] = CanEqual.derived

    case class Parsed(
        declaringClass: String,
        methodName: String,
        position: Position,
        snippetShort: String,
        snippetLong: String
    ) derives CanEqual:

        def show: String =
            Ansi.highlight(s"// $position $declaringClass $methodName", snippetLong, s"", position.lineNumber)

        override def toString = s"Frame($declaringClass, $methodName, $position, $snippetShort)"
    end Parsed

    case class Position(
        fileName: String,
        lineNumber: Int,
        columnNumber: Int
    ) derives CanEqual:
        override def toString = s"$fileName:$lineNumber:$columnNumber"
    end Position

    extension (t: Frame)
        def parse: Parsed =
            val arr = t.split('£')
            Parsed(
                arr(0),
                arr(1),
                Position(arr(2), arr(3).toInt, arr(4).toInt),
                arr(5).split("📍")(0).reverse.take(snippetShortMaxChars).takeWhile(_ != '\n').trim.reverse,
                arr(5)
            )
        end parse

        def show: String = parse.show
    end extension

    implicit inline def derive: Frame = ${ frameImpl(false) }

    private[kyo] inline def internal: Frame = ${ frameImpl(true) }

    private val allowKyoFileSuffixes = Set("Test.scala", "Spec.scala", "Bench.scala")

    private def frameImpl(internal: Boolean)(using Quotes): Expr[String] =
        import quotes.reflect.*

        val pos                      = quotes.reflect.Position.ofMacroExpansion
        val (startLine, startColumn) = (pos.startLine, pos.startColumn)
        val endLine                  = pos.endLine

        if !internal then
            findEnclosing { sym =>
                val fileName = pos.sourceFile.name
                sym.fullName.startsWith("kyo") && !allowKyoFileSuffixes.exists(fileName.endsWith)
            }.foreach { sym =>
                report.errorAndAbort(
                    s"""Frame cannot be derived within the kyo package: ${sym.owner.fullName}
                       |
                       |To resolve this issue:
                       |1. Propagate the Frame from user code via a `using` parameter.
                       |2. If absolutely necessary, you can use Frame.internal, but this is not recommended for general use.
                       |
                       |Example of propagating Frame:
                       |def myMethod[A](a: A)(using Frame): Unit = ...""".stripMargin
                )
            }
        end if

        val fileContent =
            pos.sourceFile.content
                .getOrElse(report.errorAndAbort("Can't locate source code to generate frame."))

        val markedContent = fileContent.take(pos.end) + "📍" + fileContent.drop(pos.end)
        val lines         = markedContent.linesIterator.toList
        val snippetLines  = lines.slice(startLine - 1, endLine + 2).filter(_.exists(_ != ' '))
        val toDrop        = snippetLines.map(_.takeWhile(_ == ' ').size).minOption.getOrElse(0)
        val snippet       = snippetLines.map(_.drop(toDrop)).mkString("\n")
        val cls           = findEnclosing(_.isClassDef).map(show).getOrElse("?")
        val method        = findEnclosing(_.isDefDef).map(show).getOrElse("?")

        Expr(s"$cls£$method£${pos.sourceFile.name}£${startLine + 1}£${startColumn + 1}£$snippet")
    end frameImpl

    private def show(using Quotes)(symbol: quotes.reflect.Symbol): String =
        import quotes.reflect.*
        if symbol.isClassDef then symbol.fullName
        else if symbol.isDefDef then symbol.name
        else ""
    end show

    private def findEnclosing(using Quotes)(predicate: quotes.reflect.Symbol => Boolean): Maybe[quotes.reflect.Symbol] =
        import quotes.reflect.*

        @tailrec
        def findSymbol(sym: Symbol): Maybe[Symbol] =
            if predicate(sym) then Maybe(sym)
            else if sym.isNoSymbol then Maybe.empty
            else findSymbol(sym.owner)

        findSymbol(Symbol.spliceOwner)
    end findEnclosing
end Frame
