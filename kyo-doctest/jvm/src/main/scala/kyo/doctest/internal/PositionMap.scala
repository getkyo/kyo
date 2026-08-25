package kyo.doctest.internal

import kyo.*

/** Maps synthetic compiler positions back to README line numbers.
  *
  * Built from a Chunk of WrappedBlocks produced by WrappedBlock and CompileUnit. The map is keyed by (synthFile, synthLine) and returns the
  * corresponding (Block, blockBodyLine) pair, from which the README line can be computed.
  *
  * For lines not in any lineMap entry (boilerplate: package declaration, object header/closer), translate returns Absent.
  */
final private[kyo] class PositionMap private (records: Map[kyo.Path, Map[Int, (Block, Int, Chunk[Block])]]):

    /** Translates a synthetic file + line to the originating block and block-body line.
      *
      * Returns Present((block, blockBodyLine)) when the line is mapped. blockBodyLine == 0 means the line belongs to a setup prelude.
      * Returns Absent for boilerplate lines (package, object header/closer) that have no lineMap entry.
      */
    def translate(synthFile: kyo.Path, synthLine: Int): Maybe[(Block, Int)] =
        records.get(synthFile) match
            case None => Absent
            case Some(lineMap) =>
                lineMap.get(synthLine) match
                    case None                       => Absent
                    case Some((block, bodyLine, _)) => Present((block, bodyLine))

    /** Translates a runtime stack frame to the block and README line that supplied the executed code. */
    def translateRuntime(synthFile: kyo.Path, synthLine: Int): Maybe[(Block, Int)] =
        records.get(synthFile) match
            case None => Absent
            case Some(lineMap) =>
                lineMap.get(synthLine) match
                    case None => Absent
                    case Some((block, blockBodyLine, setupBlocks)) =>
                        if blockBodyLine >= 1 then Present((block, block.lineStart + blockBodyLine))
                        else backMapPreludePosition(synthLine, lineMap, setupBlocks)
    end translateRuntime

    /** Translates a Driver.Diagnostic to a PositionMap.MappedDiagnostic.
      *
      * Returns Absent for boilerplate lines. On Present, computes the README line from blockBodyLine.
      *
      * For blockBodyLine >= 1: readmeLine = block.lineStart + blockBodyLine (lineStart is the opener; body line 1 is lineStart + 1).
      *
      * For blockBodyLine == 0 (prelude line): attempts to back-map to the originating setup block using the WrappedBlock's setupBlocks
      * list. If the list is empty (fallback), readmeLine = block.lineStart and "(setup prelude)" is appended to the message.
      */
    def translateDiagnostic(d: Driver.Diagnostic): Maybe[PositionMap.MappedDiagnostic] =
        records.get(d.file) match
            case None => Absent
            case Some(lineMap) =>
                lineMap.get(d.line) match
                    case None => Absent
                    case Some((block, blockBodyLine, setupBlocks)) =>
                        val (readmeLine, message) =
                            if blockBodyLine >= 1 then
                                // Normal block body line: readmeLine = opener + offset into body.
                                (block.lineStart + blockBodyLine, d.message)
                            else
                                // Prelude line (blockBodyLine == 0): try to find originating setup block.
                                backMapPreludeLine(d.line, lineMap, block, setupBlocks, d.message)
                        Present(PositionMap.MappedDiagnostic(
                            severity = d.severity,
                            block = block,
                            readmeLine = readmeLine,
                            col = d.col,
                            message = message,
                            carrier = block.carrier
                        ))

    /** Back-maps a prelude synthetic line to its originating setup block and README line.
      *
      * Algorithm:
      *   1. Count how many prelude lines precede the target synthLine in the lineMap (blockBodyLine == 0 entries at synthLine <= target).
      *   2. Walk setupBlocks in order; each contributes block.body.linesIterator.length prelude lines.
      *   3. Find the setup block that owns prelLineIndex (0-indexed).
      *   4. Compute offsetWithinSetup = prelLineIndex - priorSetupLinesCount.
      *   5. readmeLine = setupBlock.lineStart + 1 + offsetWithinSetup.
      *
      * Fallback (setupBlocks empty or line not found in setup bodies): readmeLine = block.lineStart, message appended with "(setup
      * prelude)".
      */
    private def backMapPreludeLine(
        targetSynthLine: Int,
        lineMap: Map[Int, (Block, Int, Chunk[Block])],
        block: Block,
        setupBlocks: Chunk[Block],
        originalMessage: String
    ): (Int, String) =
        backMapPreludePosition(targetSynthLine, lineMap, setupBlocks) match
            case Present((_, readmeLine)) => (readmeLine, originalMessage)
            case Absent                   => (block.lineStart, originalMessage + " (setup prelude)")
    end backMapPreludeLine

    private def backMapPreludePosition(
        targetSynthLine: Int,
        lineMap: Map[Int, (Block, Int, Chunk[Block])],
        setupBlocks: Chunk[Block]
    ): Maybe[(Block, Int)] =
        val preludeLineIndex = lineMap
            .toSeq
            .count { case (synthLine, (_, bodyLine, _)) => bodyLine == 0 && synthLine <= targetSynthLine } - 1

        @scala.annotation.tailrec
        def loop(remaining: Int, blocks: List[Block]): Maybe[(Block, Int)] =
            blocks match
                case Nil => Absent
                case setupBlock :: rest =>
                    val lineCount = setupBlock.body.linesIterator.length
                    if remaining < lineCount then Present((setupBlock, setupBlock.lineStart + 1 + remaining))
                    else loop(remaining - lineCount, rest)

        if preludeLineIndex < 0 then Absent
        else loop(preludeLineIndex, setupBlocks.toList)
    end backMapPreludePosition

