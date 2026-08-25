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
  *   5. Execute runtime-bearing units in isolated child JVMs.
  *   6. Translate compiler diagnostics and runtime failures back to README positions, then assemble a Report.
  */
private[kyo] object Orchestrator:

    // Internal per-block outcome, before reduction to Report.
    sealed private[internal] trait BlockOutcome derives CanEqual
    private[internal] object BlockOutcome:
        case class Skipped(block: Block, fromCache: Boolean)                  extends BlockOutcome
        case class Success(block: Block, fromCache: Boolean, warnings: Int)   extends BlockOutcome
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
            Abort.recover[FileReadException](e => Abort.fail(Doctest.Error.IoError(sourcePath, "exists", e))) {
                sourcePath.exists
            }.flatMap { exists =>
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
            val skippedOutcomes = blocks
                .filter(_.expect == Block.Expectation.Skipped)
                .map(block => BlockOutcome.Skipped(block, fromCache = false))
            val activeBlocks = blocks.filter(_.expect != Block.Expectation.Skipped)
            val withPredef =
                if activeBlocks.isEmpty then activeBlocks
                else injectPredef(activeBlocks, config.predef, sourcePath)
            val units = CompileUnit.group(withPredef)
            processUnits(units, driver, cache, fingerprint, scalaVer, config).map(outcomes => skippedOutcomes ++ outcomes)
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
        // For env-grouped units all blocks share one compile result, so the cache key must reflect
        // EVERY block in the unit, not just the first: appending the remaining blocks' bodies to the
        // scope closure makes editing any block (not only blocks(0)) invalidate the entry. For
        // isolated/inherited/nested units `drop(1)` is empty, so their keys are unchanged.
        val firstWrapped    = unit.blocks(0)
        val scopeClosure    = firstWrapped.setupBlocks.map(_.body) ++ unit.blocks.drop(1).map(_.block.body)
        val requiresRuntime = unit.blocks.exists(wb => isRuntimeExpectation(wb.block.expect))

        if requiresRuntime then
            // Compile every runtime-bearing unit on every run. The cache stores diagnostics, not class files,
            // and runtime behavior may depend on ambient state that must be observed again.
            val runtimeUnit = CompileUnit.instrumentRuntime(unit)
            driver.compile(runtimeUnit.syntheticSource).flatMap { result =>
                toOutcomesFromResult(runtimeUnit, result, driver, fromCache = false)
            }
        else
            cache.lookup(firstWrapped.block, scopeClosure, fingerprint, scalaVer, scalacOpts).flatMap {
                case Maybe.Present(entry) =>
                    toOutcomesFromResult(unit, entry.result, driver, fromCache = true)
                case Maybe.Absent =>
                    driver.compile(unit.syntheticSource).flatMap { result =>
                        cache.record(firstWrapped.block, scopeClosure, fingerprint, scalaVer, scalacOpts, result).flatMap { _ =>
                            toOutcomesFromResult(unit, result, driver, fromCache = false)
                        }
                    }
            }
        end if
    end processUnit

    private def toOutcomesFromResult(
        unit: CompileUnit,
        result: Driver.Outcome,
        driver: Driver,
        fromCache: Boolean
    )(using Frame): Chunk[BlockOutcome] < (Sync & Async) =
        val posMap = PositionMap.init(unit.blocks)
        result match
            case Driver.Outcome.Ok(warnings) =>
                unit.blocks.find(wb => isRuntimeExpectation(wb.block.expect)) match
                    case Some(runtimeBlock) =>
                        val blockTimeouts = unit.blocks.toSeq.map(wb => wb.block.lineStart -> wb.block.timeout).toMap
                        driver.execute(runtimeBlock, blockTimeouts).map { runtimeResult =>
                            Chunk.from(unit.blocks.toSeq.map { wb =>
                                if isRuntimeExpectation(wb.block.expect) then
                                    toRuntimeOutcome(wb, unit, runtimeResult, posMap, fromCache, warnings.size)
                                else
                                    toOutcomeFromResult(wb, result, posMap, fromCache)
                            })
                        }
                    case None =>
                        Sync.defer(Chunk.from(unit.blocks.toSeq.map(wb => toOutcomeFromResult(wb, result, posMap, fromCache))))
            case _: Driver.Outcome.Failed =>
                Sync.defer(Chunk.from(unit.blocks.toSeq.map(wb => toOutcomeFromResult(wb, result, posMap, fromCache))))
        end match
    end toOutcomesFromResult

    private def isRuntimeExpectation(expectation: Block.Expectation): Boolean =
        expectation == Block.Expectation.Runs || expectation == Block.Expectation.Crashes

    private def toRuntimeOutcome(
        wb: WrappedBlock,
        unit: CompileUnit,
        result: RuntimeExecutor.Outcome,
        posMap: PositionMap,
        fromCache: Boolean,
        warnings: Int
    ): BlockOutcome =
        val block = wb.block
        val effectiveResult: Result.Partial[String, RuntimeExecutor.Outcome] = result match
            case thrown: RuntimeExecutor.Outcome.Threw =>
                posMap.translateRuntime(kyo.Path(thrown.synthFile), thrown.synthLine) match
                    case Present((owner, ownerLine)) =>
                        val blocks     = unit.blocks.toSeq.map(_.block)
                        val ownerIndex = blocks.indexOf(owner)
                        val blockIndex = blocks.indexOf(block)
                        if ownerIndex < 0 then
                            Result.Failure(
                                s"block was not executed because inherited or setup code failed: ${formatRuntimeFailure(thrown, posMap)}"
                            )
                        else if blockIndex < ownerIndex then Result.Success(RuntimeExecutor.Outcome.Completed)
                        else if blockIndex == ownerIndex then Result.Success(thrown)
                        else
                            Result.Failure(s"block was not executed because runtime failed at ${owner.file}:$ownerLine")
                        end if
                    case Absent => Result.Success(thrown)
            case timedOut @ RuntimeExecutor.Outcome.TimedOut(_, progress) =>
                attributeProcessOutcome(block, unit, timedOut, progress)
            case exited @ RuntimeExecutor.Outcome.ProcessExited(_, _, progress) =>
                attributeProcessOutcome(block, unit, exited, progress)
            case other => Result.Success(other)

        effectiveResult match
            case Result.Failure(message)  => BlockOutcome.Failure(block, message, fromCache)
            case Result.Success(observed) => toRuntimeOutcome(block, observed, posMap, fromCache, warnings)
    end toRuntimeOutcome

    private def attributeProcessOutcome(
        block: Block,
        unit: CompileUnit,
        outcome: RuntimeExecutor.Outcome,
        progress: RuntimeExecutor.Progress
    ): Result.Partial[String, RuntimeExecutor.Outcome] =
        progress match
            case RuntimeExecutor.Progress.NotStarted =>
                Result.Failure("runtime process terminated before the child runner started")
            case RuntimeExecutor.Progress.Started =>
                Result.Failure("block was not executed because inherited, setup, or class-loading code terminated the runtime process")
            case RuntimeExecutor.Progress.Block(activeLine) =>
                val blocks      = unit.blocks.toSeq.map(_.block)
                val activeIndex = blocks.indexWhere(_.lineStart == activeLine)
                val blockIndex  = blocks.indexOf(block)
                if activeIndex < 0 then Result.Failure(s"runtime process reported unknown active block line $activeLine")
                else if blockIndex < activeIndex then Result.Success(RuntimeExecutor.Outcome.Completed)
                else if blockIndex == activeIndex then Result.Success(outcome)
                else
                    val active = blocks(activeIndex)
                    Result.Failure(s"block was not executed because runtime terminated in ${active.file}:${active.lineStart}")
                end if
    end attributeProcessOutcome

    private def toRuntimeOutcome(
        block: Block,
        result: RuntimeExecutor.Outcome,
        posMap: PositionMap,
        fromCache: Boolean,
        warnings: Int
    ): BlockOutcome =
        block.expect match
            case Block.Expectation.Runs =>
                result match
                    case RuntimeExecutor.Outcome.Completed =>
                        BlockOutcome.Success(block, fromCache, warnings)
                    case thrown: RuntimeExecutor.Outcome.Threw =>
                        BlockOutcome.Failure(block, formatRuntimeFailure(thrown, posMap), fromCache)
                    case RuntimeExecutor.Outcome.TimedOut(after, _) =>
                        BlockOutcome.Failure(block, s"runtime execution timed out after ${after.show}", fromCache)
                    case RuntimeExecutor.Outcome.ProcessExited(code, stderr, _) =>
                        val details = if stderr.isEmpty then "" else s": ${stderr.trim}"
                        BlockOutcome.Failure(block, s"runtime process exited with code $code$details", fromCache)
                    case RuntimeExecutor.Outcome.LaunchFailed(message) =>
                        BlockOutcome.Failure(block, s"runtime process could not be launched: $message", fromCache)
                    case RuntimeExecutor.Outcome.LoadFailed(message) =>
                        BlockOutcome.Failure(block, s"compiled block could not be loaded: $message", fromCache)
                    case RuntimeExecutor.Outcome.Unsupported(message) =>
                        BlockOutcome.Failure(block, message, fromCache)

            case Block.Expectation.Crashes =>
                result match
                    case RuntimeExecutor.Outcome.Completed =>
                        BlockOutcome.Failure(block, "expected a runtime failure but the block completed normally", fromCache)
                    case _: RuntimeExecutor.Outcome.Threw | _: RuntimeExecutor.Outcome.ProcessExited =>
                        BlockOutcome.Success(block, fromCache, warnings)
                    case RuntimeExecutor.Outcome.TimedOut(after, _) =>
                        BlockOutcome.Failure(block, s"runtime execution timed out after ${after.show}", fromCache)
                    case RuntimeExecutor.Outcome.LaunchFailed(message) =>
                        BlockOutcome.Failure(block, s"runtime process could not be launched: $message", fromCache)
                    case RuntimeExecutor.Outcome.LoadFailed(message) =>
                        BlockOutcome.Failure(block, s"compiled block could not be loaded: $message", fromCache)
                    case RuntimeExecutor.Outcome.Unsupported(message) =>
                        BlockOutcome.Failure(block, message, fromCache)

            case _ =>
                BlockOutcome.Failure(block, "internal error: runtime outcome for a compile-only expectation", fromCache)
        end match
    end toRuntimeOutcome

    private def formatRuntimeFailure(thrown: RuntimeExecutor.Outcome.Threw, posMap: PositionMap): String =
        val description =
            if thrown.message.isEmpty then thrown.className
            else s"${thrown.className}: ${thrown.message}"
        posMap.translateRuntime(kyo.Path(thrown.synthFile), thrown.synthLine) match
            case Present((block, readmeLine)) =>
                s"${block.file}:$readmeLine: error: $description"
            case _ =>
                s"runtime error: $description"
        end match
    end formatRuntimeFailure

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
                        BlockOutcome.Success(block, fromCache, 0)
                    case Driver.Outcome.Ok(_) =>
                        // Expected compile failure but compiled clean: report failure.
                        val msg = "expected compile failure but compiled clean"
                        BlockOutcome.Failure(block, msg, fromCache)

            case Block.Expectation.Warns =>
                result match
                    case Driver.Outcome.Ok(warnings) if warnings.nonEmpty =>
                        // The warning is the expected outcome here, so it is not counted as a warning to fix.
                        BlockOutcome.Success(block, fromCache, 0)
                    case Driver.Outcome.Ok(_) =>
                        val msg = "expected at least one compiler warning but none were emitted"
                        BlockOutcome.Failure(block, msg, fromCache)
                    case Driver.Outcome.Failed(errors, _) =>
                        val msgs = errors.map(_.message).mkString("; ")
                        BlockOutcome.Failure(block, s"expected warnings but block failed to compile: $msgs", fromCache)

            case Block.Expectation.Compiles | Block.Expectation.Runs | Block.Expectation.Crashes =>
                result match
                    case Driver.Outcome.Ok(warnings) =>
                        BlockOutcome.Success(block, fromCache, warnings.size)
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
        warnings: Int,
        failures: List[Doctest.Failure]
    )

    // Assembles the final Report from all per-block outcomes and link-validation failures.
    private def buildReport(outcomes: Chunk[BlockOutcome], linkFailures: Chunk[Doctest.Failure]): Doctest.Report =
        val acc = outcomes.toSeq.foldLeft(ReportAcc(0, 0, 0, 0, Nil)) { (a, outcome) =>
            outcome match
                case BlockOutcome.Skipped(_, _) =>
                    // Skipped blocks are not counted as cache hits or compiled.
                    a.copy(totalBlocks = a.totalBlocks + 1)

                case BlockOutcome.Success(_, fromCache, warnings) =>
                    if fromCache then
                        a.copy(totalBlocks = a.totalBlocks + 1, cacheHits = a.cacheHits + 1, warnings = a.warnings + warnings)
                    else a.copy(totalBlocks = a.totalBlocks + 1, compiled = a.compiled + 1, warnings = a.warnings + warnings)

                case BlockOutcome.Failure(block, message, fromCache) =>
                    val failure = Doctest.Failure(block.file, block.lineStart, message)
                    if fromCache then
                        a.copy(totalBlocks = a.totalBlocks + 1, cacheHits = a.cacheHits + 1, failures = a.failures :+ failure)
                    else
                        a.copy(totalBlocks = a.totalBlocks + 1, compiled = a.compiled + 1, failures = a.failures :+ failure)
                    end if
        }
        Doctest.Report(acc.totalBlocks, acc.cacheHits, acc.compiled, acc.warnings, Chunk.from(acc.failures) ++ linkFailures)
    end buildReport

end Orchestrator
