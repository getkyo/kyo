package kyo.doctest.internal

import kyo.*
import scala.annotation.targetName

/** Records the synthetic source and position-map for a single wrapped block.
  *
  * @param block
  *   The original Block as parsed from the Markdown source.
  * @param synthFile
  *   The display name used for the VirtualFile (e.g. "Block_3a7f9c1b_42.scala"). Stored as a kyo.Path for consistency.
  * @param syntheticContent
  *   The full text of the synthetic Scala source, including the package declaration, object wrapper, optional prelude, and block body.
  * @param lineMap
  *   Per-body-line back-mapping: each entry is (synthLine, blockBodyLine), both 1-indexed. blockBodyLine == 0 means the synth line belongs
  *   to a setup prelude (not the block body itself).
  */
final private[kyo] case class WrappedBlock(
    block: Block,
    synthFile: kyo.Path,
    syntheticContent: String,
    lineMap: Chunk[(Int, Int)],
    setupBlocks: Chunk[Block]
) derives CanEqual

/** Wraps individual blocks into synthetic Scala sources.
  *
  * Object name scheme: Block_<fileHash8>_<lineStart> where fileHash8 is the first 8 hex chars of the SHA-256 of the absolute file path
  * string. This keeps names as valid Scala identifiers and avoids filesystem separator characters.
  *
  * Package declaration corner case: if the block body's first non-blank, non-import line starts with "package", the body is emitted at top
  * level (no object wrapper). This is a rare case; the lineMap is adjusted accordingly.
  */
