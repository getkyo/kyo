package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator

/** Unit tests for ClasspathOrchestrator.globalizeUnresolvedNegIds.
  *
  * Each file decodes its unresolved cross-file references with negIds from a per-file counter that always starts at
  * -2, so distinct files reuse the same negId values for different fully-qualified names. globalizeUnresolvedNegIds
  * must give each distinct unresolved name a classpath-unique negId so the per-file values cannot collide in the
  * shared unresolvedFullNameByNegId map, and the assignment must be independent of file processing order. Cross
  * platform: pure map operations.
  */
class ClasspathOrchestratorNegIdTest extends kyo.test.Test[Any]:

    /** Resolve the fully-qualified name a file's raw decode-time negId points to, through the global remap. */
    private def nameOf(remap: ClasspathOrchestrator.UnresolvedNegIdRemap, fileIdx: Int, rawNegId: Int): String =
        val globalNeg = remap.perFileNegIdToGlobalNeg(fileIdx).getOrDefault(rawNegId, 0)
        remap.unresolvedFullNameByNegId.getOrElse(Tasty.SymbolId(globalNeg), "<unmapped>")

    private val nothingResolves: String => Int = _ => -1

    "colliding per-file negIds for different names get distinct global ids, each resolving to its own name" in {
        // Both files use raw negId -2 (every DecodeSession starts at -2) for DIFFERENT unresolved names.
        val fileA: Map[Int, String] = Map(-2 -> "scala.deprecated", -3 -> "scala.annotation.tailrec")
        val fileB: Map[Int, String] = Map(-2 -> "scala.annotation.unused")

        val remap = ClasspathOrchestrator.globalizeUnresolvedNegIds(Seq(fileA, fileB), nothingResolves)

        assert(nameOf(remap, 0, -2) == "scala.deprecated")
        assert(nameOf(remap, 0, -3) == "scala.annotation.tailrec")
        assert(nameOf(remap, 1, -2) == "scala.annotation.unused")
        val gA = remap.perFileNegIdToGlobalNeg(0).getOrDefault(-2, 0)
        val gB = remap.perFileNegIdToGlobalNeg(1).getOrDefault(-2, 0)
        assert(gA != gB, s"colliding per-file negIds must map to distinct global ids; both were $gA")
        assert(gA < -1 && gB < -1, s"global unresolved negIds must be < -1; got $gA and $gB")
        assert(remap.perFileNegIdToFinal(0).isEmpty && remap.perFileNegIdToFinal(1).isEmpty)
    }

    "global negId assignment is independent of file processing order" in {
        val fileA: Map[Int, String] = Map(-2 -> "scala.deprecated", -3 -> "scala.annotation.tailrec")
        val fileB: Map[Int, String] = Map(-2 -> "scala.annotation.unused")

        val forward  = ClasspathOrchestrator.globalizeUnresolvedNegIds(Seq(fileA, fileB), nothingResolves)
        val reversed = ClasspathOrchestrator.globalizeUnresolvedNegIds(Seq(fileB, fileA), nothingResolves)

        // The negId -> name map is a pure function of the unresolved-name set, independent of file order.
        assert(forward.unresolvedFullNameByNegId.toMap == reversed.unresolvedFullNameByNegId.toMap)
        // And every reference resolves to its own name regardless of order (file A is index 0 forward, 1 reversed).
        assert(nameOf(forward, 0, -2) == "scala.deprecated")
        assert(nameOf(reversed, 1, -2) == "scala.deprecated")
        assert(nameOf(forward, 1, -2) == "scala.annotation.unused")
        assert(nameOf(reversed, 0, -2) == "scala.annotation.unused")
    }

    "names resolving to a loaded symbol go to the final-index map, not the unresolved map" in {
        val file: Map[Int, String] = Map(-2 -> "kyo.Loaded", -3 -> "scala.deprecated")
        // kyo.Loaded resolves to final index 7; scala.deprecated is absent from the classpath.
        val resolve: String => Int = name => if name == "kyo.Loaded" then 7 else -1

        val remap = ClasspathOrchestrator.globalizeUnresolvedNegIds(Seq(file), resolve)

        assert(remap.perFileNegIdToFinal(0).getOrDefault(-2, -1) == 7)
        assert(!remap.perFileNegIdToGlobalNeg(0).containsKey(-2))
        assert(nameOf(remap, 0, -3) == "scala.deprecated")
        assert(remap.unresolvedFullNameByNegId.values.toSet == Set("scala.deprecated"))
    }

    "a name present in the index but without a materialised final id folds into the unresolved path" in {
        // resolveFinalIdx returns -1 (the orphan case: name in fullNameIndex but symbolIdMap miss).
        val file: Map[Int, String] = Map(-2 -> "scala.deprecated")
        val orphan: String => Int  = _ => -1

        val remap = ClasspathOrchestrator.globalizeUnresolvedNegIds(Seq(file), orphan)

        // The negId is not dropped: it resolves to its fully-qualified name via the global map.
        assert(nameOf(remap, 0, -2) == "scala.deprecated")
        assert(remap.perFileNegIdToFinal(0).isEmpty)
    }

end ClasspathOrchestratorNegIdTest
