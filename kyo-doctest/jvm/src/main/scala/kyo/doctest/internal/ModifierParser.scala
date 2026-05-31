package kyo.doctest.internal

import kyo.*
import kyo.doctest.*

/** Parses the doctest modifier DSL from a code block info string.
  *
  * Grammar: modifier-block := token+ token := axis-key "=" axis-value | "setup" axis-key := "scope" | "expect" | "platform" scope-value :=
  * "isolated" | "inherited" | "nested" | "env:" NAME expect-value := "compiles" | "runs" | "fails-compile" | "warns" | "crashes" |
  * "skipped" platform-value := platform-id ("," platform-id)* platform-id := "jvm" | "js" | "native" | "all"
  *
  * The "setup" keyword (no "=") expands to scope=env:__doc__. Unknown doctest: modifier keys return a ParseError. Unknown non-doctest:
  * tokens are silently ignored.
  */
private[kyo] object ModifierParser:

    /** Parsed modifiers for a single block. Maybe.Absent means "not specified" so the downstream merge step can fall back to per-README
      * defaults then hardcoded defaults.
      */
    case class Parsed(
        scope: Maybe[Block.Visibility],
        expect: Maybe[Block.Expectation],
        platform: Maybe[Set[Block.Target]]
    ) derives CanEqual

    object Parsed:
        val empty: Parsed = Parsed(Maybe.Absent, Maybe.Absent, Maybe.Absent)
    end Parsed

    private val DoctestPrefix = "doctest:"

    /** Parses the modifier block from a code block info string.
      *
      * The info string is everything after the opening backticks on the code block opener line, e.g. "scala doctest:scope=nested
      * expect=warns platform=jvm".
      *
      * Processing: tokens before the first doctest: token are silently ignored (they are unknown info-string tokens such as "scala",
      * "mdoc:reset", etc). Once a doctest: token is encountered, that token's content and ALL subsequent space-separated tokens on the same
      * line are treated as modifier tokens.
      *
      * @param infoString
      *   the text after the backticks (e.g. "scala doctest:expect=fails-compile scope=isolated")
      * @param file
      *   the source file, used for error reporting
      * @param line
      *   the 1-indexed line number of the code block opener, used for error reporting
      * @return
      *   ModifierParser.Parsed with only the explicitly specified axes set
      */
    def parse(infoString: String, file: kyo.Path, line: Int)(using Frame): Parsed < Abort[Doctest.Error.ParseError] =
        val tokens = infoString.trim.split("\\s+").filter(_.nonEmpty).toList
        val kyoIdx = tokens.indexWhere(_.startsWith(DoctestPrefix))
        if kyoIdx < 0 then
            Parsed.empty
        else
            val kyoToken         = tokens(kyoIdx)
            val firstContent     = kyoToken.drop(DoctestPrefix.length)
            val subsequentTokens = tokens.drop(kyoIdx + 1)
            val allModifierTokens =
                if firstContent.isEmpty then subsequentTokens
                else firstContent :: subsequentTokens
            parseModifierTokens(allModifierTokens, file, line, Parsed.empty)
        end if
    end parse

    // Parse a list of modifier tokens (each is a key=value pair or "setup") into an accumulated Parsed.
    private def parseModifierTokens(
        tokens: List[String],
        file: kyo.Path,
        line: Int,
        acc: Parsed
    )(using Frame): Parsed < Abort[Doctest.Error.ParseError] =
        tokens match
            case Nil => acc
            case token :: rest =>
                parseSingleToken(token, file, line, acc).flatMap { updated =>
                    parseModifierTokens(rest, file, line, updated)
                }

    // Parse one modifier token using Parse[Char] combinators.
    // Runs a Parse[Char] parser on the token string; if the parse fails (no branch matched),
    // aborts with a ParseError for an unknown modifier.
    private def parseSingleToken(
        token: String,
        file: kyo.Path,
        line: Int,
        acc: Parsed
    )(using Frame): Parsed < Abort[Doctest.Error.ParseError] =
        Parse.runResult(token)(singleTokenParser(file, line, acc)).flatMap { result =>
            result.out match
                case Present(parsed) => parsed
                case Absent =>
                    Abort.fail[Doctest.Error.ParseError](
                        Doctest.Error.ParseError(file, line, s"unknown doctest modifier key: '$token'")
                    )
        }

    // Parse[Char] combinator for a single modifier token: either "setup" or "key=value".
    private def singleTokenParser(
        file: kyo.Path,
        line: Int,
        acc: Parsed
    )(using Frame): Parsed < (Parse[Char] & Abort[Doctest.Error.ParseError]) =
        Parse.firstOf(
            setupTokenParser(acc),
            keyValueTokenParser(file, line, acc)
        )

    // Parses "setup" -> scope=Env("__doc__")
    private def setupTokenParser(acc: Parsed)(using Frame): Parsed < (Parse[Char] & Abort[Doctest.Error.ParseError]) =
        for
            _ <- Parse.literal("setup")
            _ <- Parse.end[Char]
        yield acc.copy(scope = Maybe.Present(Block.Visibility.Env("__doc__")))

    // Parses "key=value" and returns updated Parsed.
    private def keyValueTokenParser(
        file: kyo.Path,
        line: Int,
        acc: Parsed
    )(using Frame): Parsed < (Parse[Char] & Abort[Doctest.Error.ParseError]) =
        for
            key    <- Parse.readWhile[Char](_ != '=').map(_.mkString)
            _      <- Parse.literal('=')
            value  <- Parse.readWhile[Char](_ => true).map(_.mkString)
            result <- resolveKeyValue(key, value, file, line, acc)
        yield result

    private def resolveKeyValue(
        key: String,
        value: String,
        file: kyo.Path,
        line: Int,
        acc: Parsed
    )(using Frame): Parsed < (Parse[Char] & Abort[Doctest.Error.ParseError]) =
        key match
            case "scope"    => parseScope(value, file, line).map(s => acc.copy(scope = Maybe.Present(s)))
            case "expect"   => parseExpect(value, file, line).map(e => acc.copy(expect = Maybe.Present(e)))
            case "platform" => parsePlatform(value, file, line).map(p => acc.copy(platform = Maybe.Present(p)))
            case unknown =>
                Abort.fail[Doctest.Error.ParseError](
                    Doctest.Error.ParseError(file, line, s"unknown doctest modifier key: '$unknown'")
                )

    private def parseScope(value: String, file: kyo.Path, line: Int)(using
        Frame
    ): Block.Visibility < (Parse[Char] & Abort[Doctest.Error.ParseError]) =
        Parse.runResult(value)(scopeValueParser(file, line)).flatMap[Block.Visibility, Parse[Char] & Abort[Doctest.Error.ParseError]] {
            result =>
                result.out match
                    case Present(v) => v
                    case Absent =>
                        Abort.fail[Doctest.Error.ParseError](
                            Doctest.Error.ParseError(file, line, s"invalid scope value: '$value'")
                        )
        }

    private def scopeValueParser(file: kyo.Path, line: Int)(using
        Frame
    ): Block.Visibility < (Parse[Char] & Abort[Doctest.Error.ParseError]) =
        Parse.firstOf(
            Parse.literal("isolated").andThen(Block.Visibility.Isolated: Block.Visibility),
            Parse.literal("inherited").andThen(Block.Visibility.Inherited: Block.Visibility),
            Parse.literal("nested").andThen(Block.Visibility.Nested: Block.Visibility),
            envScopeParser(file, line)
        )

    private def envScopeParser(file: kyo.Path, line: Int)(using Frame): Block.Visibility < (Parse[Char] & Abort[Doctest.Error.ParseError]) =
        for
            _      <- Parse.literal("env:")
            name   <- Parse.readWhile[Char](_ => true).map(_.mkString)
            result <- envNameToVisibility(name, file, line)
        yield result

    private def envNameToVisibility(
        name: String,
        file: kyo.Path,
        line: Int
    )(using Frame): Block.Visibility < (Parse[Char] & Abort[Doctest.Error.ParseError]) =
        if name.isEmpty then
            Abort.fail[Doctest.Error.ParseError](
                Doctest.Error.ParseError(file, line, "env: scope requires a non-empty name")
            )
        else
            Block.Visibility.Env(name)

    private def parseExpect(value: String, file: kyo.Path, line: Int)(using
        Frame
    ): Block.Expectation < (Parse[Char] & Abort[Doctest.Error.ParseError]) =
        Parse.runResult(value)(expectValueParser(file, line)).flatMap[Block.Expectation, Parse[Char] & Abort[Doctest.Error.ParseError]] {
            result =>
                result.out match
                    case Present(e) => e
                    case Absent =>
                        Abort.fail[Doctest.Error.ParseError](
                            Doctest.Error.ParseError(file, line, s"invalid expect value: '$value'")
                        )
        }

    private def expectValueParser(file: kyo.Path, line: Int)(using
        Frame
    ): Block.Expectation < (Parse[Char] & Abort[Doctest.Error.ParseError]) =
        Parse.firstOf(
            Parse.literal("compiles").andThen(Block.Expectation.Compiles: Block.Expectation),
            Parse.literal("runs").andThen(Block.Expectation.Runs: Block.Expectation),
            Parse.literal("fails-compile").andThen(Block.Expectation.FailsCompile: Block.Expectation),
            Parse.literal("warns").andThen(Block.Expectation.Warns: Block.Expectation),
            Parse.literal("crashes").andThen(Block.Expectation.Crashes: Block.Expectation),
            Parse.literal("skipped").andThen(Block.Expectation.Skipped: Block.Expectation)
        )

    private def parsePlatform(
        value: String,
        file: kyo.Path,
        line: Int
    )(using Frame): Set[Block.Target] < (Parse[Char] & Abort[Doctest.Error.ParseError]) =
        Parse.runResult(value)(platformValueParser(file, line)).flatMap[Set[Block.Target], Parse[Char] & Abort[Doctest.Error.ParseError]] {
            result =>
                result.out match
                    case Present(p) => p
                    case Absent =>
                        Abort.fail[Doctest.Error.ParseError](
                            Doctest.Error.ParseError(file, line, "platform value must not be empty")
                        )
        }

    // Parses comma-separated platform ids. "all" expands to all three targets.
    private def platformValueParser(file: kyo.Path, line: Int)(using
        Frame
    ): Set[Block.Target] < (Parse[Char] & Abort[Doctest.Error.ParseError]) =
        for
            parts <- Parse.separatedBy(
                platformIdStringParser,
                Parse.literal(',')
            )
            result <-
                if parts.isEmpty then
                    Abort.fail[Doctest.Error.ParseError](
                        Doctest.Error.ParseError(file, line, "platform value must not be empty")
                    ): Set[Block.Target] < (Parse[Char] & Abort[Doctest.Error.ParseError])
                else
                    expandPlatformParts(
                        parts.toSeq,
                        file,
                        line,
                        Set.empty
                    ): Set[Block.Target] < (Parse[Char] & Abort[Doctest.Error.ParseError])
        yield result

    // Reads one platform part as a String (stops at comma or end).
    private def platformIdStringParser(using Frame): String < Parse[Char] =
        Parse.readWhile[Char](_ != ',').map(_.mkString)

    private def expandPlatformParts(
        parts: Seq[String],
        file: kyo.Path,
        line: Int,
        acc: Set[Block.Target]
    )(using Frame): Set[Block.Target] < Abort[Doctest.Error.ParseError] =
        parts match
            case Seq() => acc
            case part +: rest =>
                part match
                    case "jvm"    => expandPlatformParts(rest, file, line, acc + Block.Target.JVM)
                    case "js"     => expandPlatformParts(rest, file, line, acc + Block.Target.JS)
                    case "native" => expandPlatformParts(rest, file, line, acc + Block.Target.Native)
                    case "all" =>
                        expandPlatformParts(rest, file, line, acc ++ Set(Block.Target.JVM, Block.Target.JS, Block.Target.Native))
                    case bad =>
                        Abort.fail[Doctest.Error.ParseError](
                            Doctest.Error.ParseError(file, line, s"invalid platform id: '$bad'")
                        )

    /** Parses a space-separated list of KEY=VALUE modifier tokens directly.
      *
      * Unlike parse(), this method does NOT require a doctest: prefix: tokens are treated as modifier key=value pairs directly. Used by
      * DefaultsParser to parse the content of a "<!-- doctest:default KEY=VALUE ... -->" block.
      *
      * @param tokenString
      *   space-separated modifier tokens, e.g. "expect=runs scope=inherited"
      * @param file
      *   the source file, used for error reporting
      * @param line
      *   the 1-indexed line number, used for error reporting
      * @return
      *   ModifierParser.Parsed with only the explicitly specified axes set
      */
    private[internal] def parseTokensDirect(tokenString: String, file: kyo.Path, line: Int)(using
        Frame
    ): Parsed < Abort[Doctest.Error.ParseError] =
        val tokens = tokenString.trim.split("\\s+").filter(_.nonEmpty).toList
        parseModifierTokens(tokens, file, line, Parsed.empty)
    end parseTokensDirect

    /** Applies defaults to a ModifierParser.Parsed, filling in Absent fields with per-file defaults or hardcoded defaults.
      *
      * Hardcoded defaults: Isolated, Compiles, Set(JVM, JS, Native).
      *
      * @param perBlock
      *   modifiers explicitly specified on the block
      * @param perFile
      *   per-README defaults from the DefaultsParser
      * @return
      *   a triple of (visibility, expectation, platform) with all fields resolved
      */
    private[internal] def applyDefaults(
        perBlock: Parsed,
        perFile: Parsed
    ): (Block.Visibility, Block.Expectation, Set[Block.Target]) =
        val scope    = perBlock.scope.orElse(perFile.scope).getOrElse(Block.Visibility.Isolated)
        val expect   = perBlock.expect.orElse(perFile.expect).getOrElse(Block.Expectation.Compiles)
        val platform = perBlock.platform.orElse(perFile.platform).getOrElse(Set(Block.Target.JVM, Block.Target.JS, Block.Target.Native))
        (scope, expect, platform)
    end applyDefaults

end ModifierParser
