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

    implicit inline def derive: Frame = ${ frameImpl() }

    // A single shared placeholder Frame for synthesized-frame boundaries where no user frame exists
    // (kernel-internal suspensions, generated bridges). Every `Frame.internal` reference is this one
    // instance, so an identity/equality `eq`/`ne` check against the shared placeholder identifies an
    // internal frame, and the trace ring can skip it without an allocation. (On the JS target `ne` on
    // a String is a value comparison, not a reference one, but the placeholder content is globally
    // unique, so the check still identifies exactly this frame.) The string is a valid version-'1'
    // Frame parseable by every accessor: file/caller/callee/class/snippet are all "<internal>", line
    // and column are 0, so it renders as "<internal>:0:0" and identifies as internal via its
    // "<internal>" snippet.
    private[kyo] val internal: Frame = "1<internal>:0:0|<internal>|<internal>|<internal>|<internal>"

    private val allowKyoFileSuffixes = Set("Test.scala", "Spec.scala", "Bench.scala")

    // Single-slot per-file memo for the source file's normalized content and its line
    // split, keyed on the SOURCE FILE OBJECT identity. The compiler caches one source-file
    // object per file for the whole run, so within a file every expansion finds the same
    // object and hits; a hit reuses the stored content and line split and does NOT read the
    // content again. Identity keying is required for correctness: a recompiled file produces
    // a NEW source-file object, so it misses and recomputes; a stale window is impossible.
    // Reading content reconstructs a fresh string each call, so the hit path that skips that
    // read is the whole point. The slot is volatile for cross-thread visibility of the
    // reference write; a parallel expansion on another file is last-writer-wins and the loser
    // simply misses and recomputes the same values. Absent until the first miss.
    @volatile private var fileCache: Maybe[(AnyRef, String, Array[String])] = Absent

    private def contentAndLines(sourceFile: AnyRef, fetchContent: => String): (String, Array[String]) =
        fileCache match
            case Present((sf, content, lines)) if sf eq sourceFile => (content, lines)
            case _ =>
                val content = fetchContent.replace("\r\n", "\n")
                val lines   = content.linesIterator.toArray
                fileCache = Present((sourceFile, content, lines))
                (content, lines)
    end contentAndLines

    private def frameImpl()(using Quotes): Expr[String] =
        import quotes.reflect.*

        val pos      = quotes.reflect.Position.ofMacroExpansion
        val fileName = pos.sourceFile.name

        if FindEnclosing.isInternal then
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
        val caller = FindEnclosing(_.isDefDef).map(_.name).getOrElse("?")

        // Resolve the file's normalized content and line split from the per-file memo. On a hit
        // (same source-file object) the content is reused without re-reading it; on a miss the
        // content is read once, normalized, split, and stored.
        val (rawContent, lines) =
            contentAndLines(
                pos.sourceFile.asInstanceOf[AnyRef],
                pos.sourceFile.content.getOrElse(report.errorAndAbort("Can't locate source code"))
            )

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
        val callee = extractCalleeName(rawContent, pos.end)

        val snippet =
            // Build the marker-inserted snippet from the [startLine-1, endLine+2) line
            // window of the memoized per-file line array, inserting the marker at the
            // (endLine, endColumn) point. pos.end always lies within the retained window,
            // so the marker insertion is exact: the filtered, dedented, newline-joined
            // window is the snippet.
            //
            // pos.endColumn is the 0-based column on pos.endLine where the marker is inserted.
            // This line/column placement corresponds to the position offset `pos.end` maps to
            // in the normalized (LF) content: for LF source it produces the same marker
            // placement as a direct offset-based insertion, and it is also CRLF-correct
            // because a raw-offset insertion into CRLF source would drift the marker right by
            // the count of preceding CRs, while the line/column split sidesteps that drift.
            val markerCol = pos.endColumn
            val from      = pos.startLine - 1
            val until     = math.min(pos.endLine + 2, lines.length)
            val windowBuf = scala.collection.mutable.ArrayBuffer.empty[String]
            var i         = math.max(0, from)
            while i < until do
                val line =
                    if i == pos.endLine then
                        val col = math.min(math.max(0, markerCol), line0Length(lines, i))
                        lines(i).substring(0, col) + "📍" + lines(i).substring(col)
                    else lines(i)
                if line.exists(_ != ' ') then windowBuf += line
                i += 1
            end while
            val selected = windowBuf.toSeq
            val toDrop   = selected.map(_.takeWhile(_ == ' ').length).min
            selected.map(_.drop(toDrop)).mkString("\n")
        end snippet

        Expr(s"$version$fileName:${pos.startLine + 1}:${pos.startColumn + 1}|$cls|$caller|$callee|$snippet")
    end frameImpl

    private def line0Length(lines: Array[String], i: Int): Int =
        if i >= 0 && i < lines.length then lines(i).length else 0

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
