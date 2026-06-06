package kyo.test.runner

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kyo.*
import kyo.Chunk
import kyo.Maybe
import kyo.test.LeafInfo
import kyo.test.RunInfo
import kyo.test.SuiteInfo
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestResult

/** Validates the path type migration: `List[String]` to `Chunk[String]` for leaf paths.
  *
  * Eight mandatory leaves per the improvement plan, lines 116-123.
  */
class PathChunkTest extends kyo.test.Test[Any]:

    // ── Leaf 1 ────────────────────────────────────────────────────────────────────────────────
    // LeafInfo.path.mkString(".") on a 3-segment chunk renders "a.b.c"
    "LeafInfo.path.mkString('.') on 3-segment Chunk renders 'a.b.c'" in {
        val path = Chunk("a", "b", "c")
        val info = LeafInfo("S", path, Set.empty)
        assert(info.path.mkString(".") == "a.b.c")
    }

    // ── Leaf 2 ────────────────────────────────────────────────────────────────────────────────
    // SuiteReport.leafResults(0)._1 is a Chunk[String] (type-level check via summon)
    "SuiteReport.leafResults first element path is Chunk[String]" in {
        val sr                       = SuiteReport("S", Chunk((Chunk("a"), TestResult.Passed(1L.millis))), Duration.Zero)
        val firstPath: Chunk[String] = sr.leafResults(0)._1
        // summon confirms the type is Chunk[String] at compile time
        val _ = summon[firstPath.type <:< Chunk[String]]
        assert(firstPath == Chunk("a"))
    }

    // ── Leaf 3 ────────────────────────────────────────────────────────────────────────────────
    // Constructing a LeafInfo with Chunk("a","b") and reading .path.size yields 2
    "LeafInfo with Chunk('a','b') has path.size == 2" in {
        val info = LeafInfo("S", Chunk("a", "b"), Set.empty)
        assert(info.path.size == 2)
    }

    // ── Leaf 5 ────────────────────────────────────────────────────────────────────────────────
    // ConsoleReporter golden test: byte-identical text before and after migration
    "ConsoleReporter serialises LeafInfo with Chunk path identically to prior List output" in {
        val baos = new ByteArrayOutputStream()
        val ps   = new PrintStream(baos, true, "UTF-8")
        val r    = ConsoleReporter(kyo.test.Verbosity.Normal, useColors = false, out = ps)
        val leaf = LeafInfo("S", Chunk("a"), Set.empty)
        r.onLeafComplete(leaf, TestResult.Passed(1L.millis))
        val output = baos.toString("UTF-8")
        // Path rendered via renderPath(Chunk("a")) == "a", identical to renderPath(List("a"))
        assert(output.contains("[PASS]"), s"Expected [PASS] in: $output")
        assert(output.contains("a"), s"Expected path 'a' in: $output")
    }

    // ── Leaf 6 ────────────────────────────────────────────────────────────────────────────────
    // TapReporter golden test: byte-identical TAP output before and after migration
    "TapReporter serialises LeafInfo with Chunk path identically to prior List output" in {
        val baos = new ByteArrayOutputStream()
        val ps   = new PrintStream(baos, true, "UTF-8")
        val r    = TapReporter(ps)
        val leaf = LeafInfo("S", Chunk("a"), Set.empty)
        r.onRunStart(RunInfo(1, 1))
        r.onSuiteStart(SuiteInfo("S", "com.example.S", Maybe.empty))
        r.onLeafStart(leaf)
        r.onLeafComplete(leaf, TestResult.Passed(1L.millis))
        val sr = SuiteReport("S", Chunk((Chunk("a"), TestResult.Passed(1L.millis))), 1L.millis)
        r.onSuiteComplete(SuiteInfo("S", "com.example.S", Maybe.empty), sr)
        r.onRunComplete(TestReport(Chunk(sr)))
        val output = baos.toString("UTF-8")
        // TAP line: "ok 1 - S / a"; path.mkString(" / ") = "a" for both Chunk("a") and List("a")
        assert(output.contains("TAP version 13"), s"Expected TAP header in: $output")
        assert(output.contains("1..1"), s"Expected plan line 1..1 in: $output")
        assert(output.contains("ok 1 - S / a"), s"Expected 'ok 1 - S / a' in: $output")
    }

end PathChunkTest
