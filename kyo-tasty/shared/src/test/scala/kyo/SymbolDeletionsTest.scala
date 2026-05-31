package kyo

import scala.concurrent.Future

/** Structural deletion assertions for the api-cleanup campaign terminal invariant.
  *
  * Four grep-based leaves assert that the campaign's promised deletions are complete and permanent:
  *   1. SingleAssign / ClasspathRef / UnresolvedRef / Classpath.State are absent from production source.
  *   2. OnceCell is used only inside Interner.scala (the Entry field) -- not as a type in any other production file.
  *   3. No `// flow-allow:` markers remain in shared source.
  *   4. No `// DEV:` markers remain in shared source.
  *
  * Pins: INV-012 (terminal deletion invariant), Decision 33 (flow-allow strip), Steering strip-dev rule.
  */
class SymbolDeletionsTest extends Test:

    // Source files that were the primary targets of the campaign. Any file that could plausibly contain the
    // deleted types is listed here. The grep is conservative: it scans source text for identifier patterns.

    private val primarySourceFiles: Seq[String] = Seq(
        "kyo/Tasty.scala",
        "kyo/internal/tasty/query/ClasspathOrchestrator.scala",
        "kyo/internal/tasty/symbol/Symbol.scala",
        "kyo/internal/tasty/symbol/Interner.scala",
        "kyo/internal/tasty/symbol/OnceCell.scala",
        "kyo/internal/tasty/reader/AstUnpickler.scala",
        "kyo/internal/tasty/reader/TreeUnpickler.scala",
        "kyo/internal/tasty/reader/TypeUnpickler.scala",
        "kyo/internal/tasty/snapshot/SnapshotReader.scala",
        "kyo/internal/tasty/snapshot/SnapshotWriter.scala",
        "kyo/internal/tasty/classfile/ClassfileUnpickler.scala",
        "kyo/internal/tasty/classfile/JavaAnnotationUnpickler.scala",
        "kyo/internal/tasty/scala2/Scala2PickleReader.scala"
    )

    private val sharedMainSourceFiles: Seq[String] =
        primarySourceFiles ++ Seq(
            "kyo/TastyAnnotation.scala",
            "kyo/TastyClasspath.scala",
            "kyo/TastyErrorMode.scala",
            "kyo/TastyFlags.scala",
            "kyo/TastyJava.scala",
            "kyo/TastyMisc.scala",
            "kyo/TastyModules.scala",
            "kyo/TastyName.scala",
            "kyo/TastySymbol.scala",
            "kyo/TastySymbolId.scala",
            "kyo/TastySymbolKind.scala",
            "kyo/TastyTree.scala",
            "kyo/TastyType.scala",
            "kyo/internal/tasty/type_/Subtyping.scala",
            "kyo/internal/tasty/classfile/ModuleInfoReader.scala"
        )

    // ── Leaf 1: no SingleAssign / ClasspathRef / UnresolvedRef / Classpath.State ──

    // Given: production source files that were touched by the campaign.
    // When: each file is scanned for the deleted type identifiers.
    // Then: no match found -- all four types were deleted in Phase 07.
    // Pins: INV-012.
    "Leaf 1: no SingleAssign / ClasspathRef / UnresolvedRef / Classpath.State in production source" in {
        val deletedPatterns = Seq("SingleAssign", "ClasspathRef", "UnresolvedRef", "Classpath.State")

        val violations =
            for
                filePath <- sharedMainSourceFiles
                src =
                    try TestResourceLoader.readText(filePath)
                    catch case _: Exception => ""
                line    <- src.split("\n").toSeq
                pattern <- deletedPatterns
                // Skip comment-only occurrences of historical mentions (e.g., audit notes in scaladoc).
                // A match is only a violation if the pattern appears as a non-comment token.
                if !line.trim.startsWith("//") && !line.trim.startsWith("*") && !line.trim.startsWith("/*")
                if line.contains(pattern)
            yield s"$filePath: $pattern in: ${line.trim}"

        assert(
            violations.isEmpty,
            s"INV-012 violated: deleted types found in production source:\n${violations.mkString("\n")}"
        )

        Future.successful(succeed)
    }

    // ── Leaf 2: OnceCell used only inside Interner.scala ──

    // Given: production source files in shared/src/main.
    // When: each file is scanned for OnceCell used as a type or value (not as a prose comment mention).
    // Then: only Interner.scala and OnceCell.scala itself contain actual OnceCell type/value usages.
    //       Other files may mention "OnceCell" in prose comments; those are filtered out.
    // Pins: INV-012.
    "Leaf 2: OnceCell used as a type or value only in OnceCell.scala and Interner.scala" in {
        val allowedFiles = Set(
            "kyo/internal/tasty/symbol/OnceCell.scala",
            "kyo/internal/tasty/symbol/Interner.scala"
        )

        val violations =
            for
                filePath <- sharedMainSourceFiles
                if !allowedFiles.contains(filePath)
                src =
                    try TestResourceLoader.readText(filePath)
                    catch case _: Exception => ""
                line <- src.split("\n").toSeq
                trimmed = line.trim
                // Only flag lines where OnceCell appears as a type annotation or constructor call,
                // not in comment prose (lines starting with //, *, or /**).
                if !trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*")
                if trimmed.contains(": OnceCell") || trimmed.contains("OnceCell.init") || trimmed.contains("OnceCell[") || trimmed.contains(
                    "new OnceCell"
                )
            yield s"$filePath: OnceCell type/value usage: ${trimmed}"

        assert(
            violations.isEmpty,
            s"INV-012 violated: OnceCell used outside OnceCell.scala/Interner.scala:\n${violations.mkString("\n")}"
        )

        Future.successful(succeed)
    }

    // ── Leaf 3: no flow-allow marker remains ──

    // Given: shared production source files.
    // When: each file is scanned for '// flow-allow:'.
    // Then: no match -- all markers were stripped in Phase 12 (Decision 33).
    // Pins: Decision 33, Steering strip-dev rule.
    "Leaf 3: no flow-allow marker remains in shared production source" in {
        val violations =
            for
                filePath <- sharedMainSourceFiles
                src =
                    try TestResourceLoader.readText(filePath)
                    catch case _: Exception => ""
                line <- src.split("\n").toSeq
                if line.contains("// flow-allow:")
            yield s"$filePath: ${line.trim}"

        assert(
            violations.isEmpty,
            s"Decision 33 violated: flow-allow markers remain:\n${violations.mkString("\n")}"
        )

        Future.successful(succeed)
    }

    // ── Leaf 4: no DEV-tagged line remains ──

    // Given: shared production source files.
    // When: each file is scanned for '// DEV:'.
    // Then: no match -- all DEV tags were stripped per Steering strip-dev rule.
    // Pins: Steering strip-dev rule.
    "Leaf 4: no DEV-tagged line remains in shared production source" in {
        val violations =
            for
                filePath <- sharedMainSourceFiles
                src =
                    try TestResourceLoader.readText(filePath)
                    catch case _: Exception => ""
                line <- src.split("\n").toSeq
                if line.contains("// DEV:")
            yield s"$filePath: ${line.trim}"

        assert(
            violations.isEmpty,
            s"Steering strip-dev rule violated: DEV markers remain:\n${violations.mkString("\n")}"
        )

        Future.successful(succeed)
    }

end SymbolDeletionsTest
