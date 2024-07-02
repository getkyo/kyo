package kyo2.kernel

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

        def show: String = parse.toString
    end extension

    implicit inline def derive: Frame = ${ frameImpl }

    private def frameImpl(using Quotes): Expr[String] =
        import quotes.reflect.*

        val pos                      = quotes.reflect.Position.ofMacroExpansion
        val (startLine, startColumn) = (pos.startLine, pos.startColumn)
        val endLine                  = pos.endLine

        val fileContent =
            pos.sourceFile.content
                .getOrElse(report.errorAndAbort("Can't locate source code to generate frame."))

        val markedContent = fileContent.take(pos.end) + "📍" + fileContent.drop(pos.end)
        val lines         = markedContent.linesIterator.toList
        val snippetLines  = lines.slice(startLine - 1, endLine + 2).filter(_.exists(_ != ' '))
        val toDrop        = snippetLines.map(_.takeWhile(_ == ' ').size).minOption.getOrElse(0)

        def parseSnippetShort(l: List[Char], closes: Int = 0, acc: List[Char] = Nil): List[Char] =
            l match
                case Nil if closes == 0 => acc
                case Nil                => Nil
                case '(' :: tail        => parseSnippetShort(tail, closes + 1, '(' :: acc)
                case ')' :: tail        => parseSnippetShort(tail, closes - 1, ')' :: acc)
                case '\n' :: _          => acc
                case ' ' :: tail if closes == 0 =>
                    acc
                case c :: tail => parseSnippetShort(tail, closes, c :: acc)

        val snippet = snippetLines.map(_.drop(toDrop)).mkString("\n")
        val cls     = findEnclosing(_.isClassDef)
        val method  = findEnclosing(_.isDefDef)

        Expr(s"$cls£$method£${pos.sourceFile.name}£${startLine + 1}£${startColumn + 1}£$snippet")
    end frameImpl

    private def findEnclosing(using Quotes)(predicate: quotes.reflect.Symbol => Boolean): String =
        import quotes.reflect.*

        @tailrec
        def findSymbol(sym: Symbol): Symbol =
            if predicate(sym) then sym
            else if sym.isNoSymbol then Symbol.noSymbol
            else findSymbol(sym.owner)

        val symbol = findSymbol(Symbol.spliceOwner)
        if symbol.isClassDef then symbol.fullName
        else if symbol.isDefDef then symbol.name
        else "Unknown"
    end findEnclosing
end Frame
