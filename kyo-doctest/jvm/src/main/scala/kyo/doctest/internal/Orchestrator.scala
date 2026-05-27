package kyo.doctest.internal

import kyo.*
import kyo.doctest.*

/** Composes all kyo-doctest subsystems into a single validation run.
  *
  * The run proceeds in five stages:
  *   1. Acquire a warm Driver via Scope.acquireRelease (one per run, amortises init cost across all blocks).
  *   2. Open (or create) the BlockCache directory.
  *   3. Compute a classpath fingerprint (once for the whole run).
  *   4. For each source file: parse blocks, group into CompileUnits; for each unit: cache lookup, compile on miss.
  *   5. Translate all compiler diagnostics back to README positions and assemble a Report.
  */
private[kyo] object Orchestrator:

    // Internal per-block outcome, before reduction to Report.
    sealed private[internal] trait BlockOutcome derives CanEqual
    private[internal] object BlockOutcome:
        case class Skipped(block: Block, fromCache: Boolean)                  extends BlockOutcome
        case class Success(block: Block, fromCache: Boolean)                  extends BlockOutcome
        case class Failure(block: Block, message: String, fromCache: Boolean) extends BlockOutcome
    end BlockOutcome

    /** Runs a complete validation pass, honouring the supplied Config.
      *
      * Returns a Report summarising how many blocks were found, how many were cache hits, how many were compiled, and what failures were
      * encountered. Fatal setup failures (bad classpath, missing source file) are surfaced as Abort[Doctest.Error].
      *
      * Abort[Doctest.Error.DriverInitFailed] widens to Abort[Doctest.Error] via contravariance of Abort.
      */
    def run(config: Doctest.Config)(using Frame): Doctest.Report < (Sync & Async & Scope & Abort[Doctest.Error]) =
        if config.sources.isEmpty then
            Abort.fail(Doctest.Error.NoSourcesConfigured)
        else
            Scope.acquireRelease(Driver.init(config.classpath, config.scalaOpts, config.freshDriver))(_.close).flatMap { driver =>
                for
                    cache       <- BlockCache.init(config.cache)
                    fingerprint <- ClasspathFingerprint.compute(config.classpath)
                    scalaVer = scala.util.Properties.versionNumberString
                    allOutcomes  <- processAllSources(config, driver, cache, fingerprint, scalaVer)
                    linkFailures <- validateAllLinks(config.sources)
                yield buildReport(allOutcomes, linkFailures)
            }

    // Validate internal links in every source. Failures are merged into the final report.
    private def validateAllLinks(sources: Chunk[Path])(using Frame): Chunk[Doctest.Failure] < (Sync & Abort[Doctest.Error]) =
        Kyo.foreach(sources)(LinkValidator.validate).map(_.flatten)

    // Processes all source files, returning a flat list of per-block outcomes.
    private def processAllSources(
        config: Doctest.Config,
        driver: Driver,
        cache: BlockCache,
        fingerprint: String,
        scalaVer: String
    )(using Frame): Chunk[BlockOutcome] < (Sync & Async & Scope & Abort[Doctest.Error]) =
        Kyo.foreach(config.sources) { sourcePath =>
            sourcePath.exists.flatMap { exists =>
                if !exists then Abort.fail(Doctest.Error.SourceNotFound(sourcePath))
                else processOneSource(sourcePath, config, driver, cache, fingerprint, scalaVer)
            }
        }.map(_.flatten)

    // Parses and processes one source file, returning per-block outcomes.
    private def processOneSource(
        sourcePath: kyo.Path,
        config: Doctest.Config,
        driver: Driver,
        cache: BlockCache,
        fingerprint: String,
        scalaVer: String
    )(using Frame): Chunk[BlockOutcome] < (Sync & Async & Scope & Abort[Doctest.Error]) =
        MarkdownParser.parse(sourcePath).flatMap { blocks =>
            val withPredef = injectPredef(blocks, config.predef, sourcePath)
            val units      = CompileUnit.group(withPredef)
            processUnits(units, driver, cache, fingerprint, scalaVer, config)
        }

    // Prepends a synthetic setup block built from the configured predef, so every block (including
    // env:NAME groups, which would otherwise opt out of __doc__ defaulting) sees the predef lines.
    // No-op when the predef is empty.
    private def injectPredef(blocks: Chunk[Block], predef: Chunk[String], sourcePath: kyo.Path): Chunk[Block] =
        if predef.isEmpty then blocks
        else
            val predefBlock = Block(
                file = sourcePath,
                lineStart = 0,
                lineEnd = 0,
                body = predef.toSeq.mkString("\n"),
                visibility = Block.Visibility.Env("__doc__"),
                expect = Block.Expectation.Compiles,
                platform = Set(Block.Target.JVM, Block.Target.JS, Block.Target.Native),
                carrier = Block.Carrier.Hidden
            )
            Chunk.from(predefBlock +: blocks.toSeq)

    // Processes all compile units from one file in parallel (up to config.parallel).
    private def processUnits(
        units: Chunk[CompileUnit],
        driver: Driver,
        cache: BlockCache,
        fingerprint: String,
        scalaVer: String,
        config: Doctest.Config
    )(using Frame): Chunk[BlockOutcome] < (Sync & Async & Scope & Abort[Doctest.Error]) =
        Async.foreach(units.toSeq, config.parallel) { unit =>
            processUnit(unit, driver, cache, fingerprint, scalaVer, config.scalaOpts)
        }.map(_.flatten)

    // Processes one compile unit: cache lookup, compile on miss, translate results.
    private def processUnit(
        unit: CompileUnit,
        driver: Driver,
        cache: BlockCache,
        fingerprint: String,
        scalaVer: String,
        scalacOpts: Chunk[String]
    )(using Frame): Chunk[BlockOutcome] < (Sync & Async) =
        // Use the first block in the unit as the representative for the cache key.
        // For env-grouped units all blocks share one compile result.
        val firstWrapped = unit.blocks(0)
        val scopeClosure = firstWrapped.setupBlocks.map(_.body)
        cache.lookup(firstWrapped.block, scopeClosure, fingerprint, scalaVer, scalacOpts).flatMap {
            case Maybe.Present(entry) =>
                // Cache hit: build outcomes from stored result, no compile needed.
                val posMap = PositionMap.init(unit.blocks)
                Chunk.from(unit.blocks.toSeq.map(wb => toOutcomeFromResult(wb, entry.result, posMap, fromCache = true)))
            case Maybe.Absent =>
                // Cache miss: compile, record, produce outcomes.
                driver.compile(unit.syntheticSource).flatMap { result =>
                    cache.record(firstWrapped.block, scopeClosure, fingerprint, scalaVer, scalacOpts, result).map { _ =>
                        val posMap = PositionMap.init(unit.blocks)
                        Chunk.from(unit.blocks.toSeq.map(wb => toOutcomeFromResult(wb, result, posMap, fromCache = false)))
                    }
                }
        }
    end processUnit

    // Translates a Driver.Outcome to a BlockOutcome for one wrapped block.
    private def toOutcomeFromResult(
        wb: WrappedBlock,
        result: Driver.Outcome,
        posMap: PositionMap,
        fromCache: Boolean
    ): BlockOutcome =
        val block  = wb.block
        val expect = block.expect

        expect match
            case Block.Expectation.Skipped =>
                BlockOutcome.Skipped(block, fromCache)

            case Block.Expectation.FailsCompile =>
                result match
                    case _: Driver.Outcome.Failed =>
                        // Expected failure; compile did fail: success.
                        BlockOutcome.Success(block, fromCache)
                    case Driver.Outcome.Ok(_) =>
                        // Expected compile failure but compiled clean: report failure.
                        val msg = "expected compile failure but compiled clean"
                        BlockOutcome.Failure(block, msg, fromCache)

            case Block.Expectation.Warns =>
                result match
                    case Driver.Outcome.Ok(warnings) if warnings.nonEmpty =>
                        BlockOutcome.Success(block, fromCache)
                    case Driver.Outcome.Ok(_) =>
                        val msg = "expected at least one compiler warning but none were emitted"
                        BlockOutcome.Failure(block, msg, fromCache)
                    case Driver.Outcome.Failed(errors, _) =>
                        val msgs = errors.map(_.message).mkString("; ")
                        BlockOutcome.Failure(block, s"expected warnings but block failed to compile: $msgs", fromCache)

            case Block.Expectation.Compiles | Block.Expectation.Runs | Block.Expectation.Crashes =>
                result match
                    case Driver.Outcome.Ok(_) =>
                        BlockOutcome.Success(block, fromCache)
                    case Driver.Outcome.Failed(errors, _) =>
                        // Translate diagnostic positions back to README lines.
                        val msgs = errors.map { d =>
                            posMap.translateDiagnostic(d) match
                                case Maybe.Present(md) =>
                                    s"${md.block.file}:${md.readmeLine}:${md.col}: error: ${md.message}"
                                case Maybe.Absent =>
                                    s"error: ${d.message}"
                        }
                        val msg = msgs.mkString("\n")
                        BlockOutcome.Failure(block, msg, fromCache)
        end match
    end toOutcomeFromResult

    // Accumulator for buildReport fold.
    private case class ReportAcc(
        totalBlocks: Int,
        cacheHits: Int,
        compiled: Int,
        failures: List[Doctest.Failure]
    )

    // Assembles the final Report from all per-block outcomes and link-validation failures.
    private def buildReport(outcomes: Chunk[BlockOutcome], linkFailures: Chunk[Doctest.Failure]): Doctest.Report =
        val acc = outcomes.toSeq.foldLeft(ReportAcc(0, 0, 0, Nil)) { (a, outcome) =>
            outcome match
                case BlockOutcome.Skipped(_, _) =>
                    // Skipped blocks are not counted as cache hits or compiled.
                    a.copy(totalBlocks = a.totalBlocks + 1)

                case BlockOutcome.Success(_, fromCache) =>
                    if fromCache then a.copy(totalBlocks = a.totalBlocks + 1, cacheHits = a.cacheHits + 1)
                    else a.copy(totalBlocks = a.totalBlocks + 1, compiled = a.compiled + 1)

                case BlockOutcome.Failure(block, message, fromCache) =>
                    val failure = Doctest.Failure(block.file, block.lineStart, message)
                    if fromCache then
                        a.copy(totalBlocks = a.totalBlocks + 1, cacheHits = a.cacheHits + 1, failures = a.failures :+ failure)
                    else
                        a.copy(totalBlocks = a.totalBlocks + 1, compiled = a.compiled + 1, failures = a.failures :+ failure)
                    end if
        }
        Doctest.Report(acc.totalBlocks, acc.cacheHits, acc.compiled, Chunk.from(acc.failures) ++ linkFailures)
    end buildReport

end Orchestrator
