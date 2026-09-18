package kyo.internal

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kyo.*
import scala.jdk.CollectionConverters.*

/** The guard on the battery's own neutrality: no conformance body may name an engine.
  *
  * A body that branches on the engine asserts that a divergence is forced: a strong claim, rarely checked, and a comfortable place for a bug
  * to live, since the test is green and reads as thorough. A named capability states the same thing where it can be checked.
  *
  * A source scan rather than a runtime assertion because what it forbids is a shape in the text. The banned list is DERIVED from the
  * registered descriptors, so a backend extends this check by existing and there is no second list to keep current.
  *
  * JVM-only because it reads the source tree; what it guards is shared source, so it guards every platform.
  */
class SqlBackendNeutralitySourceTest extends kyo.Test:

    /** The shared conformance source, found by probing UPWARD from the test JVM's working directory.
      *
      * Upward rather than anchored: a cross-built module's JVM project can be forked at the module directory, at the platform subdirectory, or
      * at the repository root, and assuming one silently reads nothing under the other two. The leaf below asserts it was found, which is the
      * failure mode a source scan has and a runtime assertion does not.
      */
    private val conformanceRoot: Path =
        val suffix = Paths.get("kyo-sql-tests/shared/src/test/scala/kyo")
        @annotation.tailrec
        def probe(dir: Path): Path =
            if dir == null then Paths.get("").toAbsolutePath.resolve(suffix)
            else
                val candidate = dir.resolve(suffix)
                if Files.isDirectory(candidate) then candidate else probe(dir.getParent)
        probe(Paths.get("").toAbsolutePath)
    end conformanceRoot

    /** Every engine name a registered descriptor answers to, lowercased.
      *
      * Both the id and the label, because either is a name a body could branch on, and the two are free to differ.
      */
    private val engineNames: Set[String] =
        SqlTestBackends.registered.flatMap(b => Chunk(b.id, b.label)).map(_.toLowerCase).toSet

    private def conformanceFiles: Seq[Path] =
        if !Files.isDirectory(conformanceRoot) then Seq.empty
        else
            // `Files.list` answers a Stream holding an OPEN directory handle, so it is closed here rather than left to
            // the collector. Draining it to a Seq does not release the handle, and the descriptor outlives the run.
            val listing = Files.list(conformanceRoot)
            try
                listing.iterator().asScala
                    .filter(p => p.getFileName.toString.endsWith("ConformanceTest.scala"))
                    .toSeq
                    .sortBy(_.getFileName.toString)
            finally listing.close()
            end try

    /** One line of a conformance file with its location, excluding the lines where an engine name is legitimate. */
    private case class Line(file: String, number: Int, text: String)

    /** Scaladoc and comments are excluded, and that is not a loophole.
      *
      * Prose NAMING an engine is how a capability's direction gets recorded ("one engine's column reaches further than the other's"), and the
      * rule is about what the body DOES, not about what it explains. What the scan is looking for is an engine name reaching the executed
      * text: a branch, a literal spliced into SQL, a per-engine expectation.
      */
    private def isProse(text: String): Boolean =
        val t = text.trim
        t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")

    private def offendingLines: Seq[Line] =
        conformanceFiles.flatMap { file =>
            val name = file.getFileName.toString
            Files.readAllLines(file).asScala.toSeq.zipWithIndex.collect {
                case (text, i) if !isProse(text) && engineNames.exists(text.toLowerCase.contains) =>
                    Line(name, i + 1, text.trim)
            }
        }

    "the source tree the scan reads is actually found" in {
        // Without this the whole check passes by reading nothing, which is the failure mode a source scan has and a
        // runtime assertion does not.
        val files = conformanceFiles
        assert(
            files.sizeIs > 10,
            s"expected to find the conformance suites under $conformanceRoot, found ${files.size}"
        )
    }

    "the descriptors name at least one engine to ban" in {
        // The banned list is derived, so an empty descriptor set would make the scan vacuous exactly as it would make
        // every cross-engine leaf vacuous.
        assert(
            engineNames.nonEmpty,
            "the banned list is derived from the registered descriptors, and none registered, so this check would pass on anything"
        )
    }

    "no conformance body names an engine" in {
        val offenders = offendingLines
        assert(
            offenders.isEmpty,
            s"a conformance body names an engine, which asserts that a divergence is forced. Name a capability on " +
                s"SqlTestBackend instead, with a scaladoc saying what the difference IS, so every backend has to answer it. " +
                s"Engine names in scope: ${engineNames.toSeq.sorted.mkString(", ")}. Offending lines:\n" +
                offenders.map(l => s"  ${l.file}:${l.number}  ${l.text}").mkString("\n")
        )
    }

end SqlBackendNeutralitySourceTest
