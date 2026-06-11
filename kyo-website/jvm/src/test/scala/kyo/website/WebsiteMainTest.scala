package kyo.website

import java.nio.file.Files
import java.nio.file.Paths
import kyo.*

/** Tests for `WebsiteMain` CLI (Phase 4 scope: arg parsing + smoke emit). */
class WebsiteMainTest extends WebsiteTest:

    // ---- Helpers ----

    private def findRepoRoot(): java.nio.file.Path =
        var dir = Paths.get(java.lang.System.getProperty("user.dir")).toAbsolutePath
        while dir != null && !Files.exists(dir.resolve("build.sbt")) do
            dir = dir.getParent
        if dir == null then throw new RuntimeException("repo root not found")
        dir
    end findRepoRoot

    private def tmpDir(using Frame): Path < Sync =
        Sync.defer(Path(Files.createTempDirectory("kyo-main-test").toString))

    private def stubBundleDir(using Frame): Path < Sync =
        Sync.defer {
            val d = Files.createTempDirectory("kyo-main-bundle")
            java.nio.file.Files.writeString(d.resolve("main.js"), "// stub")
            java.nio.file.Files.writeString(d.resolve("main.js.map"), "{}")
            Path(d.toString)
        }

    private def readFile(path: Path)(using Frame): String < (Sync & Abort[WebsiteException]) =
        Abort.run[FileReadException](path.read).map {
            case Result.Success(s) => s
            case Result.Failure(e) => Abort.fail(WebsiteEmitException(path.toString, e))
            case p: Result.Panic   => Abort.error(p)
        }

    // ---- Test 11: CLI smoke emit writes index.html ----

    "CLI smoke: emit via WebsiteGenerator writes complete artifact" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            repoRoot = Path(findRepoRoot().toString)
            _ <- WebsiteGenerator.emit(
                Chunk.empty[WebsiteContent],
                out,
                WebsiteGenerator.Config(repoRoot, bundleDir)
            )
            html  <- readFile(out / "index.html")
            json  <- readFile(out / "versions.json")
            cname <- readFile(out / "CNAME")
        yield
            assert(html.contains("<!DOCTYPE html>"), "index.html must be a complete document")
            assert(html.contains("data-section=\"hero\""), "hero section must be present")
            assert(json == "[]", "empty content yields empty versions.json")
            assert(cname.trim == "getkyo.io", "CNAME must be getkyo.io")
        end for
    }

    // ---- SEO-3: sitemap.xml and robots.txt are always written, even for empty content ----

    "sitemap.xml and robots.txt always written, even for empty content (SEO-3)" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            repoRoot = Path(findRepoRoot().toString)
            _ <- WebsiteGenerator.emit(
                Chunk.empty[WebsiteContent],
                out,
                WebsiteGenerator.Config(repoRoot, bundleDir)
            )
            sitemapExists <- (out / "sitemap.xml").exists
            robotsExists  <- (out / "robots.txt").exists
            sitemap       <- readFile(out / "sitemap.xml")
        yield
            assert(sitemapExists, "sitemap.xml must be written")
            assert(robotsExists, "robots.txt must be written")
            // The root is always indexable, so even empty content lists "/".
            assert(sitemap.contains("<loc>https://getkyo.io/</loc>"), s"empty-content sitemap must list the root: $sitemap")
        end for
    }

    // ---- Test 12: --out flag is parsed correctly ----

    "emit to the specified output directory" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            repoRoot = Path(findRepoRoot().toString)
            _ <- WebsiteGenerator.emit(
                Chunk.empty[WebsiteContent],
                out,
                WebsiteGenerator.Config(repoRoot, bundleDir)
            )
            exists <- (out / "index.html").exists
        yield assert(exists, s"output must be written to the specified directory: $out")
        end for
    }

    // ---- Test 13 (Phase-4 WARN-1): parseOut / flagValue parse directly, no emit ----

    "parseOut parses --out flag directly" in {
        assert(WebsiteMain.parseOut(Chunk("--out", "/x")) == "/x")
        assert(WebsiteMain.parseOut(Chunk("--bundle-dir", "/b")) == "/tmp/kyo-site")
        assert(WebsiteMain.flagValue(Chunk("--out", "/x"), "--out") == Present("/x"))
        assert(WebsiteMain.flagValue(Chunk("--out", "/x"), "--missing") == Absent)
        assert(WebsiteMain.flagValue(Chunk("--content", "/c"), "--content") == Present("/c"))
    }

    // ---- Phase-8 audit WARN-1: latest-version selection is SEMANTIC, not lexicographic ----

    /** Create a `--content` directory holding one subdirectory per `tag`, each with a minimal
      * `README.md` (no `## Modules`, so `WebsiteContent.fromRepo` degrades to empty groups). The
      * subdirectories are created in the given `tags` order; the real filesystem listing order is
      * arbitrary, so this exercises the order-independence of `parseContent`.
      */
    private def contentDirWithTags(tags: Seq[String])(using Frame): String < Sync =
        Sync.defer {
            val root = Files.createTempDirectory("kyo-content-tags")
            tags.foreach { tag =>
                val tagDir = Files.createDirectory(root.resolve(tag))
                Files.writeString(tagDir.resolve("README.md"), s"# Kyo $tag\n\n## Introduction\n\nDocs for $tag.\n")
            }
            root.toString
        }

    private def latestTagOf(content: Chunk[WebsiteContent]): Maybe[String] =
        Maybe.fromOption(content.find(_.version.latest).map(_.version.tag))

    /** The realistic unsorted kyo tag set from the audit: lexicographically `v0.16.2 < v0.19.0 <
      * v0.9.3`, so a lexicographic stable-last picks the STALE `v0.9.3` while a semantic sort picks
      * the newest stable `v0.19.0`. The RC of `v0.19.0` must not win over its stable release.
      */
    private val auditTags = Seq("v0.9.3", "v0.16.2", "v0.19.0", "v0.19.0-RC1")

    "parseContent flags the newest STABLE version as latest, not the lexicographic last (WARN-1)" in {
        for
            dir     <- contentDirWithTags(auditTags)
            content <- WebsiteMain.parseContent(Chunk("--content", dir))
        yield
            assert(latestTagOf(content) == Present("v0.19.0"), s"latest must be v0.19.0, got ${latestTagOf(content)}")
            assert(latestTagOf(content) != Present("v0.9.3"), "latest must NOT be the lexicographic-last v0.9.3")
            assert(latestTagOf(content) != Present("v0.19.0-RC1"), "latest must NOT be the pre-release v0.19.0-RC1")
            assert(content.count(_.version.latest) == 1, "exactly one version is flagged latest")
        end for
    }

    "parseContent latest selection is order-independent (lexicographic / shuffled input) (WARN-1)" in {
        for
            lexDir      <- contentDirWithTags(auditTags.sorted) // v0.16.2, v0.19.0, v0.19.0-RC1, v0.9.3
            lexContent  <- WebsiteMain.parseContent(Chunk("--content", lexDir))
            shufDir     <- contentDirWithTags(Seq("v0.19.0-RC1", "v0.9.3", "v0.19.0", "v0.16.2"))
            shufContent <- WebsiteMain.parseContent(Chunk("--content", shufDir))
        yield
            assert(latestTagOf(lexContent) == Present("v0.19.0"), s"lex-ordered input still yields v0.19.0, got ${latestTagOf(lexContent)}")
            assert(latestTagOf(shufContent) == Present("v0.19.0"), s"shuffled input still yields v0.19.0, got ${latestTagOf(shufContent)}")
        end for
    }

    "parseContent orders versions.json semantically, RC before its stable release (WARN-1)" in {
        for
            dir     <- contentDirWithTags(auditTags)
            content <- WebsiteMain.parseContent(Chunk("--content", dir))
        yield
            val order = content.map(_.version.tag)
            assert(order == Chunk("v0.9.3", "v0.16.2", "v0.19.0-RC1", "v0.19.0"), s"semantic ascending order, got $order")
            val rcIdx     = order.indexOf("v0.19.0-RC1")
            val stableIdx = order.indexOf("v0.19.0")
            assert(rcIdx >= 0 && stableIdx >= 0 && rcIdx < stableIdx, s"RC must precede stable, got $order")
        end for
    }

end WebsiteMainTest
