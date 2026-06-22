package kyo.website

import kyo.*
import kyo.AllowUnsafe.embrace.danger

class WebsiteBuildGraphTest extends WebsiteTest:

    // Locate the repo root by walking up from user.dir until we find build.sbt.
    private def repoRoot(): Path =
        @scala.annotation.tailrec
        def loop(dir: Path): Path =
            if (dir / "build.sbt").unsafe.exists() then dir
            else
                dir.parent match
                    case Maybe.Present(parent) => loop(parent)
                    case Maybe.Absent          => throw new RuntimeException("repo root with build.sbt not found")
        loop(Path(java.lang.System.getProperty("user.dir").nn))
    end repoRoot

    // build.sbt is at the repo root.
    private def buildSbtLines(): List[String] =
        (repoRoot() / "build.sbt").unsafe.read().getOrThrow.linesIterator.toList

    // kyo-website/shared, kyo-website/js, and kyo-website-bundle source trees should have no flexmark import.
    private def sourceLines(subdir: String): List[String] =
        val root = repoRoot() / subdir
        if root.unsafe.exists() then
            filesUnder(root)
                .filter(p => p.toString.endsWith(".scala") || p.toString.endsWith(".sbt"))
                .flatMap(p => p.unsafe.read().getOrThrow.linesIterator.toList)
        else
            Nil
        end if
    end sourceLines

    // Path.Unsafe.list collects each directory's entries and closes the stream before returning (no leaked fd);
    // recurse to cover the full tree without following symlinks.
    private def filesUnder(dir: Path): List[Path] =
        dir.unsafe.list().getOrThrow.toList.flatMap { entry =>
            if entry.unsafe.isDirectory() && !entry.unsafe.isSymbolicLink() then filesUnder(entry)
            else List(entry)
        }

    "flexmark JVM-only import grep" - {
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

    "build.sbt uses kyo-parse (no flexmark) for Markdown" - {
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

    // scalameta must appear ONLY in the kyo-website .jvmSettings block, never in the
    // shared/js/bundle source trees. The grep is scoped to import/dependency forms so prose
    // mentions of "scalameta" in scaladoc comments (e.g. DocsMarkdown.scala:11) do not trigger it.
    // Pattern: `import scala.meta` (import form) or `org.scalameta` (libraryDependencies form).
    // This mirrors the flexmark guard at lines 39-57 exactly.
    "scalameta JVM-only import grep" - {

        "zero scalameta in kyo-website/shared" in {
            val lines      = sourceLines("kyo-website/shared")
            val importHits = lines.filter(_.contains("import scala.meta"))
            val depHits    = lines.filter(_.contains("org.scalameta"))
            assert(
                importHits.isEmpty,
                s"unexpected scala.meta import in kyo-website/shared: ${importHits.mkString(", ")}"
            )
            assert(
                depHits.isEmpty,
                s"unexpected org.scalameta dependency in kyo-website/shared: ${depHits.mkString(", ")}"
            )
        }

        "zero scalameta in kyo-website/js" in {
            val lines      = sourceLines("kyo-website/js")
            val importHits = lines.filter(_.contains("import scala.meta"))
            val depHits    = lines.filter(_.contains("org.scalameta"))
            assert(
                importHits.isEmpty,
                s"unexpected scala.meta import in kyo-website/js: ${importHits.mkString(", ")}"
            )
            assert(
                depHits.isEmpty,
                s"unexpected org.scalameta dependency in kyo-website/js: ${depHits.mkString(", ")}"
            )
        }

        "zero scalameta in kyo-website-bundle" in {
            val lines      = sourceLines("kyo-website-bundle")
            val importHits = lines.filter(_.contains("import scala.meta"))
            val depHits    = lines.filter(_.contains("org.scalameta"))
            assert(
                importHits.isEmpty,
                s"unexpected scala.meta import in kyo-website-bundle: ${importHits.mkString(", ")}"
            )
            assert(
                depHits.isEmpty,
                s"unexpected org.scalameta dependency in kyo-website-bundle: ${depHits.mkString(", ")}"
            )
        }

        "scalameta declared only in kyo-website .jvmSettings block (not in kyo-website-bundle)" in {
            val lines        = buildSbtLines()
            val projectStart = lines.indexWhere(l => l.contains("lazy val `kyo-website`") && !l.contains("bundle"))
            assert(projectStart >= 0, "kyo-website project not found in build.sbt")
            val nextProject  = lines.indexWhere(l => l.startsWith("lazy val") && !l.contains("kyo-website"), projectStart + 1)
            val projectEnd   = if nextProject > 0 then nextProject else lines.length
            val projectBlock = lines.slice(projectStart, projectEnd)
            assert(
                projectBlock.exists(l => l.contains("org.scalameta")),
                "org.scalameta must appear in the kyo-website project block in build.sbt"
            )
            // The bundle must not declare a scalameta dependency.
            val bundleStart = lines.indexWhere(l => l.contains("lazy val `kyo-website-bundle`"))
            if bundleStart >= 0 then
                val nextBundle  = lines.indexWhere(l => l.startsWith("lazy val") && !l.contains("kyo-website-bundle"), bundleStart + 1)
                val bundleEnd   = if nextBundle > 0 then nextBundle else lines.length
                val bundleBlock = lines.slice(bundleStart, bundleEnd)
                assert(
                    bundleBlock.forall(l => !l.contains("org.scalameta")),
                    s"org.scalameta must NOT appear in the kyo-website-bundle project block in build.sbt"
                )
            else
                succeed
            end if
        }
    }

    // If the fullLinkJS-optimized bundle output is present, grep it for
    // any scala_meta symbol references and assert zero occurrences. The file is only present
    // after `sbt kyo-website-bundleJS/fullLinkJS`; when absent the test is cancelled (not failed).
    "zero scala_meta in bundle-opt main.js (skip when absent)" in {
        val ver    = scala.util.Properties.versionNumberString
        val jsPath = repoRoot() / s"kyo-website-bundle/js/target/scala-$ver/kyo-website-bundle-opt/main.js"
        if !jsPath.unsafe.exists() then
            cancel("Bundle-opt check skipped: bundle-opt main.js not present (run fullLinkJS first)")
        else
            val text              = jsPath.unsafe.read().getOrThrow
            val scalaMetaCount    = text.sliding("scala_meta".length).count(_ == "scala_meta")
            val docsMarkdownCount = text.sliding("DocsMarkdown".length).count(_ == "DocsMarkdown")
            assert(
                scalaMetaCount == 0,
                s"bundle-opt main.js must contain zero scala_meta symbols, found $scalaMetaCount"
            )
            assert(
                docsMarkdownCount > 0,
                s"control: bundle-opt main.js must contain DocsMarkdown (proves file is a real linked output), found $docsMarkdownCount"
            )
        end if
    }

    // The client-side trees (bundle/js) must not reference the JVM-only transpiler surface.
    // Grep for the method/type names that must never appear in the JS compilation unit.
    "zero transpile/highlightScala/DocsMarkdownRender in bundle and js source" in {
        val bundleLines = sourceLines("kyo-website-bundle")
        val jsLines     = sourceLines("kyo-website/js")
        val allLines    = bundleLines ++ jsLines
        val renderHits = allLines.filter(l =>
            l.contains("DocsMarkdownRender") ||
                l.contains("highlightScala") ||
                l.contains(".transpile(")
        )
        assert(
            renderHits.isEmpty,
            s"DocsMarkdownRender/highlightScala/transpile must not appear in bundle or js source: ${renderHits.mkString(", ")}"
        )
    }

end WebsiteBuildGraphTest
