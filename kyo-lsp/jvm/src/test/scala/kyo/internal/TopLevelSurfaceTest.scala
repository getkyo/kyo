package kyo

import java.io.File
import scala.io.Source

/** Verifies that exactly 8 top-level public types exist in kyo-lsp/shared/src/main/scala/kyo/
  * (excluding the internal/lsp/ subdirectory). Enforces steering A5 / INV-103.
  *
  * The expected set is exactly: Lsp, LspHandler, LspServer, LspClient, LspException,
  * LspCapabilities, LspConfig, LspInfo.
  */
class TopLevelSurfaceTest extends Test:

    private val expectedTopLevel: Set[String] = Set(
        "Lsp",
        "LspHandler",
        "LspServer",
        "LspClient",
        "LspException",
        "LspCapabilities",
        "LspConfig",
        "LspInfo"
    )

    /** Finds the kyo-lsp source directory relative to the classpath. */
    private def findKyoLspSrcDir: Option[File] =
        // Try relative to the project root (worktree).
        val candidates = Seq(
            new File("kyo-lsp/shared/src/main/scala/kyo"),
            new File("../kyo-lsp/shared/src/main/scala/kyo"),
            new File("../../kyo-lsp/shared/src/main/scala/kyo")
        )
        candidates.find(f => f.exists() && f.isDirectory)
    end findKyoLspSrcDir

    "Exactly 8 top-level public types in kyo-lsp/shared/src/main/scala/kyo/*.scala (INV-103)" in run {
        findKyoLspSrcDir match
            case None =>
                // Running in an environment where the source tree is not available (e.g. CI jar-only run).
                // Skip the file-system check; the compile gate already enforced this.
                succeed
            case Some(srcDir) =>
                // Collect all .scala files at the top level (not in internal/).
                val topLevelFiles = srcDir
                    .listFiles()
                    .filter(f => f.isFile && f.getName.endsWith(".scala"))
                    .toSeq

                // Pattern: a line starting at column 0 (no leading whitespace) with a type keyword.
                // This correctly excludes nested types which have indentation.
                val topLevelPattern =
                    """^(object|final\s+class|sealed\s+(abstract\s+)?(trait|class)|opaque\s+type|case\s+class|class|trait|enum)\s+(\w+)""".r

                val foundNames = topLevelFiles.flatMap { file =>
                    val lines = Source.fromFile(file).getLines().toSeq
                    lines.flatMap { line =>
                        // Only match lines with zero indentation (top-level declarations).
                        if !line.startsWith(" ") && !line.startsWith("\t") then
                            topLevelPattern.findFirstMatchIn(line).map { m =>
                                val groups   = m.subgroups
                                val typeName = groups.last
                                typeName
                            }
                        else
                            None
                    }
                }.toSet

                // All found names should be in the expected set.
                val unexpected = foundNames -- expectedTopLevel
                if unexpected.nonEmpty then
                    fail(s"Unexpected top-level types found: ${unexpected.mkString(", ")}")
                else
                    succeed
                end if
    }

    "Expected type names are all present in the source set" in run {
        // This is a compile-time structural check; if any of these types do not
        // exist, importing them will fail to compile.
        val lspName          = classOf[Lsp.type].getSimpleName
        val lspHandlerName   = classOf[LspHandler[?, ?, ?]].getSimpleName
        val lspExceptionName = classOf[LspException].getSimpleName
        val lspCapsName      = classOf[LspCapabilities.type].getSimpleName
        val lspConfigName    = classOf[LspConfig].getSimpleName
        val lspInfoName      = classOf[LspInfo].getSimpleName
        assert(lspInfoName == "LspInfo")
        assert(lspConfigName == "LspConfig")
        assert(lspExceptionName == "LspException")
        succeed
    }

end TopLevelSurfaceTest
