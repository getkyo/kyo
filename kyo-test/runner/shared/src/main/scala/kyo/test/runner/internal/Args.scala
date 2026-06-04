package kyo.test.runner.internal

import kyo.Chunk
import kyo.Maybe
import kyo.test.RunConfig
import kyo.test.TestFilter
import kyo.test.Verbosity
import kyo.test.runner.CombinedReporter
import kyo.test.runner.Reporters
import kyo.test.runner.TapReporter
import scala.annotation.tailrec

/** Parses CLI arguments into a [[RunConfig]] and a list of reporter values.
  *
  * This parser is used by both the [[kyo.test.runner.Cli]] entry point and the sbt/JS/Native runners. It supports a richer argument set
  * than the former per-platform parsers, adding `--reporter` and `--help`. Runners that do not understand Help/Error fall back to a default
  * [[kyo.test.RunConfig]].
  *
  * Supported flags:
  *   - `--parallel=N`: set parallelism to N (default 0 = auto)
  *   - `--randomize`: randomize leaf order using the current time as seed
  *   - `--randomize=SEED`: randomize using the given long seed (for reproducing runs)
  *   - `--filter=GLOB`: include only leaves matching GLOB (repeatable)
  *   - `--tag=NAME`: include only leaves tagged NAME (repeatable)
  *   - `--exclude-tag=NAME`: exclude leaves tagged NAME (repeatable)
  *   - `--reporter=VALUE`: add a reporter (comma-separated or repeatable); `console`, `tap`, `junit-xml:PATH`
  *   - `--verbose`: emit per-leaf start/complete lines in verbose mode
  *   - `--quiet`: suppress per-leaf lines, show only failures and summary
  *   - `--count`: discovery only; report the leaf count without executing any leaf body
  *   - `--list`: discovery only; print every leaf's full name path (implies `--count`)
  *   - `--help`: print usage to stdout and exit 0
  */
