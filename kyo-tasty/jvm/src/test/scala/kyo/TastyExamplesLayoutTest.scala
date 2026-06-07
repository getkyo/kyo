package kyo

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

/** JVM-only source-layout tests for the kyo-tasty-examples extraction.
  *
  * Uses java.nio.file to walk the worktree source tree; not available on Scala.js or Scala Native at link time, so these tests live in
  * jvm/src/test rather than shared/src/test.
  */
class TastyExamplesLayoutTest extends kyo.test.Test[Any]:

    "examples no longer ship in kyo-tasty source tree" in {
        // kyo-tasty/shared/src/main/scala/kyo/tasty/examples must not exist after.
        // This is a source-level check (not a JAR check); the directory was deleted as part of the
        // move to kyo-tasty-examples. A JAR-level check would require sbt compilation to have run
        // first and is deferred.
        val worktreeRoot = findWorktreeRoot
        val examplesDir  = worktreeRoot.resolve("kyo-tasty/shared/src/main/scala/kyo/tasty/examples")
        if Files.exists(examplesDir) then
            val scalaFiles = Files.list(examplesDir).iterator.asScala
                .filter(p => p.getFileName.toString.endsWith(".scala"))
                .toList
            assert(
                scalaFiles.isEmpty,
                s"violated: kyo-tasty/shared/src/main/scala/kyo/tasty/examples/ still contains .scala files: ${scalaFiles.map(_.getFileName).mkString(", ")}"
            )
        else
            succeed
        end if
    }

    "kyo-tasty-examples sources at expected path with correct package" in {
        // kyo-tasty-examples/shared/src/main/scala/examples/ must contain exactly the 4
        // moved example files, each declaring `package examples` at the first non-blank line.
        val expectedNames = Set(
            "CodegenExample.scala",
            "IdeHoverExample.scala",
            "JavaScalaBridgeExample.scala",
            "RuntimeReflectionExample.scala"
        )
        val worktreeRoot = findWorktreeRoot
        val examplesDir  = worktreeRoot.resolve("kyo-tasty-examples/shared/src/main/scala/examples")
        assert(
            Files.isDirectory(examplesDir),
            s"expected directory $examplesDir to exist"
        )
        val scalaFiles = Files.list(examplesDir).iterator.asScala
            .filter(p => p.getFileName.toString.endsWith(".scala"))
            .toList
        val foundNames = scalaFiles.map(_.getFileName.toString).toSet
        assert(
            foundNames == expectedNames,
            s"expected files $expectedNames, found $foundNames"
        )
        val packageErrors = scalaFiles.flatMap { path =>
            val firstLine = Files.readAllLines(path).iterator.asScala
                .find(_.trim.nonEmpty)
                .getOrElse("")
            if firstLine.trim == "package examples" then Nil
            else List(s"${path.getFileName}: first non-blank line is '${firstLine.trim}', expected 'package examples'")
        }
        assert(
            packageErrors.isEmpty,
            s"package declaration errors: ${packageErrors.mkString("; ")}"
        )
    }

    /** Walk up from user.dir until a directory containing build.sbt is found; that is the worktree root.
      *
      * sbt sets user.dir to the module subproject directory (e.g. kyo-tasty/jvm/), so we cannot rely on it directly for repo-relative
      * paths.
      */
    private def findWorktreeRoot: Path =
        var candidate = Paths.get(java.lang.System.getProperty("user.dir")).toAbsolutePath
        while candidate != null && !Files.exists(candidate.resolve("build.sbt")) do
            candidate = candidate.getParent
        end while
        require(candidate != null, "Could not locate worktree root (no build.sbt found in ancestor directories)")
        candidate
    end findWorktreeRoot

end TastyExamplesLayoutTest
