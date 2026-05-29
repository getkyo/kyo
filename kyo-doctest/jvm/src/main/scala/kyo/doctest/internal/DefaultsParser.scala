package kyo.doctest.internal

import kyo.*
import kyo.doctest.*

/** Parses per-README default modifiers from the top of a Markdown file.
  *
  * Scans the first non-blank lines for a single HTML comment block of the form: single-line: <!-- doctest:default KEY=VALUE KEY=VALUE -->
  * multi-line: <!-- doctest:default KEY=VALUE KEY=VALUE -->
  *
  * The defaults block must appear before any non-blank, non-comment content line. A defaults block appearing after first content is
  * ignored.
  */
object DefaultsParser:

    private val DefaultCommentStart = "<!-- doctest:default"
    private val CommentOpen         = "<!--"
    private val CommentEnd          = "-->"

    /** Parses per-README defaults from the content string.
      *
      * @param content
      *   the full content of a Markdown file (already CRLF-normalised)
      * @param file
      *   the source file path, used for error reporting
      * @return
      *   ModifierParser.Parsed containing only the axes explicitly set in the defaults block; all fields are Absent if no defaults block is
      *   found
      */
    def parse(content: String, file: kyo.Path)(using Frame): ModifierParser.Parsed < Abort[Doctest.Error.ParseError] =
        Parse.runResult(content)(defaultsParser(file)).flatMap { result =>
            result.out match
                case Present(parsed) => parsed
                case Absent          => ModifierParser.Parsed.empty
        }

    // Parse[Char] combinator that scans for the defaults block at the top of the file.
    // Skips blank lines and non-defaults HTML comments; returns empty if any non-comment content line is found.
    private def defaultsParser(file: kyo.Path)(using Frame): ModifierParser.Parsed < (Parse[Char] & Abort[Doctest.Error.ParseError]) =
        Parse.repeat(blankLineParser).andThen(
            Parse.firstOf(
                defaultsCommentBlockParser(file),
                skipNonDefaultsCommentParser(file).andThen(defaultsParser(file))
            )
        )

    // Matches a line that is entirely horizontal whitespace followed by a newline.
    // Requires at least the newline terminator to be present so Parse.repeat terminates at EOF.
    private def blankLineParser(using Frame): Unit < Parse[Char] =
        for
            _ <- Parse.readWhile[Char](c => c == ' ' || c == '\t')
            _ <- Parse.literal('\n')
        yield ()

    // Matches the <!-- doctest:default ... --> block (single or multi-line) and parses the token string inside.
    private def defaultsCommentBlockParser(file: kyo.Path)(using
        Frame
    ): ModifierParser.Parsed < (Parse[Char] & Abort[Doctest.Error.ParseError]) =
        Parse.literal(Text(DefaultCommentStart)).andThen(gatherUntilCommentEnd).flatMap { tokensPart =>
            if tokensPart.nonEmpty then
                ModifierParser.parseTokensDirect(tokensPart, file, 1)
            else
                ModifierParser.Parsed.empty
        }

    // Skips a non-defaults HTML comment (<!-- ... -->) that appears before any content.
    // Fails (drops branch) if the next non-blank content does not start with <!-- or starts with <!-- doctest:default.
    private def skipNonDefaultsCommentParser(file: kyo.Path)(using Frame): Unit < Parse[Char] =
        for
            _ <- Parse.readWhile[Char](c => c == ' ' || c == '\t')
            _ <- Parse.literal(Text(CommentOpen))
            _ <- Parse.not(Parse.literal(Text(" doctest:default")))
            _ <- gatherUntilCommentEnd
        yield ()

    // Reads characters until --> is consumed, returning the trimmed content before -->.
    // Works for both single-line and multi-line comments.
    private def gatherUntilCommentEnd(using Frame): String < Parse[Char] =
        collectUntilCommentEnd(new StringBuilder).map(_.trim)

    // Recursive combinator: reads one char at a time, stopping when --> is matched.
    private def collectUntilCommentEnd(acc: StringBuilder)(using Frame): String < Parse[Char] =
        Parse.firstOf(
            Parse.literal(Text(CommentEnd)).andThen(acc.toString),
            Parse.any[Char].flatMap(c => collectUntilCommentEnd(acc.append(c)))
        )

    /** Applies the three-tier resolution: per-block > per-file > hardcoded defaults.
      *
      * Hardcoded defaults: Isolated, Compiles, Set(JVM, JS, Native).
      *
      * @param perBlock
      *   modifiers explicitly set on the code block info string
      * @param perFile
      *   defaults from the per-README defaults block
      * @return
      *   fully-resolved (visibility, expectation, platform) triple
      */
    def applyDefaults(
        perBlock: ModifierParser.Parsed,
        perFile: ModifierParser.Parsed
    ): (Block.Visibility, Block.Expectation, Set[Block.Target]) =
        ModifierParser.applyDefaults(perBlock, perFile)

end DefaultsParser
