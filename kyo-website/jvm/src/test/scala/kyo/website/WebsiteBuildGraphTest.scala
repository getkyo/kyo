package kyo.website

import java.nio.file.Files
import java.nio.file.Paths

class WebsiteBuildGraphTest extends Test:

    // Locate the repo root by walking up from user.dir until we find build.sbt.
    private def repoRoot(): java.nio.file.Path =
        var dir = Paths.get(java.lang.System.getProperty("user.dir")).toAbsolutePath
        while dir != null && !Files.exists(dir.resolve("build.sbt")) do
            dir = dir.getParent
        if dir == null then throw new RuntimeException("repo root with build.sbt not found")
        dir
    end repoRoot

    // build.sbt is at the repo root.
    private def buildSbtLines(): List[String] =
        val p = repoRoot().resolve("build.sbt")
        new String(Files.readAllBytes(p)).linesIterator.toList

    // kyo-website/shared, kyo-website/js, and kyo-website-bundle source trees should have no flexmark import.
    private def sourceLines(subdir: String): List[String] =
        val root = repoRoot().resolve(subdir)
        if Files.exists(root) then
            import scala.jdk.CollectionConverters.*
            Files.walk(root)
                .iterator()
                .asScala
                .filter(p => p.toString.endsWith(".scala") || p.toString.endsWith(".sbt"))
                .flatMap(p => new String(Files.readAllBytes(p)).linesIterator)
                .toList
        else
            Nil
        end if
    end sourceLines

    "flexmark JVM-only import grep (INV-005 build-graph half)" - {
        "zero flexmark matches in kyo-website/shared" in {
            val lines = sourceLines("kyo-website/shared")
            val hits  = lines.filter(_.contains("com.vladsch.flexmark"))
            assert(hits.isEmpty, s"unexpected flexmark reference in shared: ${hits.mkString(", ")}")
        }

        "zero flexmark matches in kyo-website/js" in {
            val lines = sourceLines("kyo-website/js")
            val hits  = lines.filter(_.contains("com.vladsch.flexmark"))
            assert(hits.isEmpty, s"unexpected flexmark reference in js: ${hits.mkString(", ")}")
        }

        "zero flexmark matches in kyo-website-bundle" in {
            val lines = sourceLines("kyo-website-bundle")
            val hits  = lines.filter(_.contains("com.vladsch.flexmark"))
            assert(hits.isEmpty, s"unexpected flexmark reference in kyo-website-bundle: ${hits.mkString(", ")}")
        }
    }

    "build.sbt uses kyo-parse (no flexmark) for Markdown (INV-005 build-graph half, D6)" - {
        "no flexmark in build.sbt" in {
            val lines         = buildSbtLines()
            val flexmarkLines = lines.filter(_.contains("com.vladsch.flexmark"))
            assert(flexmarkLines.isEmpty, s"unexpected flexmark in build.sbt: ${flexmarkLines.mkString(", ")}")
        }

        "kyo-parse dependency present in build.sbt for kyo-website" in {
            val lines        = buildSbtLines()
            val projectStart = lines.indexWhere(l => l.contains("lazy val `kyo-website`") && !l.contains("bundle"))
            assert(projectStart >= 0, "kyo-website project not found in build.sbt")
            // Find the next project or end of file.
            val nextProject  = lines.indexWhere(l => l.startsWith("lazy val") && !l.contains("kyo-website"), projectStart + 1)
            val projectEnd   = if nextProject > 0 then nextProject else lines.length
            val projectBlock = lines.slice(projectStart, projectEnd)
            val hasParseDep  = projectBlock.exists(l => l.contains("kyo-parse") && l.contains("dependsOn"))
            assert(hasParseDep, s"kyo-parse dependsOn not found in kyo-website project block")
        }
    }

end WebsiteBuildGraphTest
