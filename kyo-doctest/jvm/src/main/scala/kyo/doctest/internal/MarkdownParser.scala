package kyo.doctest.internal

import kyo.*
import kyo.doctest.*

/** Extracts scala code blocks from Markdown files using a three-state machine.
  *
  * States: Top: scanning top-level Markdown InComment: inside an HTML comment that contains doctest: InBlock: inside a scala or sbt code
  * block; outer captures the enclosing state
  *
  * Carrier assignment: InBlock(outer=Top) -> Block.Carrier.Visible InBlock(outer=InComment) -> Block.Carrier.Hidden
  *
  * Hybrid approach: the outer loop is tail-recursive and line-based (tracking line numbers and block state), while individual line-content
  * recognition uses Parse[Char] combinators applied to each line string. A pure Parse[Char] rewrite of the outer loop would require
  * threading line-number counters through the Parse continuation structure, producing code approximately 3x longer with no semantic
  * benefit.
  */
private[kyo] object MarkdownParser:

    private val BlockCloser    = "```"
    private val CommentStart   = "<!--"
    private val CommentEnd     = "-->"
    private val DoctestTag     = "doctest:"
    private val DoctestDefault = "doctest:default"

    // Sealed trait for the state machine states.
    sealed private trait ParserState derives CanEqual
    private case object Top extends ParserState
    // commentModifiers: the kyo-doctest modifier string from the <!-- opener line
    private case class InComment(commentModifiers: String) extends ParserState
    // outer: enclosing state; infoString: after the backticks on the opener line
    private case class InBlock(
        outer: ParserState,
        lineStart: Int,
        infoString: String,
        bodyLines: List[String]
    ) extends ParserState

    // Line classification results produced by Parse[Char] combinators.
    // Each case carries whatever data the state machine needs from that line.
    sealed private trait LineKind derives CanEqual
    private case class CommentOpener(content: String) extends LineKind
    private case class ScalaCodeOpener(info: String)  extends LineKind
    private case object BlockCloserLine               extends LineKind
    private case object OtherLine                     extends LineKind

    /** Reads and parses a Markdown file, extracting all scala code blocks.
      *
      * @param path
      *   the path to the Markdown file
      * @return
      *   a Chunk of Block objects representing every extracted scala code block
      */
    def parse(path: kyo.Path)(using Frame): Chunk[Block] < (Sync & Abort[Doctest.Error]) =
        Abort.recover[FileException](e => Abort.fail(Doctest.Error.IoError(path, "read", e))) {
            Path.runReadOnly(path.read).flatMap { raw =>
                parseString(raw.replace("\r\n", "\n"), path)
            }
        }

    /** Parses Markdown content from a string (exposed for testing without file IO).
      *
      * @param content
      *   the full Markdown content; CRLF sequences are normalised before splitting
      * @param file
      *   the file path used for constructing Block objects and error messages
      * @return
      *   a Chunk of Block objects representing every extracted scala code block
      */
    private[internal] def parseString(content: String, file: kyo.Path)(using Frame): Chunk[Block] < Abort[Doctest.Error.ParseError] =
        DefaultsParser.parse(content, file).flatMap { fileDefaults =>
            val normalised = content.replace("\r\n", "\n")
            val lines      = normalised.split("\n", -1)
            loop(lines, file, fileDefaults, state = Top, acc = Chunk.empty, lineNum = 1)
        }

    // Main parsing loop. lineNum is 1-indexed; lines(lineNum-1) is the current line.
    private def loop(
        lines: Array[String],
        file: kyo.Path,
        fileDefaults: ModifierParser.Parsed,
        state: ParserState,
        acc: Chunk[Block],
        lineNum: Int
    )(using Frame): Chunk[Block] < Abort[Doctest.Error.ParseError] =
        if lineNum > lines.length then acc
        else
            val line    = lines(lineNum - 1)
            val trimmed = line.trim
            state match
                case Top =>
                    stepTop(trimmed, lines, file, fileDefaults, acc, lineNum)
                case c: InComment =>
                    stepInComment(trimmed, c, lines, file, fileDefaults, acc, lineNum)
                case f: InBlock =>
                    stepInBlock(trimmed, line, f, lines, file, fileDefaults, acc, lineNum)
            end match

    private def stepTop(
        trimmed: String,
        lines: Array[String],
        file: kyo.Path,
        fileDefaults: ModifierParser.Parsed,
        acc: Chunk[Block],
        lineNum: Int
    )(using Frame): Chunk[Block] < Abort[Doctest.Error.ParseError] =
        classifyLine(trimmed).flatMap {
            case CommentOpener(content) if content.startsWith(DoctestDefault) =>
                // per-README defaults block: already consumed by DefaultsParser; skip it
                if trimmed.contains(CommentEnd) then
                    loop(lines, file, fileDefaults, Top, acc, lineNum + 1)
                else
                    skipComment(lines, file, fileDefaults, acc, lineNum + 1)
                end if
            case CommentOpener(content) if content.startsWith(DoctestTag) =>
                val modStr = extractCommentModifiers(content)
                loop(lines, file, fileDefaults, InComment(modStr), acc, lineNum + 1)
            case CommentOpener(_) =>
                if trimmed.contains(CommentEnd) then
                    loop(lines, file, fileDefaults, Top, acc, lineNum + 1)
                else
                    skipComment(lines, file, fileDefaults, acc, lineNum + 1)
                end if
            case ScalaCodeOpener(infoString) =>
                val newState = InBlock(outer = Top, lineStart = lineNum, infoString = infoString, bodyLines = Nil)
                loop(lines, file, fileDefaults, newState, acc, lineNum + 1)
            case _ =>
                loop(lines, file, fileDefaults, Top, acc, lineNum + 1)
        }
    end stepTop

    // Extract the kyo-doctest modifier string from the portion of a comment after "<!--".
    // Stops at --> if present on the same line; returns the trimmed content.
    private def extractCommentModifiers(afterComment: String): String =
        afterComment.split("-->").headOption.fold(afterComment.trim)(_.trim)

    private def skipComment(
        lines: Array[String],
        file: kyo.Path,
        fileDefaults: ModifierParser.Parsed,
        acc: Chunk[Block],
        lineNum: Int
    )(using Frame): Chunk[Block] < Abort[Doctest.Error.ParseError] =
        if lineNum > lines.length then acc
        else
            val trimmed = lines(lineNum - 1).trim
            if trimmed.contains(CommentEnd) then
                loop(lines, file, fileDefaults, Top, acc, lineNum + 1)
            else
                skipComment(lines, file, fileDefaults, acc, lineNum + 1)
            end if

    private def stepInComment(
        trimmed: String,
        commentState: InComment,
        lines: Array[String],
        file: kyo.Path,
        fileDefaults: ModifierParser.Parsed,
        acc: Chunk[Block],
        lineNum: Int
    )(using Frame): Chunk[Block] < Abort[Doctest.Error.ParseError] =
        if trimmed.startsWith(CommentEnd) then
            loop(lines, file, fileDefaults, Top, acc, lineNum + 1)
        else
            classifyLine(trimmed).flatMap {
                case ScalaCodeOpener(infoString) =>
                    val newState = InBlock(outer = commentState, lineStart = lineNum, infoString = infoString, bodyLines = Nil)
                    loop(lines, file, fileDefaults, newState, acc, lineNum + 1)
                case _ =>
                    loop(lines, file, fileDefaults, commentState, acc, lineNum + 1)
            }
        end if
    end stepInComment

    private def stepInBlock(
        trimmed: String,
        originalLine: String,
        blockState: InBlock,
        lines: Array[String],
        file: kyo.Path,
        fileDefaults: ModifierParser.Parsed,
        acc: Chunk[Block],
        lineNum: Int
    )(using Frame): Chunk[Block] < Abort[Doctest.Error.ParseError] =
        if trimmed == BlockCloser then
            buildBlock(blockState, lineEnd = lineNum, file, fileDefaults).flatMap { block =>
                loop(lines, file, fileDefaults, blockState.outer, acc.append(block), lineNum + 1)
            }
        else
            val updatedState = blockState.copy(bodyLines = blockState.bodyLines :+ originalLine)
            loop(lines, file, fileDefaults, updatedState, acc, lineNum + 1)

    private def buildBlock(
        blockState: InBlock,
        lineEnd: Int,
        file: kyo.Path,
        fileDefaults: ModifierParser.Parsed
    )(using Frame): Block < Abort[Doctest.Error.ParseError] =
        val carrier = blockState.outer match
            case Top          => Block.Carrier.Visible
            case _: InComment => Block.Carrier.Hidden
            case _: InBlock   => Block.Carrier.Visible // nested blocks not supported; treat as visible

        // For HTML comment blocks, the modifiers come from the comment opener line.
        // Combine: block info string + comment modifier string.
        // ModifierParser.parse handles the doctest: prefix lookup.
        val combinedInfo = blockState.outer match
            case InComment(commentMods) if commentMods.nonEmpty =>
                s"${blockState.infoString} $commentMods"
            case _ =>
                blockState.infoString

        ModifierParser.parse(combinedInfo, file, blockState.lineStart).map { perBlock =>
            val (visibility, expect, platform) = DefaultsParser.applyDefaults(perBlock, fileDefaults)
            val body                           = blockState.bodyLines.mkString("\n")
            Block(
                file = file,
                lineStart = blockState.lineStart,
                lineEnd = lineEnd,
                body = body,
                visibility = visibility,
                expect = expect,
                platform = platform,
                carrier = carrier
            )
        }
    end buildBlock

    // Classify a trimmed line using Parse[Char] combinators applied to the lowercased version.
    // Info strings for code openers are extracted from the original trimmed line (same position, case preserved).
    private def classifyLine(trimmed: String)(using Frame): LineKind < Abort[Doctest.Error.ParseError] =
        val lower = trimmed.toLowerCase
        Parse.runResult(lower)(lineKindParser(trimmed)).map { result =>
            result.out.getOrElse(OtherLine)
        }
    end classifyLine

    // Parse[Char] combinators for line classification.
    // The input is the lowercased version of the trimmed line; the original trimmed is passed for info-string extraction.
    private def lineKindParser(original: String)(using Frame): LineKind < Parse[Char] =
        Parse.firstOf(
            blockCloserParser,
            scalaOpenerParser(original),
            commentOpenerParser(original)
        )

    // Matches ``` exactly (block closer).
    private def blockCloserParser(using Frame): LineKind < Parse[Char] =
        for
            _ <- Parse.literal("```")
            _ <- Parse.end[Char]
        yield BlockCloserLine

    // Matches ```scala or ```sbt (case-insensitive, since input is already lowercased).
    // Extracts the info string from the original (non-lowercased) line.
    private def scalaOpenerParser(original: String)(using Frame): LineKind < Parse[Char] =
        Parse.firstOf(
            scalaOpenerForPrefix("```scala", original),
            scalaOpenerForPrefix("```sbt", original)
        )

    // Parses a scala/sbt code fence opener by matching the prefix then optionally a space + remainder.
    // The info string is extracted from the original (case-preserved) line by dropping the backticks.
    private def scalaOpenerForPrefix(prefix: String, original: String)(using Frame): LineKind < Parse[Char] =
        for
            _ <- Parse.literal(prefix)
            _ <- Parse.firstOf(
                Parse.end[Char],
                Parse.literal(' ').andThen(Parse.readWhile[Char](_ => true))
            )
        yield
            val infoString = original.span(_ == '`')._2.trim
            ScalaCodeOpener(infoString)

    // Matches <!-- (HTML comment opener). Extracts the content after <!-- from the original trimmed line.
    private def commentOpenerParser(original: String)(using Frame): LineKind < Parse[Char] =
        for
            _ <- Parse.literal(CommentStart)
            _ <- Parse.readWhile[Char](_ => true)
        yield
            val afterComment = original.drop(CommentStart.length).trim
            CommentOpener(afterComment)

end MarkdownParser
