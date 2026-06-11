package kyo.doctest.internal

import kyo.*

/** Tests for WrappedBlock covering synthetic source generation and position-map construction. */
class WrappedBlockTest extends kyo.test.Test[Any]:

    private def makeBlock(
        body: String,
        file: String = "test.md",
        lineStart: Int = 1,
        lineEnd: Int = 5,
        visibility: Block.Visibility = Block.Visibility.Isolated
    ): Block =
        Block(
            file = kyo.Path(file),
            lineStart = lineStart,
            lineEnd = lineEnd,
            body = body,
            visibility = visibility,
            expect = Block.Expectation.Compiles,
            platform = Set(Block.Target.JVM, Block.Target.JS, Block.Target.Native),
            carrier = Block.Carrier.Visible
        )

    "init produces an object BlockN containing the block body" in {
        val block   = makeBlock("val answer = 42")
        val wrapped = WrappedBlock.init(block)

        // The syntheticContent must contain "object Block_" followed by the object name.
        assert(wrapped.syntheticContent.contains("object Block_"), s"expected 'object Block_' in:\n${wrapped.syntheticContent}")
        // The block body must appear inside the content.
        assert(wrapped.syntheticContent.contains("val answer = 42"), "block body missing from syntheticContent")
        // The package declaration must be present.
        assert(wrapped.syntheticContent.startsWith("package _doctest_synthetic_"), "missing package declaration")
    }

    "top-level vals in a block are placed inside the object" in {
        val block   = makeBlock("val x = 1\nval y = 2\nval z = x + y")
        val wrapped = WrappedBlock.init(block)

        val content = wrapped.syntheticContent
        // The object must contain all three vals.
        assert(content.contains("val x = 1"), "val x missing")
        assert(content.contains("val y = 2"), "val y missing")
        assert(content.contains("val z = x + y"), "val z missing")
        // The vals must appear after the object header line, not before it.
        val objHeaderIdx = content.indexOf("object Block_")
        val val1Idx      = content.indexOf("val x = 1")
        assert(val1Idx > objHeaderIdx, "val x appears before object header")
    }

    "imports in a block body are placed inside the object scope" in {
        val body    = "import java.util.UUID\nval id = UUID.randomUUID().toString"
        val block   = makeBlock(body)
        val wrapped = WrappedBlock.init(block)

        val content = wrapped.syntheticContent
        assert(content.contains("import java.util.UUID"), "import missing from syntheticContent")
        val objHeaderIdx = content.indexOf("object Block_")
        val importIdx    = content.indexOf("import java.util.UUID")
        assert(importIdx > objHeaderIdx, "import appears before object header")
    }

    "init records correct (synthLine, blockBodyLine) entries in lineMap" in {
        val body    = "val a = 1\nval b = 2\nval c = 3"
        val block   = makeBlock(body)
        val wrapped = WrappedBlock.init(block)

        val lineMap = wrapped.lineMap
        // 3 body lines -> 3 entries in lineMap.
        assert(lineMap.size == 3, s"expected 3 lineMap entries, got ${lineMap.size}")
        // blockBodyLine values should be 1, 2, 3.
        val blockBodyLines = lineMap.map(_._2).toSeq
        assert(blockBodyLines == Seq(1, 2, 3), s"expected blockBodyLine sequence 1,2,3 but got $blockBodyLines")
        // synthLine values should be strictly increasing.
        val synthLines = lineMap.map(_._1).toSeq
        val increasing = synthLines.zip(synthLines.tail).forall { case (a, b) => b > a }
        assert(increasing, s"synthLine sequence not strictly increasing: $synthLines")
    }

    // Additional: init with setup injects the setup prelude before the block body.
    "init with setup injects setup prelude with blockBodyLine=0 entries" in {
        val setupBody = "val setup = true"
        val blockBody = "val usage = setup"
        val block     = makeBlock(blockBody)
        val wrapped   = WrappedBlock.init(block, Chunk(setupBody))

        val content = wrapped.syntheticContent
        assert(content.contains("val setup = true"), "setup body missing from syntheticContent")
        assert(content.contains("val usage = setup"), "block body missing from syntheticContent")

        // Setup line should be mapped with blockBodyLine=0.
        val lineMap    = wrapped.lineMap
        val setupEntry = lineMap.filter(_._2 == 0)
        assert(setupEntry.nonEmpty, "expected at least one blockBodyLine=0 entry for setup prelude")
        // Block body line mapped with blockBodyLine=1.
        val bodyEntry = lineMap.filter(_._2 == 1)
        assert(bodyEntry.nonEmpty, "expected at least one blockBodyLine=1 entry for block body")
    }

    // Additional: synthFile name uses the object name format Block_<hash8>_<lineStart>.scala.
    "synthFile name follows Block_<hash8>_<lineStart>.scala format" in {
        val block   = makeBlock("val n = 0", file = "README.md", lineStart = 42)
        val wrapped = WrappedBlock.init(block)

        val name = wrapped.synthFile.toString
        assert(name.startsWith("Block_"), s"expected name to start with 'Block_', got $name")
        assert(name.endsWith("_42.scala"), s"expected name to end with '_42.scala', got $name")
        // The hash part should be 8 hex characters.
        val parts = name.stripPrefix("Block_").stripSuffix("_42.scala")
        assert(parts.length == 8, s"expected 8-char hash, got '$parts'")
        assert(parts.forall(c => "0123456789abcdef".contains(c)), s"hash '$parts' contains non-hex chars")
    }

end WrappedBlockTest
