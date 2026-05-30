package kyo

import scala.concurrent.Future

/** Doc-consistency tests enforcing INV-019, INV-020, INV-026 (Phase 01 produced_invariants).
  *
  * These tests verify that the documentation rewrite in Phase 01 correctly renamed kyo-reflect to kyo-tasty, split the Goals /
  * Performance-targets sections, removed delivery-phase metadata from the 3 identified production source sites, and produced at least one
  * valid doctest-ready scala block in README.md.
  */
class TastyTest extends Test:

    "README rename consistency" in {
        // INV-020, INV-026: kyo-reflect / Reflect. / ReflectError / .kyo-reflect-cache must be gone from both docs,
        // except the one explicit historical-reference in DESIGN.md section 22 decision #7 (line ~1477).
        val readme = TestResourceLoader.readText("README.md")
        val design = TestResourceLoader.readText("DESIGN.md")

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
        val design = TestResourceLoader.readText("DESIGN.md")
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
        val tastyLines = TestResourceLoader.readText("kyo/Tasty.scala").split("\n")

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
        val clsLines = TestResourceLoader.readText("kyo/internal/tasty/classfile/ClassfileUnpickler.scala").split("\n")
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
        val readme = TestResourceLoader.readText("README.md")

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

    // ── Phase 02a source-text invariant tests (INV-001) ─────────────────────

    "every Symbol accessor signature carries (using AllowUnsafe)" in {
        // INV-001: Name.asString + 9 Symbol accessors all use (using AllowUnsafe) not import danger in body.
        // Given: source Tasty.scala read as String.
        // When: regex search counts occurrences of def (accessor)(using AllowUnsafe).
        // Then: count equals 10.
        val src = TestResourceLoader.readText("kyo/Tasty.scala")
        val pattern =
            """def (fullName|isPackageObject|scaladoc|position|declaredType|parents|typeParams|declarations|companion|asString)\(using AllowUnsafe\)""".r
        val count = pattern.findAllIn(src).length
        assert(count == 10, s"Expected 10 accessor signatures with (using AllowUnsafe), found $count")
        Future.successful(succeed)
    }

    "no import AllowUnsafe.embrace.danger in any migrated Symbol accessor body" in {
        // INV-001: after Phase 02a, no import danger inside the 10 migrated accessor bodies.
        // Given: source Tasty.scala read as String.
        // When: substring scan for import AllowUnsafe.embrace.danger on lines within the 10 accessor bodies.
        // Then: count is 0.
        // Note: import danger may remain in Symbol.body and computeFullName/computeBinaryName/show (§839 case 3 non-accessor sites).
        val lines = TestResourceLoader.readText("kyo/Tasty.scala").split("\n")
        // The 10 accessor defs (after Phase 02a) are single-line or short-body forms.
        // The key invariant: no line in the range covering accessor bodies has BOTH a (using AllowUnsafe) accessor def
        // AND an import danger on the SAME body line. We verify that none of the 10 accessor signatures are
        // immediately followed by `import AllowUnsafe.embrace.danger` within 3 lines.
        val accessorPattern =
            """def (fullName|isPackageObject|scaladoc|position|declaredType|parents|typeParams|declarations|companion)\(using AllowUnsafe\)""".r
        var violations = List.empty[String]
        for (line, idx) <- lines.zipWithIndex do
            if accessorPattern.findFirstIn(line).isDefined then
                val window = lines.slice(idx + 1, idx + 4).mkString(" ")
                if window.contains("import AllowUnsafe.embrace.danger") then
                    violations = violations :+ s"line ${idx + 1}: accessor followed by import danger within 3 lines"
        end for
        assert(violations.isEmpty, s"INV-001 violated: ${violations.mkString("; ")}")
        Future.successful(succeed)
    }

    // ── Phase 02d source-text invariant tests (INV-002 / A4 Symbol.body bridge) ──

    "Symbol.body TastyOrigin branch has no import AllowUnsafe.embrace.danger" in {
        // INV-002 / A4: Symbol.body uses Sync.Unsafe.defer as the AllowUnsafe bridge; the freestanding
        // import danger must be absent from the body method body.
        // Scope: lines between 'def body(using Frame)' and the next top-level 'def ' or 'end body',
        // so the _bodyOnce init lambda (which retains its own import at line ~553) is excluded.
        // Given: source Tasty.scala read as String, lines split.
        // When: extract lines in Symbol.body (from 'def body(using Frame)' through 'end body').
        // Then: count of 'import AllowUnsafe.embrace.danger' in those lines is 0.
        val lines    = TestResourceLoader.readText("kyo/Tasty.scala").split("\n")
        val startIdx = lines.indexWhere(_.contains("def body(using Frame): Tree < (Sync & Abort[TastyError])"))
        assert(startIdx >= 0, "Could not find 'def body(using Frame)' in Tasty.scala")
        val endIdx = lines.indexWhere(l => l.trim == "end body", startIdx + 1)
        assert(endIdx > startIdx, "Could not find 'end body' after def body in Tasty.scala")
        val bodyLines   = lines.slice(startIdx, endIdx + 1)
        val dangerCount = bodyLines.count(_.contains("import AllowUnsafe.embrace.danger"))
        assert(
            dangerCount == 0,
            s"A4 violated: found $dangerCount 'import AllowUnsafe.embrace.danger' in Symbol.body (lines ${startIdx + 1}-${endIdx + 1})"
        )
        Future.successful(succeed)
    }

    "Symbol.body TastyOrigin branch uses Sync.Unsafe.defer" in {
        // A4: Symbol.body bridges the unsafe OnceCell/ClasspathRef reads through Sync.Unsafe.defer.
        // Given: source Tasty.scala read as String, lines split.
        // When: extract lines in Symbol.body (from 'def body(using Frame)' through 'end body').
        // Then: count of 'Sync.Unsafe.defer' is at least 1.
        val lines    = TestResourceLoader.readText("kyo/Tasty.scala").split("\n")
        val startIdx = lines.indexWhere(_.contains("def body(using Frame): Tree < (Sync & Abort[TastyError])"))
        assert(startIdx >= 0, "Could not find 'def body(using Frame)' in Tasty.scala")
        val endIdx = lines.indexWhere(l => l.trim == "end body", startIdx + 1)
        assert(endIdx > startIdx, "Could not find 'end body' after def body in Tasty.scala")
        val bodyLines  = lines.slice(startIdx, endIdx + 1)
        val deferCount = bodyLines.count(_.contains("Sync.Unsafe.defer"))
        assert(
            deferCount >= 1,
            s"A4 violated: found $deferCount 'Sync.Unsafe.defer' in Symbol.body; expected at least 1"
        )
        Future.successful(succeed)
    }

    // ── Phase 02f source-text invariant test (INV-025) ────────────────────────

    "Classpath.open one-arg delegates to open(roots, strict = false)" in {
        // INV-025: The no-strict Classpath.open(roots) overload delegates by name to the canonical
        // Classpath.open(roots, strict) with `strict = false` explicit; no default-parameter shim.
        // Given: source Tasty.scala read as String, lines split.
        // When: locate the one-arg open overload by its signature and read its body line.
        // Then: the body line contains the literal substring `open(roots, strict = false)`.
        val lines = TestResourceLoader.readText("kyo/Tasty.scala").split("\n")
        // Find the one-arg overload: `def open(roots: Seq[String])(using Frame)` without a second param.
        val sigPattern = """^\s+def open\(roots: Seq\[String\]\)\(using Frame\)""".r
        val sigIdx     = lines.indexWhere(l => sigPattern.findFirstIn(l).isDefined)
        assert(sigIdx >= 0, "INV-025: could not locate one-arg open signature in Tasty.scala")
        // The body is on the next non-blank line after the signature.
        val bodyLine = lines.slice(sigIdx + 1, sigIdx + 3).find(_.trim.nonEmpty).getOrElse("")
        assert(
            bodyLine.contains("open(roots, strict = false)"),
            s"INV-025 violated: one-arg open body is '${bodyLine.trim}'; expected 'open(roots, strict = false)'"
        )
        assert(
            !bodyLine.contains("openImpl"),
            s"INV-025 violated: one-arg open body still calls openImpl; got '${bodyLine.trim}'"
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
