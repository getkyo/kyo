package kyo.doctest.internal

import kyo.*

/** A compile unit: one synthetic source (potentially containing multiple block bodies) that will be sent to the Driver as a single compile
  * call.
  *
  * @param syntheticSource
  *   The synthetic Scala source to compile.
  * @param blocks
  *   The original blocks whose bodies contributed to this compile unit (for position mapping).
  */
final private[kyo] case class CompileUnit(syntheticSource: Driver.Source, blocks: Chunk[WrappedBlock]) derives CanEqual

/** Groups a file's blocks into compile units, honoring Block.Visibility semantics.
  *
  * Isolated (default): one compile unit per block. If the file has setup blocks, their bodies are injected as a prelude inside each
  * isolated block's object.
  *
  * Inherited: one compile unit per block. Each block's synthetic source includes all prior blocks' bodies as a prelude. The compound grows
  * linearly. Setup blocks are injected before the inherited prelude as well, so Inherited blocks see setup bindings.
  *
  * Nested: like Inherited, but each block body is wrapped in a local block so its names do not escape to subsequent blocks. Setup blocks
  * are injected before the inherited prelude here too.
  *
  * Env(name): all blocks sharing the same name form one compile unit. Their bodies are concatenated in document order inside a single
  * object.
  *
  * Env("__doc__") (setup): setup blocks are injected as preludes into isolated blocks. They form a standalone compile unit only if the file
  * contains no other blocks.
  */