private[runner] object Args:

    /** Result of a successful parse.
      *
      * @param config
      *   the resolved [[RunConfig]] (with filter, parallelism, etc. applied)
      * @param reporterArgs
      *   reporter value strings collected from `--reporter=` flags
      * @param positional
      *   non-flag arguments (e.g. fully-qualified class names); when no `--filter=` flag is present these are promoted to
      *   `RunConfig.filter.pathInclude` by [[Args.parse]]
      */
    final case class Parsed(config: RunConfig, reporterArgs: Chunk[String], positional: Chunk[String]) derives CanEqual

    /** Outcome of [[Args.parse]]. */
    sealed trait Result derives CanEqual
    object Result:
        /** Parsing succeeded; proceed with the run. */
        final case class Ok(parsed: Parsed) extends Result derives CanEqual

        /** `--help` was requested; the caller should print usage and exit 0. */
        case object Help extends Result

        /** An unrecognised or malformed argument was encountered. */
        final case class Error(message: String) extends Result derives CanEqual
    end Result

    // Parse an integer string, returning kyo.Result.succeed(int) or kyo.Result.fail(error message).
    private[runner] def parseInt(s: String, flag: String): kyo.Result[String, Int] =
        s.toIntOption match
            case Some(n) => kyo.Result.succeed(n)
            case None    => kyo.Result.fail(s"invalid value for $flag: '$s' (expected an integer)")

    // Parse a long string, returning kyo.Result.succeed(long) or kyo.Result.fail(error message).
    private[runner] def parseLong(s: String, flag: String): kyo.Result[String, Long] =
        s.toLongOption match
            case Some(n) => kyo.Result.succeed(n)
            case None    => kyo.Result.fail(s"invalid seed for $flag: '$s' (expected a long integer)")

    def parse(args: Array[String]): Result =

        /** Accumulated state for the tail-recursive loop. */
        final case class Acc(
            parallelism: Int,
            randomize: Maybe[Long],
            pathIncludes: Chunk[String],
            tagsInclude: Set[String],
            tagsExclude: Set[String],
            verbosity: Verbosity,
            countOnly: Boolean,
            listOnly: Boolean,
            reporterArgs: Chunk[String],
            positional: Chunk[String]
        )

        val initial = Acc(
            parallelism = 0,
            randomize = Maybe.empty,
            pathIncludes = Chunk.empty,
            tagsInclude = Set.empty,
            tagsExclude = Set.empty,
            verbosity = Verbosity.Normal,
            countOnly = false,
            listOnly = false,
            reporterArgs = Chunk.empty,
            positional = Chunk.empty
        )

        @tailrec
        def parseLoop(remaining: Chunk[String], acc: Acc, error: Maybe[String]): Result =
            if remaining.isEmpty then
                error match
                    case Maybe.Present(msg) => Result.Error(msg)
                    case Maybe.Absent =>
                        val config = RunConfig(
                            filter = TestFilter(
                                pathInclude = acc.pathIncludes,
                                tagsInclude = acc.tagsInclude,
                                tagsExclude = acc.tagsExclude
                            ),
                            parallelism = acc.parallelism,
                            randomize = acc.randomize,
                            verbosity = acc.verbosity,
                            countOnly = acc.countOnly || acc.listOnly,
                            listOnly = acc.listOnly
                        )
                        Result.Ok(Parsed(config, acc.reporterArgs, acc.positional))
            else
                val arg  = remaining.head
                val rest = remaining.drop(1)
                error match
                    case Maybe.Present(msg) => Result.Error(msg)
                    case Maybe.Absent =>
                        if arg == "--help" || arg == "-h" then
                            Result.Help
                        else if arg == "--verbose" then
                            parseLoop(rest, acc.copy(verbosity = Verbosity.Verbose), Maybe.empty)
                        else if arg == "--quiet" then
                            parseLoop(rest, acc.copy(verbosity = Verbosity.Quiet), Maybe.empty)
                        else if arg == "--count" then
                            parseLoop(rest, acc.copy(countOnly = true), Maybe.empty)
                        else if arg == "--list" then
                            parseLoop(rest, acc.copy(listOnly = true), Maybe.empty)
                        else if arg == "--randomize" then
                            parseLoop(rest, acc.copy(randomize = Maybe(java.lang.System.currentTimeMillis())), Maybe.empty)
                        else if arg.startsWith("--randomize=") then
                            val s = arg.drop("--randomize=".length)
                            parseLong(s, "--randomize") match
                                case kyo.Result.Success(seed)   => parseLoop(rest, acc.copy(randomize = Maybe(seed)), Maybe.empty)
                                case kyo.Result.Failure(errMsg) => parseLoop(rest, acc, Maybe(errMsg))
                            end match
                        else if arg.startsWith("--parallel=") then
                            val s = arg.drop("--parallel=".length)
                            parseInt(s, "--parallel") match
                                case kyo.Result.Success(n)      => parseLoop(rest, acc.copy(parallelism = n), Maybe.empty)
                                case kyo.Result.Failure(errMsg) => parseLoop(rest, acc, Maybe(errMsg))
                            end match
                        else if arg.startsWith("--filter=") then
                            val glob = arg.drop("--filter=".length)
                            parseLoop(rest, acc.copy(pathIncludes = acc.pathIncludes :+ glob), Maybe.empty)
                        else if arg.startsWith("--tag=") then
                            parseLoop(rest, acc.copy(tagsInclude = acc.tagsInclude + arg.drop("--tag=".length)), Maybe.empty)
                        else if arg.startsWith("--exclude-tag=") then
                            parseLoop(rest, acc.copy(tagsExclude = acc.tagsExclude + arg.drop("--exclude-tag=".length)), Maybe.empty)
                        else if arg.startsWith("--reporter=") then
                            val reporterArgStr = arg.drop("--reporter=".length)
                            val reporterValues = Chunk.from(reporterArgStr.split(',').map(_.trim).toIndexedSeq)
                            parseLoop(rest, acc.copy(reporterArgs = acc.reporterArgs ++ reporterValues), Maybe.empty)
                        else if arg.startsWith("--") then
                            parseLoop(rest, acc, Maybe(s"unknown argument: '$arg'"))
                        else
                            parseLoop(rest, acc.copy(positional = acc.positional :+ arg), Maybe.empty)
                end match
        end parseLoop

        parseLoop(Chunk.from(args.toIndexedSeq), initial, Maybe.empty) match
            case ok @ Result.Ok(parsed) if parsed.reporterArgs.isEmpty =>
                ok
            case Result.Ok(parsed) =>
                // Post-parse reporter translation: convert reporter values to TestReporter instances
                var reporters        = Chunk.empty[kyo.test.TestReporter]
                var errorMsg         = Maybe.empty[String]
                val reporterArgsIter = parsed.reporterArgs.iterator
                while reporterArgsIter.hasNext && errorMsg.isEmpty do
                    val reporterArg = reporterArgsIter.next()
                    if reporterArg == "console" then
                        reporters = reporters :+ Reporters.console(parsed.config.verbosity)
                    else if reporterArg == "tap" then
                        reporters = reporters :+ TapReporter(java.lang.System.out)
                    else if reporterArg.startsWith("tap:") then
                        val path = reporterArg.drop("tap:".length)
                        ReportersPlatform.makeTapFile(path) match
                            case kyo.Result.Success(r)      => reporters = reporters :+ r
                            case kyo.Result.Failure(errMsg) => errorMsg = Maybe(errMsg)
                        end match
                    else if reporterArg.startsWith("junit-xml:") then
                        val path = reporterArg.drop("junit-xml:".length)
                        if path.isEmpty then
                            errorMsg = Maybe("--reporter=junit-xml requires a path: --reporter=junit-xml:./out")
                        else
                            ReportersPlatform.makeJunitXml(path) match
                                case kyo.Result.Success(r)      => reporters = reporters :+ r
                                case kyo.Result.Failure(errMsg) => errorMsg = Maybe(errMsg)
                            end match
                        end if
                    else if reporterArg == "junit-xml" then
                        errorMsg = Maybe("--reporter=junit-xml requires a path: --reporter=junit-xml:./out")
                    else
                        errorMsg = Maybe(s"unknown reporter: $reporterArg")
                    end if
                end while
                errorMsg match
                    case Maybe.Present(msg) => Result.Error(msg)
                    case Maybe.Absent =>
                        val resolvedReporter =
                            if reporters.size == 1 then Maybe(reporters.head)
                            else Maybe(CombinedReporter(reporters*))
                        Result.Ok(parsed.copy(config = parsed.config.copy(reporter = resolvedReporter)))
                end match
            case other =>
                other
        end match
    end parse

    val usage: String =
        """|Usage: kyo-test [options]
           |
           |Options:
           |  --parallel=N            Parallelism: 1 = within-suite sequential; 0 (default) or N>1 = parallel, bounded by the runner's global pool (N>1 is not a per-suite cap)
           |  --randomize             Randomize leaf execution order (uses current time as seed)
           |  --randomize=SEED        Randomize with a specific seed for reproducibility
           |  --filter=GLOB           Include only leaves whose path matches GLOB (repeatable)
           |  --tag=NAME              Include only leaves tagged NAME (repeatable)
           |  --exclude-tag=NAME      Exclude leaves tagged NAME (repeatable)
           |  --reporter=VALUE        Add a reporter: console, tap, junit-xml:PATH (comma-separated or repeatable)
           |  --verbose               Print per-leaf start lines (verbose output)
           |  --quiet                 Suppress per-leaf pass/skip/pending lines; show only failures + summary
           |  --count                 Discovery only: enumerate and report the leaf count without executing any leaf body
           |  --list                  Discovery only: print every leaf's full name path (implies --count); for exact name diffs
           |  --help, -h              Print this help message and exit
           |""".stripMargin

end Args
