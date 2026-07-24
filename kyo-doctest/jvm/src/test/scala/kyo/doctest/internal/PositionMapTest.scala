package kyo.doctest.internal

import kyo.*

/** Tests for PositionMap covering synthetic-line to README-line translation. */
class PositionMapTest extends kyo.doctest.DoctestTest:

    // Helper: builds a standard Block with the given parameters.
    private def makeBlock(
        file: String = "kyo-data/README.md",
        lineStart: Int = 40,
        lineEnd: Int = 45,
        body: String = "val x = 1\nval y = 2\nval z = 3",
        carrier: Block.Carrier = Block.Carrier.Visible
    ): Block =
        Block(
            file = kyo.Path(file),
            lineStart = lineStart,
            lineEnd = lineEnd,
            body = body,
            visibility = Block.Visibility.Isolated,
            expect = Block.Expectation.Compiles,
            platform = Set(Block.Target.JVM),
            carrier = carrier
        )

    // Helper: builds a simple WrappedBlock with a known lineMap.
    private def makeWrapped(
        block: Block,
        synthFileName: String = "Block_3a7f9c1b_40.scala",
        lineMap: Chunk[(Int, Int)] = Chunk((3, 1), (4, 2), (5, 3)),
        setupBlocks: Chunk[Block] = Chunk.empty
    ): WrappedBlock =
        WrappedBlock(
            block = block,
            synthFile = kyo.Path(synthFileName),
            syntheticContent = "",
            lineMap = lineMap,
            setupBlocks = setupBlocks
        )

    "translate returns correct (block, blockBodyLine) for a mapped synth line" in {
        val block = makeBlock(lineStart = 40)
        val wb    = makeWrapped(block, lineMap = Chunk((3, 1), (4, 2), (5, 3)))
        val pm    = PositionMap.init(Chunk(wb))

        val result = pm.translate(kyo.Path("Block_3a7f9c1b_40.scala"), 3)
        assert(result == Present((block, 1)), s"expected Present((block, 1)), got $result")

        // Verify readmeLine computation: lineStart=40, blockBodyLine=1 -> readmeLine=41.
        val diag = Driver.Diagnostic(
            severity = Driver.Diagnostic.Severity.Error,
            file = kyo.Path("Block_3a7f9c1b_40.scala"),
            line = 3,
            col = 5,
            message = "type mismatch",
            related = Chunk.empty
        )
        val mapped = pm.translateDiagnostic(diag)
        assert(mapped.isDefined, "translateDiagnostic should return Present")
        val md = mapped.get
        assert(md.readmeLine == 41, s"expected readmeLine=41, got ${md.readmeLine}")
        assert(md.col == 5, s"expected col=5, got ${md.col}")
    }

    "translate for block starting at lineStart=1 returns readmeLine=2 for blockBodyLine=1" in {
        val block = makeBlock(lineStart = 1, lineEnd = 5, body = "val a = 0")
        val wb    = makeWrapped(block, synthFileName = "Block_abc12345_1.scala", lineMap = Chunk((3, 1)))
        val pm    = PositionMap.init(Chunk(wb))

        val diag = Driver.Diagnostic(
            severity = Driver.Diagnostic.Severity.Error,
            file = kyo.Path("Block_abc12345_1.scala"),
            line = 3,
            col = 1,
            message = "error at line 1",
            related = Chunk.empty
        )
        val mapped = pm.translateDiagnostic(diag)
        assert(mapped.isDefined, "should translate for lineStart=1")
        assert(mapped.get.readmeLine == 2, s"expected readmeLine=2, got ${mapped.get.readmeLine}")
    }

    "translate is carrier-agnostic for Visible carrier" in {
        val block = makeBlock(lineStart = 40, carrier = Block.Carrier.Visible)
        val wb    = makeWrapped(block, lineMap = Chunk((3, 1)))
        val pm    = PositionMap.init(Chunk(wb))

        val result = pm.translate(kyo.Path("Block_3a7f9c1b_40.scala"), 3)
        assert(result == Present((block, 1)), s"Visible carrier should not affect translate result")
    }

    "translate is carrier-agnostic for Hidden carrier" in {
        val block = makeBlock(lineStart = 40, carrier = Block.Carrier.Hidden)
        val wb    = makeWrapped(block, lineMap = Chunk((3, 1)))
        val pm    = PositionMap.init(Chunk(wb))

        val result = pm.translate(kyo.Path("Block_3a7f9c1b_40.scala"), 3)
        assert(result == Present((block, 1)), s"Hidden carrier should not affect translate result")
    }

    "translate for multi-line block maps each synth line independently" in {
        val block = makeBlock(lineStart = 50, lineEnd = 55, body = "val a = 1\nval b = 2\nval c = a + b")
        val wb    = makeWrapped(block, synthFileName = "Block_deadbeef_50.scala", lineMap = Chunk((3, 1), (4, 2), (5, 3)))
        val pm    = PositionMap.init(Chunk(wb))

        // Line 3 (blockBodyLine=1): readmeLine = 50 + 1 = 51.
        val d1 = Driver.Diagnostic(Driver.Diagnostic.Severity.Error, kyo.Path("Block_deadbeef_50.scala"), 3, 1, "err1", Chunk.empty)
        val m1 = pm.translateDiagnostic(d1)
        assert(m1.isDefined && m1.get.readmeLine == 51, s"line3: expected readmeLine=51, got ${m1.map(_.readmeLine)}")

        // Line 5 (blockBodyLine=3): readmeLine = 50 + 3 = 53.
        val d2 = Driver.Diagnostic(Driver.Diagnostic.Severity.Error, kyo.Path("Block_deadbeef_50.scala"), 5, 3, "err2", Chunk.empty)
        val m2 = pm.translateDiagnostic(d2)
        assert(m2.isDefined && m2.get.readmeLine == 53, s"line5: expected readmeLine=53, got ${m2.map(_.readmeLine)}")
    }

    "warning diagnostic translates the same way as an error" in {
        val block = makeBlock(lineStart = 100, lineEnd = 104, body = "import java.util.Date")
        val wb    = makeWrapped(block, synthFileName = "Block_cafebabe_100.scala", lineMap = Chunk((3, 1)))
        val pm    = PositionMap.init(Chunk(wb))

        val diag = Driver.Diagnostic(
            severity = Driver.Diagnostic.Severity.Warning,
            file = kyo.Path("Block_cafebabe_100.scala"),
            line = 3,
            col = 8,
            message = "unused import",
            related = Chunk.empty
        )
        val mapped = pm.translateDiagnostic(diag)
        assert(mapped.isDefined, "warning diagnostic should be translatable")
        val md = mapped.get
        assert(md.severity == Driver.Diagnostic.Severity.Warning, "severity should be Warning")
        assert(md.readmeLine == 101, s"expected readmeLine=101, got ${md.readmeLine}")
        assert(md.col == 8, s"expected col=8, got ${md.col}")
    }

    "translateDiagnostic returns Absent for a line not in any lineMap entry" in {
        val block = makeBlock(lineStart = 40)
        val wb    = makeWrapped(block, lineMap = Chunk((3, 1), (4, 2), (5, 3)))
        val pm    = PositionMap.init(Chunk(wb))

        // Lines 1 and 2 are package/object boilerplate; line 99 is not in the map.
        val diagOnBoilerplate =
            Driver.Diagnostic(Driver.Diagnostic.Severity.Error, kyo.Path("Block_3a7f9c1b_40.scala"), 1, 1, "pkg err", Chunk.empty)
        val diagUnknown =
            Driver.Diagnostic(Driver.Diagnostic.Severity.Error, kyo.Path("Block_3a7f9c1b_40.scala"), 99, 1, "unknown", Chunk.empty)

        assert(pm.translateDiagnostic(diagOnBoilerplate) == Absent, "line 1 (package) should be Absent")
        assert(pm.translateDiagnostic(diagUnknown) == Absent, "line 99 (not in map) should be Absent")
    }

    "translate returns Absent for synthetic boilerplate lines (package, object header)" in {
        val block = makeBlock(lineStart = 40)
        // lineMap only covers lines 3, 4, 5 (body lines); lines 1 and 2 are boilerplate.
        val wb = makeWrapped(block, lineMap = Chunk((3, 1), (4, 2), (5, 3)))
        val pm = PositionMap.init(Chunk(wb))

        val synthFile = kyo.Path("Block_3a7f9c1b_40.scala")

        assert(pm.translate(synthFile, 1) == Absent, "line 1 (package declaration) should be Absent")
        assert(pm.translate(synthFile, 2) == Absent, "line 2 (object header) should be Absent")
        // Confirm that line 3 IS present (the body starts there).
        assert(pm.translate(synthFile, 3) == Present((block, 1)), "line 3 should be Present")
    }

end PositionMapTest