private[kyo] object CompileUnit:

    private val Newline = "\n"

    /** Produces compile units from a file's blocks.
      *
      * @param blocks
      *   All blocks from one Markdown file, in document order.
      * @return
      *   Compile units ready to feed to Driver.compile.
      */
    def group(blocks: Chunk[Block]): Chunk[CompileUnit] =
        if blocks.isEmpty then Chunk.empty
        else
            val setupBlocks    = extractSetupBlocks(blocks)
            val nonSetupBlocks = blocks.filter(f => !isSetup(f))
            if nonSetupBlocks.isEmpty then
                // Only setup blocks: compile them as a standalone Env("__doc__") unit.
                groupEnvBlocks(blocks.filter(isSetup), Chunk.empty)
            else
                groupNonSetup(nonSetupBlocks, setupBlocks)
            end if
        end if
    end group

    // Extract the setup blocks (Env("__doc__")) in document order.
    private def extractSetupBlocks(blocks: Chunk[Block]): Chunk[Block] =
        blocks.filter(isSetup)

    private def isSetup(b: Block): Boolean =
        b.visibility == Block.Visibility.Env("__doc__")

    // State threaded through foldLeft for groupNonSetup.
    private case class GroupState(
        result: List[CompileUnit],
        inheritedBlocks: List[Block],
        envAccum: Map[String, List[Block]]
    )

    // Group non-setup blocks, injecting setup blocks into isolated blocks.
    private def groupNonSetup(blocks: Chunk[Block], setupBlocks: Chunk[Block]): Chunk[CompileUnit] =
        // Process in document order. Env blocks are buffered and emitted at the end.
        val blockList = blocks.toList

        val finalState = blockList.foldLeft(GroupState(Nil, Nil, Map.empty)) { (state, block) =>
            block.visibility match
                case Block.Visibility.Isolated =>
                    val wrapped = WrappedBlock.init(block, setupBlocks)
                    state.copy(result = state.result :+ toCompileUnit(wrapped))

                case Block.Visibility.Inherited =>
                    val priorBlocks = setupBlocks ++ Chunk.from(state.inheritedBlocks)
                    val wrapped     = WrappedBlock.init(block, priorBlocks)
                    state.copy(
                        result = state.result :+ toCompileUnit(wrapped),
                        inheritedBlocks = state.inheritedBlocks :+ block
                    )

                case Block.Visibility.Nested =>
                    val priorBlocks   = setupBlocks ++ Chunk.from(state.inheritedBlocks)
                    val nestedWrapped = wrapNested(block, priorBlocks.map(_.body), priorBlocks)
                    // Nested body does NOT leak into subsequent preludes; inheritedBlocks is unchanged.
                    state.copy(result = state.result :+ toCompileUnit(nestedWrapped))

                case Block.Visibility.Env(name) =>
                    val current = state.envAccum.getOrElse(name, Nil)
                    state.copy(envAccum = state.envAccum.updated(name, current :+ block))
        }

        // Emit env groups in first-seen order.
        val envOrder: List[String] = blockList
            .collect {
                case b @ Block(_, _, _, _, Block.Visibility.Env(name), _, _, _) => name
            }
            .distinct

        val envUnits = envOrder.flatMap { name =>
            val group = finalState.envAccum.getOrElse(name, Nil)
            groupEnvBlocks(Chunk.from(group), setupBlocks).toSeq
        }

        Chunk.from(finalState.result ++ envUnits)
    end groupNonSetup

    // Wraps a Nested block: the body is placed inside a local block `{ ... }`.
    // Prior prelude bodies are visible (emitted before the block).
    private def wrapNested(block: Block, preludeBodies: Chunk[String], priorBlocks: Chunk[Block]): WrappedBlock =
        val objName   = WrappedBlock.objectName(block)
        val synthName = s"$objName.scala"
        val blockPath = kyo.Path(synthName)

        val header = Chunk(
            "package _doctest_synthetic_" + Newline, // line 1
            s"object $objName {" + Newline           // line 2
        )

        // Emit prelude lines (blockBodyLine == 0), starting at line 3.
        val preludeLines = preludeBodies.toList.flatMap(_.split(Newline, -1).toList)
        val (preludeEmitted, preludeMap, lineAfterPrelude) =
            preludeLines.zipWithIndex.foldLeft((Chunk.empty[String], List.empty[(Int, Int)], 3)) {
                case ((lines, mapAcc, ln), (pl, _)) =>
                    (lines :+ ("    " + pl + Newline), mapAcc :+ (ln, 0), ln + 1)
            }

        // Nested block opener (not mapped).
        val nestedOpenerLine = lineAfterPrelude
        val lineAfterOpener  = nestedOpenerLine + 1

        // Block body inside the local block.
        val bodyLines = block.body.split(Newline, -1).toList
        val (bodyEmitted, bodyMap, _) =
            bodyLines.zipWithIndex.foldLeft((Chunk.empty[String], List.empty[(Int, Int)], lineAfterOpener)) {
                case ((lines, mapAcc, ln), (fl, idx)) =>
                    (lines :+ ("        " + fl + Newline), mapAcc :+ (ln, idx + 1), ln + 1)
            }

        val allLines =
            (header ++ preludeEmitted) ++
                Chunk("    {" + Newline) ++
                bodyEmitted ++
                Chunk("    }" + Newline, "}" + Newline)
        val content = allLines.mkString
        val mapBuf  = preludeMap ++ bodyMap
        WrappedBlock(block, blockPath, content, Chunk.from(mapBuf), priorBlocks)
    end wrapNested

    // Groups all blocks sharing an env name into one compile unit.
    // The object name is Env_<sanitisedName>_<fileHash8>.
    // setupBlocks: the file's setup blocks (Env("__doc__")). Their bodies are emitted as a prelude
    // at the top of the synthetic env object so env-scoped blocks inherit setup/predef bindings.
    private def groupEnvBlocks(blocks: Chunk[Block], setupBlocks: Chunk[Block]): Chunk[CompileUnit] =
        if blocks.isEmpty then Chunk.empty
        else
            val firstBlock = blocks(0)
            val envName = firstBlock.visibility match
                case Block.Visibility.Env(n) => n
                case _                       => "__doc__"
            val sanitised = sanitiseEnvName(envName)
            val hash8     = Hashing.sha256First8(firstBlock.file.toString)
            val objName   = s"Env_${sanitised}_${hash8}"
            val synthName = s"$objName.scala"
            val blockPath = kyo.Path(synthName)

            // Setup blocks are emitted as a prelude inside the env object unless this IS the
            // setup-only emission path (envName == "__doc__"), in which case the env blocks ARE
            // the setup blocks and we must not duplicate their bodies.
            val emitSetupPrelude = envName != "__doc__"

            val headerLines = Chunk(
                "package _doctest_synthetic_" + Newline, // line 1
                s"object $objName {" + Newline           // line 2
            )

            // Emit setup prelude lines (blockBodyLine == 0), starting at line 3.
            val (setupLines, preludeBuf, lineAfterSetup) =
                if emitSetupPrelude then
                    val allSetupBodyLines = setupBlocks.toList.flatMap(_.body.split(Newline, -1).toList)
                    allSetupBodyLines.foldLeft((Chunk.empty[String], List.empty[(Int, Int)], 3)) {
                        case ((lines, mapAcc, ln), sl) =>
                            (lines :+ ("    " + sl + Newline), mapAcc :+ (ln, 0), ln + 1)
                    }
                else
                    (Chunk.empty[String], List.empty[(Int, Int)], 3)

            // Emit body lines for each block, accumulating WrappedBlocks and content lines.
            val (bodyLines, wrappedBlocks, _) =
                blocks.toList.foldLeft((Chunk.empty[String], List.empty[WrappedBlock], lineAfterSetup)) {
                    case ((accLines, accWrapped, ln), block) =>
                        val blockBodyLines = block.body.split(Newline, -1).toList
                        val (newLines, blockMap, nextLn) =
                            blockBodyLines.zipWithIndex.foldLeft((Chunk.empty[String], preludeBuf, ln)) {
                                case ((bLines, bMap, bLn), (fl, idx)) =>
                                    (bLines :+ ("    " + fl + Newline), bMap :+ (bLn, idx + 1), bLn + 1)
                            }
                        val wb = WrappedBlock(block, blockPath, "", Chunk.from(blockMap), setupBlocks)
                        (accLines ++ newLines, accWrapped :+ wb, nextLn)
                }

            val allLines = headerLines ++ setupLines ++ bodyLines :+ ("}" + Newline)
            val content  = allLines.mkString

            // Update each WrappedBlock to carry the final syntheticContent.
            val finalWrapped = Chunk.from(wrappedBlocks.map(_.copy(syntheticContent = content)))
            val synthSrc     = Driver.Source(blockPath, content)
            Chunk(CompileUnit(synthSrc, finalWrapped))
        end if
    end groupEnvBlocks

    private def toCompileUnit(wb: WrappedBlock): CompileUnit =
        val src = Driver.Source(wb.synthFile, wb.syntheticContent)
        CompileUnit(src, Chunk(wb))
    end toCompileUnit

    /** Sanitises an env name to a valid Scala identifier segment.
      *
      * Replaces any non-alphanumeric character (including ".", "-", ":") with "_".
      */
    private[internal] def sanitiseEnvName(name: String): String =
        name.map(c => if c.isLetterOrDigit then c else '_')

end CompileUnit
