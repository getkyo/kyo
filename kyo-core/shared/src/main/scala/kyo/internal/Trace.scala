package kyo.internal

import scala.annotation.tailrec
import scala.quoted.*

opaque type Trace = String

object Trace:

    private val maxSnippetLines = 3

    extension (t: Trace)
        def show: String       = s"Trace(${position},method=${method},snippet=${snippet})"
        def position: Position = Position(t.takeWhile(_ != '\n'))
        def method: String     = t.drop(position.show.length + 1).takeWhile(isIdentifierPart)
        def snippet: String    = t.drop(position.show.length + 1)
    end extension

    implicit inline def derive: Trace =
        ${ traceImpl }

    private def traceImpl(using Quotes): Expr[Trace] =
        val position = Position.infer

        import quotes.reflect.*

        val source =
            Position.ofMacroExpansion.sourceFile.content
                .getOrElse(report.errorAndAbort("Can't locate source code to generate trace."))

        @tailrec
        def parse(source: List[Char], acc: List[Char] = Nil, closes: Int = 0): String =
            source match
                case (head @ (')' | ']' | '}')) :: tail => parse(tail, head :: acc, closes + 1)
                case (head @ ('(' | '[' | '{')) :: tail => parse(tail, head :: acc, closes - 1)
                case ' ' :: tail                        => parse(tail, ' ' :: acc, closes)
                case head :: tail if closes > 0 =>
                    parse(tail, head :: acc, closes)
                case '`' :: tail if closes == 0 =>
                    s"`${tail.takeWhile(_ != '`').reverse.mkString}`${acc.mkString}"
                case source =>
                    val method = source.takeWhile(isIdentifierPart).reverse
                    (method ::: acc).mkString
        end parse

        def print(code: String): String =
            code.split('\n').take(maxSnippetLines).toList match
                case Nil         => ""
                case head :: Nil => head
                case head :: tail =>
                    val spaces = tail.map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)
                    (head :: tail.map(_.drop(spaces))).mkString("\n")

        val code = print(parse(source.reverse.drop(source.length() - Position.ofMacroExpansion.start).toList))
        Expr(s"$position\n$code")
    end traceImpl

    private def isIdentifierPart(c: Char) =
        (c == '_') || (c == '$') || Character.isUnicodeIdentifierStart(c) || "!#%&*+-/:<=>?@\\^|~".contains(c)

end Trace
