package kyo

import java.nio.file.Files
import java.nio.file.Paths
import scala.concurrent.Future

/** Doc-consistency tests enforcing INV-019, INV-020, INV-026 (Phase 01 produced_invariants).
  *
  * These tests verify that the documentation rewrite in Phase 01 correctly renamed kyo-reflect to kyo-tasty, split the Goals /
  * Performance-targets sections, removed delivery-phase metadata from the 3 identified production source sites, and produced at least one
  * valid doctest-ready scala block in README.md.
  */
class TastyTest extends Test:

    /** Resolve a path relative to the build root.
      *
      * sbt cross-project sets user.dir to the platform sub-directory (e.g. kyo-tasty/jvm). We walk two levels up from that to reach the
      * repository root, then append the given relative path.
      */
    private def buildPath(relative: String): java.nio.file.Path =
        Paths.get(java.lang.System.getProperty("user.dir")).getParent.getParent.resolve(relative)

    "README rename consistency" in {
        // INV-020, INV-026: kyo-reflect / Reflect. / ReflectError / .kyo-reflect-cache must be gone from both docs,
        // except the one explicit historical-reference in DESIGN.md section 22 decision #7 (line ~1477).
        val readme = Files.readString(buildPath("kyo-tasty/README.md"))
        val design = Files.readString(buildPath("kyo-tasty/DESIGN.md"))

        // README: zero occurrences of any old-module pattern
        val readmeKyoReflect   = countOccurrences(readme, "kyo-reflect")
        val readmeReflectDot   = countOccurrences(readme, "Reflect.")
        val readmeReflectError = countOccurrences(readme, "ReflectError")
        val readmeCacheDir     = countOccurrences(readme, ".kyo-reflect-cache")

        assert(readmeKyoReflect == 0, s"README.md: found $readmeKyoReflect occurrences of 'kyo-reflect'")
        assert(readmeReflectDot == 0, s"README.md: found $readmeReflectDot occurrences of 'Reflect.'")
        assert(readmeReflectError == 0, s"README.md: found $readmeReflectError occurrences of 'ReflectError'")
        assert(readmeCacheDir == 0, s"README.md: found $readmeCacheDir occurrences of '.kyo-reflect-cache'")

        // DESIGN.md: exactly 1 occurrence of 'kyo-reflect', in the locked-decision section (historical reference)
        val designKyoReflect = countOccurrences(design, "kyo-reflect")
        assert(designKyoReflect == 1, s"DESIGN.md: expected exactly 1 historical reference to 'kyo-reflect', found $designKyoReflect")

        // DESIGN.md: zero Reflect. / ReflectError / .kyo-reflect-cache
        val designReflectDot   = countOccurrences(design, "Reflect.")
        val designReflectError = countOccurrences(design, "ReflectError")
        val designCacheDir     = countOccurrences(design, ".kyo-reflect-cache")
        assert(designReflectDot == 0, s"DESIGN.md: found $designReflectDot occurrences of 'Reflect.'")
        assert(designReflectError == 0, s"DESIGN.md: found $designReflectError occurrences of 'ReflectError'")
        assert(designCacheDir == 0, s"DESIGN.md: found $designCacheDir occurrences of '.kyo-reflect-cache'")

        // README: at least one 'kyo-tasty' reference (module was renamed)
        val readmeKyoTasty = countOccurrences(readme, "kyo-tasty")
        assert(readmeKyoTasty >= 1, s"README.md: expected >= 1 occurrence of 'kyo-tasty', found $readmeKyoTasty")

        Future.successful(succeed)
    }

    "DESIGN section split" in {
        // L7: Section 1 must be split into Goals (concise) and Performance targets (separate heading).
        // The plan after-block says '## 1. Goals' and '## 1a. Performance targets' each appear once.
        val design = Files.readString(buildPath("kyo-tasty/DESIGN.md"))
        val headerLines = design.split("\n").zipWithIndex
            .filter { case (line, _) => line.startsWith("## ") }
            .take(100) // scan only leading headers to bound the check

        val goalsHeaders = headerLines.filter { case (line, _) => line == "## 1. Goals" }
        val perfHeaders  = headerLines.filter { case (line, _) => line == "## 1a. Performance targets" }

        assert(goalsHeaders.length == 1, s"DESIGN.md: expected exactly 1 '## 1. Goals' header, found ${goalsHeaders.length}")
        assert(perfHeaders.length == 1, s"DESIGN.md: expected exactly 1 '## 1a. Performance targets' header, found ${perfHeaders.length}")

        // Goals section is concise: the block between '## 1. Goals' and '## 1a. Performance targets' must be < 20 lines
        val allLines      = design.split("\n")
        val goalsStartIdx = allLines.indexWhere(_ == "## 1. Goals")
        val perfStartIdx  = allLines.indexWhere(_ == "## 1a. Performance targets")
        assert(goalsStartIdx >= 0, "DESIGN.md: '## 1. Goals' not found")
        assert(perfStartIdx > goalsStartIdx, "DESIGN.md: '## 1a. Performance targets' must appear after '## 1. Goals'")
        val goalsSectionLines = perfStartIdx - goalsStartIdx - 1
        assert(goalsSectionLines < 20, s"DESIGN.md: Goals section is $goalsSectionLines lines; must be < 20")

        Future.successful(succeed)
    }

    "No phase-metadata comments in INV-021 production source sites" in {
        // flow-allow: this test's rationale comment cites the historical 'Phase N' tokens it tests for; not a DEV tag
        // INV-021: Phase 01 removed delivery-tracking '// Phase N' comments from exactly 3 identified sites:
        //   1. kyo-tasty/shared/src/main/scala/kyo/Tasty.scala, lines 73-88 (around old line 78)
        //   2. kyo-tasty/shared/src/main/scala/kyo/Tasty.scala, lines 1006-1022 (around old line 1012)
        //   3. kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala, lines 14-29 (around old line 19)
        //
        // Scope is NARROWLY limited to these 3 windows. AstUnpickler.scala and ClasspathOrchestrator.scala are
        // intentionally excluded: their '// Phase A/B/C' and '// Phase 1/2' tokens are algorithmic pipeline stage
        // names per the classpath orchestrator architecture, not delivery metadata. They are governed by Phase 19 (M10).
        import scala.util.matching.Regex
        val phaseMetaPattern: Regex = """// Phase [0-9A-Z]""".r

        // Site 1 and 2: Tasty.scala windows
        val tastyPath  = buildPath("kyo-tasty/shared/src/main/scala/kyo/Tasty.scala")
        val tastyLines = Files.readString(tastyPath).split("\n")

        // Window around former line 78 (0-indexed 77): lines 72..87
        val window1 = tastyLines.slice(72, 88)
        val w1Hits  = window1.zipWithIndex.filter { case (line, _) => phaseMetaPattern.findFirstIn(line).isDefined }
        assert(
            w1Hits.isEmpty,
            s"Tasty.scala lines 73-88: found phase-metadata comment(s): ${w1Hits.map { case (l, i) => s"line ${73 + i}: $l" }.mkString(", ")}"
        )

        // Window around former line 1012 (0-indexed 1011): lines 1005..1021
        val window2 = tastyLines.slice(1005, 1022)
        val w2Hits  = window2.zipWithIndex.filter { case (line, _) => phaseMetaPattern.findFirstIn(line).isDefined }
        assert(w2Hits.isEmpty, s"Tasty.scala lines 1006-1022: found phase-metadata comment(s): ${w2Hits.map { case (l, i) => s"line ${1006 + i}: $l" }.mkString(", ")}")

        // Site 3: ClassfileUnpickler.scala window around line 19 (0-indexed 18): lines 13..28
        val clsPath  = buildPath("kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ClassfileUnpickler.scala")
        val clsLines = Files.readString(clsPath).split("\n")
        val window3  = clsLines.slice(13, 29)
        val w3Hits   = window3.zipWithIndex.filter { case (line, _) => phaseMetaPattern.findFirstIn(line).isDefined }
        assert(
            w3Hits.isEmpty,
            s"ClassfileUnpickler.scala lines 14-29: found phase-metadata comment(s): ${w3Hits.map { case (l, i) =>
                    s"line ${14 + i}: $l"
                }.mkString(", ")}"
        )

        Future.successful(succeed)
    }

    "README doctest extraction" in {
        // INV-020, L6: README must contain at least one fenced scala block with 'import kyo.Tasty.*'
        // and zero fenced blocks containing 'Reflect.' or 'ReflectError'.
        // Doctest compilation is verified by sbt's doctest infrastructure; this test verifies the source structure.
        val readme = Files.readString(buildPath("kyo-tasty/README.md"))

        // Extract all fenced scala block bodies
        val scalaBlockPattern = """(?s)```scala\n(.*?)```""".r
        val blocks            = scalaBlockPattern.findAllMatchIn(readme).map(_.group(1)).toSeq

        assert(blocks.nonEmpty, "README.md: no fenced ```scala blocks found")

        val blocksWithTastyImport = blocks.filter(_.contains("import kyo.Tasty.*"))
        assert(
            blocksWithTastyImport.nonEmpty,
            s"README.md: expected at least one fenced scala block containing 'import kyo.Tasty.*', found ${blocks.length} blocks but none match"
        )

        val blocksWithReflect = blocks.filter(b => b.contains("Reflect.") || b.contains("ReflectError"))
        assert(
            blocksWithReflect.isEmpty,
            s"README.md: found ${blocksWithReflect.length} fenced scala block(s) containing 'Reflect.' or 'ReflectError'"
        )

        Future.successful(succeed)
    }

    /** Count non-overlapping occurrences of `needle` in `haystack`. */
    private def countOccurrences(haystack: String, needle: String): Int =
        var count = 0
        var idx   = 0
        while
            val found = haystack.indexOf(needle, idx)
            if found >= 0 then
                count += 1
                idx = found + needle.length
                true
            else
                false
            end if
        do ()
        end while
        count
    end countOccurrences

end TastyTest
