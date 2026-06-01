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

    "build.sbt declares flexmark only on JVM config (INV-005 build-graph half)" - {
        "flexmark deps are inside .jvmSettings block, not shared .settings" in {
            val lines = buildSbtLines()

            // Locate the kyo-website project block: starts at "lazy val `kyo-website`"
            val projectStart = lines.indexWhere(l => l.contains("lazy val `kyo-website`") && !l.contains("bundle"))
            assert(projectStart >= 0, "kyo-website project not found in build.sbt")

            // Locate the .jvmSettings block within the kyo-website project
            val jvmSettingsStart = lines.indexWhere(_.contains(".jvmSettings"), projectStart)
            assert(jvmSettingsStart > projectStart, ".jvmSettings block not found in kyo-website")

            // flexmark declarations should exist in the file at all
            val flexmarkLines = lines.filter(_.contains("com.vladsch.flexmark"))
            assert(flexmarkLines.nonEmpty, "no flexmark declaration found in build.sbt")

            // Every flexmark line must appear after jvmSettings opens and before any line closing that block
            // (i.e., inside jvmSettings), not in a shared .settings block.
            for line <- flexmarkLines do
                val lineIdx = lines.indexOf(line)
                assert(
                    lineIdx > jvmSettingsStart,
                    s"flexmark declaration '${line.trim}' appears before .jvmSettings at line $jvmSettingsStart"
                )
            end for

            succeed
        }
    }

end WebsiteBuildGraphTest
