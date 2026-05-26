package kyo

import kyo.internal.FindEnclosing
import scala.annotation.tailrec
import scala.quoted.*

opaque type Frame <: AnyRef = String

object Frame:

    // version bytes:
    //   '0' (legacy): $0$file:line:col|$cls|$method|$snippet
    //   '1' adds the syntactic name of the function whose implicit Frame parameter is being filled at the
    //         macro-expansion call site: $1$file:line:col|$cls|$method|$callee|$snippet
    // Accessors detect the version and parse accordingly; `calleeName` returns "" for a '0' Frame.
    private val version = '1'

    private val snippetShortMaxChars = 50

    inline given CanEqual[Frame, Frame] = CanEqual.derived

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

        def callerName: String =
            val firstSep = findNextSeparator(1)
            val start    = findNextSeparator(firstSep + 1) + 1
            parseSection(start, findNextSeparator(start))
        end callerName

        /** Syntactic name of the function whose implicit `Frame` parameter is being filled at the macro-expansion call site (e.g. `"map"`
          * for `comp.map(f)`, `"assertEmpty"` for `Browser.assertEmpty(sel)`). Returns `""` for legacy (version `'0'`) frames that did not
          * encode this segment.
          */
        def calleeName: String =
            if self.charAt(0) == '0' then ""
            else
                val firstSep  = findNextSeparator(1)
                val secondSep = findNextSeparator(firstSep + 1)
                val thirdSep  = findNextSeparator(secondSep + 1)
                val start     = thirdSep + 1
                parseSection(start, findNextSeparator(start))
            end if
        end calleeName

        def snippet: String =
            val firstSep  = findNextSeparator(1)
            val secondSep = findNextSeparator(firstSep + 1)
            val thirdSep  = findNextSeparator(secondSep + 1)
            // Version '0' has 3 separators ($file|$cls|$method|$snippet); '1' has 4 ($file|$cls|$method|$callee|$snippet).
            val start =
                if self.charAt(0) == '0' then thirdSep + 1
                else findNextSeparator(thirdSep + 1) + 1
            self.substring(start)
        end snippet

        def snippetShort: String = snippet.split("📍")(0).reverse.take(snippetShortMaxChars).takeWhile(_ != '\n').trim.reverse

        def show: String = s"Frame(${Position.show(position)}, $className, $callerName, $snippetShort)"

        def render: String =
            Ansi.highlight(
                s"// ${Position.show(position)} $className $callerName",
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
                s"// ${Position.show(position)} $className $callerName",
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

        if !internal && FindEnclosing.isInternal then
            report.errorAndAbort(
                s"""Frame cannot be derived within the kyo package.
                    |
                    |To resolve this issue:
                    |1. Propagate the Frame from user code via a `using` parameter.
                    |2. If absolutely necessary, you can use Frame.internal, but this is not recommended for general use.
                    |
                    |Example of propagating Frame:
                    |def myMethod[A](a: A)(using Frame): Unit = ...""".stripMargin
            )
        end if

        val cls    = FindEnclosing(_.isClassDef).map(_.fullName).getOrElse("?")
        val caller = if internal then "?" else FindEnclosing(_.isDefDef).map(_.name).getOrElse("?")

        val rawContent =
            if internal then ""
            else pos.sourceFile.content.getOrElse(report.errorAndAbort("Can't locate source code")).replace("\r\n", "\n")

        // Callee = syntactic name of the function whose `(using Frame)` parameter the macro is filling.
        // The macro splice always lands at the end of a call expression: either right after the function
        // identifier (no explicit args) or right after the closing `)`/`]` of the trailing explicit
        // arg/type-arg list. Reading the source text backwards from `pos.end`, peeling any trailing
        // matched `(...)` / `[...]` groups, and taking the identifier that immediately precedes them
        // recovers the called function's name across all of these shapes.
        //
        // Caveats: returns "?" in the rare case the call is split across lines by comments or
        // unusual whitespace. Operator-named methods (return the operator string like "+", "==",
        // "::") and backticked identifiers (return the content without backticks) are both
        // handled.
        val callee = if internal then "?" else extractCalleeName(rawContent, pos.end)

        val snippet =
            if internal then "<internal>"
            else
                val marked = rawContent.take(pos.end) + "📍" + rawContent.drop(pos.end)
                val lines  = marked.linesIterator.slice(pos.startLine - 1, pos.endLine + 2).filter(_.exists(_ != ' ')).toSeq
                val toDrop = lines.map(_.takeWhile(_ == ' ').length).min
                lines.map(_.drop(toDrop)).mkString("\n")

        Expr(s"$version$fileName:${pos.startLine + 1}:${pos.startColumn + 1}|$cls|$caller|$callee|$snippet")
    end frameImpl

    /** Source-text extraction of the callee identifier from `source` walking backwards starting at `end`. Pulled out to a pure helper so
      * the algorithm can be exercised directly from unit tests with hand-built inputs.
      */
    private[kyo] def extractCalleeName(source: String, end: Int): String =
        def isIdChar(c: Char): Boolean = c.isLetterOrDigit || c == '_' || c == '$'
        def isOpChar(c: Char): Boolean =
            c match
                case '+' | '-' | '*' | '/' | '<' | '>' | '=' | '!' | '&' | '|' | '^' | '%' | ':' | '~' | '?' | '@' | '#' => true
                case _                                                                                                   => false

        @tailrec def skipWhitespace(idx: Int): Int =
            if idx >= 0 && source(idx).isWhitespace then skipWhitespace(idx - 1) else idx

        @tailrec def skipMatchedBracket(idx: Int, closeChar: Char, openChar: Char, depth: Int): Int =
            if idx < 0 || depth == 0 then idx
            else
                val nextDepth = source(idx) match
                    case c if c == closeChar => depth + 1
                    case c if c == openChar  => depth - 1
                    case _                   => depth
                skipMatchedBracket(idx - 1, closeChar, openChar, nextDepth)
            end if
        end skipMatchedBracket

        @tailrec def peelBrackets(idx: Int): Int =
            if idx >= 0 && (source(idx) == ')' || source(idx) == ']') then
                val closeChar = source(idx)
                val openChar  = if closeChar == ')' then '(' else '['
                val afterBkt  = skipMatchedBracket(idx - 1, closeChar, openChar, 1)
                peelBrackets(skipWhitespace(afterBkt))
            else idx

        @tailrec def collectIdStart(idx: Int): Int =
            if idx >= 0 && isIdChar(source(idx)) then collectIdStart(idx - 1) else idx

        @tailrec def collectOpStart(idx: Int): Int =
            if idx >= 0 && isOpChar(source(idx)) then collectOpStart(idx - 1) else idx

        @tailrec def findOpeningBacktick(idx: Int): Int =
            if idx < 0 then -1
            else if source(idx) == '`' then idx
            else findOpeningBacktick(idx - 1)

        val afterWs   = skipWhitespace(end - 1)
        val afterBkts = peelBrackets(afterWs)
        val tokenEnd  = afterBkts + 1

        // Backticked identifier (e.g. ``foo.`my method`(x)``): peel landed on the closing backtick.
        if afterBkts >= 0 && source(afterBkts) == '`' then
            val openIdx = findOpeningBacktick(afterBkts - 1)
            if openIdx < 0 then "?"
            else source.substring(openIdx + 1, afterBkts)
        else if afterBkts >= 0 && isOpChar(source(afterBkts)) then
            // Operator-named method: trailing token is a contiguous run of operator chars.
            val opStart = collectOpStart(afterBkts) + 1
            source.substring(opStart, tokenEnd)
        else
            // Plain identifier: must start with a letter, `_`, or `$`. Numeric literals and other
            // non-identifier trailing tokens surface as "?".
            val idStart = collectIdStart(afterBkts) + 1
            if idStart >= tokenEnd then "?"
            else
                val first = source(idStart)
                if first.isLetter || first == '_' || first == '$' then source.substring(idStart, tokenEnd) else "?"
            end if
        end if
    end extractCalleeName

    private def render(using Quotes)(symbol: quotes.reflect.Symbol): String =
        if symbol.isClassDef then symbol.fullName
        else if symbol.isDefDef then symbol.name
        else ""
    end render

end Frame
