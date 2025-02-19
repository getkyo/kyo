package kyo

import kyo.Maybe
import scala.annotation.tailrec
import scala.quoted.*

opaque type Frame <: AnyRef = String

object Frame:

    private val version = '0'

    private val snippetShortMaxChars = 50

    inline given CanEqual[Frame, Frame] = CanEqual.derived
    inline given Flat[Frame]            = Flat.unsafe.bypass

    opaque type Position = String

    object Position:
        extension (self: Position)
            def fileName: String =
                self.substring(1, self.indexOf(':'))

            def lineNumber: Int =
                val start = self.indexOf(':') + 1
                val end   = self.indexOf(':', start)
                self.substring(start, end).toInt
            end lineNumber

            def columnNumber: Int =
                val firstColon = self.indexOf(':')
                val start      = self.indexOf(':', firstColon + 1) + 1
                val end        = self.indexOf('|', start)
                self.substring(start, end).toInt
            end columnNumber

            def show: String =
                self.substring(1, self.indexOf('|'))
        end extension
    end Position

    extension (self: Frame)
        private def findNextSeparator(from: Int): Int =
            self.indexOf('|', from)

        private def parseSection(start: Int, end: Int): String =
            if end < 0 then self.substring(start)
            else self.substring(start, end)

        def position: Position = self

        def className: String =
            val start = findNextSeparator(1) + 1
            parseSection(start, findNextSeparator(start))

        def methodName: String =
            val firstSep = findNextSeparator(1)
            val start    = findNextSeparator(firstSep + 1) + 1
            parseSection(start, findNextSeparator(start))
        end methodName

        def snippet: String =
            val firstSep  = findNextSeparator(1)
            val secondSep = findNextSeparator(firstSep + 1)
            val start     = findNextSeparator(secondSep + 1) + 1
            self.substring(start)
        end snippet

        def snippetShort: String = snippet.split("📍")(0).reverse.take(snippetShortMaxChars).takeWhile(_ != '\n').trim.reverse

        def show: String = s"Frame(${Position.show(position)}, $className, $methodName, $snippetShort)"

        def render: String =
            Ansi.highlight(
                s"// ${Position.show(position)} $className $methodName",
                snippet.toString(),
                s"",
                Math.max(0, Position.lineNumber(self) - 2)
            )

        def render(details: Any*): String =
            val detailsString =
                details.size match
                    case 0 => ""
                    case 1 => pprint(details(0)).render
                    case _ =>
                        details.map {
                            case v: String => v
                            case v         => pprint(v).render
                        }.mkString("\n\n")
            Ansi.highlight(
                s"// ${Position.show(position)} $className $methodName",
                snippet.toString(),
                detailsString,
                Math.max(0, Position.lineNumber(self) - 2)
            )
        end render
    end extension

    implicit inline def derive: Frame = ${ frameImpl(false) }

    private[kyo] inline def internal: Frame = ${ frameImpl(true) }

    private val allowKyoFileSuffixes = Set("Test.scala", "Spec.scala", "Bench.scala")

    private def frameImpl(internal: Boolean)(using Quotes): Expr[String] =
        import quotes.reflect.*

        val pos      = quotes.reflect.Position.ofMacroExpansion
        val fileName = pos.sourceFile.name

        if !internal then
            findEnclosing { sym =>
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

        val cls    = findEnclosing(_.isClassDef).map(_.fullName).getOrElse("?")
        val method = if internal then "?" else findEnclosing(_.isDefDef).map(_.name).getOrElse("?")

        val snippet =
            if internal then "<internal>"
            else
                val content = pos.sourceFile.content.getOrElse(
                    report.errorAndAbort("Can't locate source code")
                )
                val marked = content.take(pos.end) + "📍" + content.drop(pos.end)
                val lines  = marked.linesIterator.slice(pos.startLine - 1, pos.endLine + 2).filter(_.exists(_ != ' '))
                val toDrop = lines.map(_.takeWhile(_ == ' ').length).min
                lines.map(_.drop(toDrop)).mkString("\n")

        Expr(s"$version$fileName:${pos.startLine + 1}:${pos.startColumn + 1}|$cls|$method|$snippet")
    end frameImpl

    private def render(using Quotes)(symbol: quotes.reflect.Symbol): String =
        if symbol.isClassDef then symbol.fullName
        else if symbol.isDefDef then symbol.name
        else ""
    end render

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