private[kyo] object WrappedBlock:

    private val PackageDecl = "package _doctest_synthetic_"
    private val Newline     = "\n"

    /** Wraps a block with no setup prelude (isolated block in a file with no setup blocks). */
    def init(block: Block): WrappedBlock =
        init(block, Chunk.empty[Block])

    /** Wraps a block with an optional setup prelude, given the setup blocks as Block instances.
      *
      * @param block
      *   The block to wrap.
      * @param setupBlockList
      *   Setup blocks whose bodies are emitted as a prelude inside the object. Stored on the resulting WrappedBlock.
      */
    def init(block: Block, setupBlockList: Chunk[Block]): WrappedBlock =
        wrapWithBodies(block, setupBlockList.map(_.body), setupBlockList)

    /** Wraps a block with an optional setup prelude, given the setup bodies as raw strings.
      *
      * @param block
      *   The block to wrap.
      * @param setupBodies
      *   Bodies from all setup blocks in the file, to be emitted as a prelude inside the object.
      */
    @targetName("initFromBodies")
    def init(block: Block, setupBodies: Chunk[String]): WrappedBlock =
        wrapWithBodies(block, setupBodies, Chunk.empty)

    private def wrapWithBodies(block: Block, setupBodies: Chunk[String], setupBlockList: Chunk[Block]): WrappedBlock =
        val objName   = objectName(block)
        val synthName = s"$objName.scala"
        val blockPath = kyo.Path(synthName)

        val blockBodyLines = block.body.split(Newline, -1).toList
        val hasPackageDecl = firstNonBlankNonImportIsPackage(blockBodyLines)

        val base =
            if hasPackageDecl then
                wrapTopLevel(block, blockPath, setupBodies, blockBodyLines)
            else
                wrapInObject(block, blockPath, objName, setupBodies, blockBodyLines)
        base.copy(setupBlocks = setupBlockList)
    end wrapWithBodies

    // Emit the block body at top level (no object wrapper) when a package declaration is present.
    // Rare corner case: a block body that starts with its own package declaration cannot be wrapped inside an object.
    // lineMap records (synthLine, blockBodyLine) starting from line 1 of the synthetic source.
    private def wrapTopLevel(
        block: Block,
        blockPath: kyo.Path,
        setupBodies: Chunk[String],
        blockBodyLines: List[String]
    ): WrappedBlock =
        val (content, mapBuf) =
            emitSetupAndBody(
                startingLine = 1,
                indent = "",
                prefix = Chunk.empty,
                suffix = Chunk.empty,
                setupBodies = setupBodies,
                blockBodyLines = blockBodyLines
            )
        WrappedBlock(block, blockPath, content, mapBuf, Chunk.empty)
    end wrapTopLevel

    // Wrap the block body in an object.
    // Synthetic source structure:
    //   line 1:  package _doctest_synthetic_
    //   line 2:  object ObjName {
    //   lines 3..: setup prelude lines (if any), then block body lines
    //   last line: }
    private def wrapInObject(
        block: Block,
        blockPath: kyo.Path,
        objName: String,
        setupBodies: Chunk[String],
        blockBodyLines: List[String]
    ): WrappedBlock =
        val prefix = Chunk(PackageDecl + Newline, s"object $objName {" + Newline)
        val suffix = Chunk("}" + Newline)
        val (content, mapBuf) =
            emitSetupAndBody(
                startingLine = 3,
                indent = "    ",
                prefix = prefix,
                suffix = suffix,
                setupBodies = setupBodies,
                blockBodyLines = blockBodyLines
            )
        WrappedBlock(block, blockPath, content, mapBuf, Chunk.empty)
    end wrapInObject

    /** Shared body-emission logic for `wrapTopLevel` and `wrapInObject`.
      *
      * Builds synthetic content from prefix lines, setup-body lines, and block-body lines followed by suffix lines. Produces a position-map
      * entry per emitted content line (setup lines map to `(synthLine, 0)`, block-body lines map to `(synthLine, blockBodyLine)`
      * 1-indexed). Prefix and suffix lines are not mapped.
      *
      * Returns the joined synthetic content and the line map as a Chunk.
      */
    private def emitSetupAndBody(
        startingLine: Int,
        indent: String,
        prefix: Chunk[String],
        suffix: Chunk[String],
        setupBodies: Chunk[String],
        blockBodyLines: List[String]
    ): (String, Chunk[(Int, Int)]) =
        // Emit setup lines with blockBodyLine == 0.
        val allSetupLines = setupBodies.toList.flatMap(_.split(Newline, -1).toList)
        val (setupEmitted, setupMap, lineAfterSetup) =
            allSetupLines.foldLeft((Chunk.empty[String], List.empty[(Int, Int)], startingLine)) {
                case ((lines, mapAcc, ln), sl) =>
                    (lines :+ (indent + sl + Newline), mapAcc :+ (ln, 0), ln + 1)
            }

        // Emit block body lines with 1-indexed blockBodyLine.
        val (bodyEmitted, bodyMap, _) =
            blockBodyLines.zipWithIndex.foldLeft((Chunk.empty[String], List.empty[(Int, Int)], lineAfterSetup)) {
                case ((lines, mapAcc, ln), (fl, idx)) =>
                    (lines :+ (indent + fl + Newline), mapAcc :+ (ln, idx + 1), ln + 1)
            }

        val allLines = prefix ++ setupEmitted ++ bodyEmitted ++ suffix
        val mapBuf   = setupMap ++ bodyMap
        (allLines.mkString, Chunk.from(mapBuf))
    end emitSetupAndBody

    /** Returns the object name for a block: Block_<fileHash8>_<lineStart>. */
    private[internal] def objectName(block: Block): String =
        val hash8 = Hashing.sha256First8(block.file.toString)
        s"Block_${hash8}_${block.lineStart}"

    /** Returns true if the first non-blank, non-import line of the block body starts with "package".
      *
      * This handles the rare case where a block body declares its own package (which cannot be wrapped inside an object).
      */
    private def firstNonBlankNonImportIsPackage(lines: List[String]): Boolean =
        lines
            .filter(l => l.trim.nonEmpty && !l.trim.startsWith("import ") && !l.trim.startsWith("import\t"))
            .headOption
            .exists(_.trim.startsWith("package "))
    end firstNonBlankNonImportIsPackage

end WrappedBlock