end PositionMap

object PositionMap:

    /** A single compiler diagnostic mapped back to its origin in a Markdown README file.
      *
      * @param severity
      *   Error, Warning, or Info (from the compiler).
      * @param block
      *   The block whose body (or setup prelude) triggered the diagnostic.
      * @param readmeLine
      *   1-indexed line in the README file that corresponds to the offending synthetic line.
      * @param col
      *   1-indexed column (copied directly from Driver.Diagnostic.col, already 1-indexed).
      * @param message
      *   The compiler's message text.
      * @param carrier
      *   How the block is embedded in the Markdown (Bare, Details, HtmlComment).
      */
    case class MappedDiagnostic(
        severity: Driver.Diagnostic.Severity,
        block: Block,
        readmeLine: Int,
        col: Int,
        message: String,
        carrier: Block.Carrier
    ) derives CanEqual

    /** Builds a PositionMap from all WrappedBlocks in one or more compile units.
      *
      * For env-grouped blocks sharing the same synthFile, entries from each WrappedBlock are accumulated without collision (their lineMap
      * ranges are disjoint).
      */
    def init(units: Chunk[CompileUnit]): PositionMap =
        val allWrapped = units.flatMap(_.blocks)
        init(allWrapped)

    /** Builds a PositionMap directly from a Chunk of WrappedBlocks.
      *
      * Exposed for tests and the Orchestrator.
      */
    @scala.annotation.targetName("initFromWrapped")
    def init(wrappedBlocks: Chunk[WrappedBlock]): PositionMap =
        val records = wrappedBlocks.toSeq.foldLeft(Map.empty[kyo.Path, Map[Int, (Block, Int, Chunk[Block])]]) {
            (acc, wb) =>
                val fileMap = acc.getOrElse(wb.synthFile, Map.empty)
                val newFileMap = wb.lineMap.toSeq.foldLeft(fileMap) { (fm, entry) =>
                    val (synthLine, blockBodyLine) = entry
                    fm.updated(synthLine, (wb.block, blockBodyLine, wb.setupBlocks))
                }
                acc.updated(wb.synthFile, newFileMap)
        }
        new PositionMap(records)
    end init

end PositionMap
