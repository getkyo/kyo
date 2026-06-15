package kyo.website

import java.nio.file.Files
import java.nio.file.Paths
import kyo.*

/** Tests for `WebsiteMain` CLI: arg parsing and smoke emit. */
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

    // ---- sitemap.xml and robots.txt are always written, even for empty content ----

    "sitemap.xml and robots.txt always written, even for empty content" in {
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

    "parseOut parses --out flag directly" in {
        assert(WebsiteMain.parseOut(Chunk("--out", "/x")) == "/x")
        assert(WebsiteMain.parseOut(Chunk("--bundle-dir", "/b")) == "/tmp/kyo-site")
        assert(WebsiteMain.flagValue(Chunk("--out", "/x"), "--out") == Present("/x"))
        assert(WebsiteMain.flagValue(Chunk("--out", "/x"), "--missing") == Absent)
        assert(WebsiteMain.flagValue(Chunk("--content", "/c"), "--content") == Present("/c"))
    }

    /** Create a `--content` forward-append directory holding one subdirectory per `tag`, each with a
      * minimal `README.md` (no `## Modules`, so `WebsiteContent.fromRepo` degrades to empty groups). The
      * subdirectories are created in the given `tags` order; the real filesystem listing order is
      * arbitrary, so this exercises the order-independence of the append path.
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

    private def repoRootStr: String = findRepoRoot().toString

    // ---- Forward-only render: the current version comes from the LIVE repo ----

    "currentVersion is the repo's newest release tag, flagged latest" in {
        for
            v <- WebsiteMain.currentVersion(repoRootStr)
        yield
            // The repo's newest release tag by git date is v1.0.0-RC2 (a newer RC beats older stables).
            assert(v.tag == "v1.0.0-RC2", s"current version tag must be the newest release tag, got ${v.tag}")
            assert(v.label == "1.0.0-RC2", s"current version label drops the leading v, got ${v.label}")
            assert(v.latest, "the current version must be flagged latest")
        end for
    }

    "parseContent with no --content renders ONE current version from the live repo, flagged latest" in {
        for
            content <- WebsiteMain.parseContent(Chunk.empty[String], repoRootStr)
        yield
            assert(content.size == 1, s"forward-only render yields exactly the current version, got ${content.size}")
            val current = content.head
            assert(current.version.latest, "the lone version must be flagged latest")
            assert(current.version.tag == "v1.0.0-RC2", s"current version tag must be the newest release tag, got ${current.version.tag}")
            // The live root README has a ## Modules table, so the current version carries real module groups.
            val slugs = current.groups.flatMap(_.modules).map(_.slug).toSeq.toSet
            assert(current.groups.nonEmpty, "the live README ## Modules table must yield non-empty groups")
            assert(slugs.contains("kyo-core"), s"live modules must include kyo-core, got $slugs")
            assert(slugs.contains("kyo-http"), s"live modules must include kyo-http, got $slugs")
            assert(slugs.contains("kyo-schema"), s"live modules must include kyo-schema, got $slugs")
            assert(slugs.contains("kyo-actor"), s"live modules must include kyo-actor, got $slugs")
        end for
    }

    "parseContent appends --content snapshots oldest-first, current version last and latest" in {
        for
            // Forward-append two PAST snapshots; the live current version is always appended last.
            dir     <- contentDirWithTags(Seq("v0.19.0", "v0.9.3"))
            content <- WebsiteMain.parseContent(Chunk("--content", dir), repoRootStr)
        yield
            val order = content.map(_.version.tag)
            // Appended snapshots come first (oldest-first by semantic version), the current version last.
            assert(order == Chunk("v0.9.3", "v0.19.0", "v1.0.0-RC2"), s"snapshots oldest-first then current last, got $order")
            assert(latestTagOf(content) == Present("v1.0.0-RC2"), s"only the current version is latest, got ${latestTagOf(content)}")
            assert(content.count(_.version.latest) == 1, "exactly one version is flagged latest")
        end for
    }

    "parseContent drops a non-version snapshot dir and a snapshot duplicating the current version" in {
        for
            // backup-foo/test/not-a-version are non-version dirs; v1.0.0-RC2 duplicates the current version.
            dir     <- contentDirWithTags(Seq("v0.19.0", "v1.0.0-RC2", "backup-foo", "test", "not-a-version"))
            content <- WebsiteMain.parseContent(Chunk("--content", dir), repoRootStr)
        yield
            val tags = content.map(_.version.tag).toSeq
            // Only the v0.19.0 snapshot survives as an append; the current version (v1.0.0-RC2) is the
            // live render, never duplicated from the snapshot dir.
            assert(tags == Seq("v0.19.0", "v1.0.0-RC2"), s"only the non-duplicate release snapshot + current survive, got $tags")
            assert(content.count(_.version.tag == "v1.0.0-RC2") == 1, "the current version must appear exactly once (not duplicated)")
            assert(latestTagOf(content) == Present("v1.0.0-RC2"), s"current version is latest, got ${latestTagOf(content)}")
        end for
    }

    "tagTimestamps maps real release tags and excludes non-version tags" in {
        for
            ts <- WebsiteMain.tagTimestamps(repoRootStr)
        yield
            assert(ts.nonEmpty, "the kyo repo has dated release tags, so the map must be non-empty")
            assert(ts.contains("v1.0.0-RC2"), s"v1.0.0-RC2 must carry a timestamp: ${ts.keySet}")
            assert(ts.contains("v0.19.0"), s"v0.19.0 must carry a timestamp: ${ts.keySet}")
            // v1.0.0-RC2 is the newest release tag by creation date, beating the older stable v0.19.0.
            assert(ts("v1.0.0-RC2") > ts("v0.19.0"), "v1.0.0-RC2 must be newer than v0.19.0 by git date")
            // Non-release tags (backup-*, test, ...) are never version tags, so they never appear.
            assert(!ts.keys.exists(k => WebsiteVersion.parse(k).isEmpty), s"only parseable release tags may appear: ${ts.keySet}")
        end for
    }

    "parseRepoRoot discovers the build.sbt ancestor when --repo-root absent" in {
        for
            result <- WebsiteMain.parseRepoRoot(Chunk.empty[String])
            exists <- (Path(result) / "build.sbt").exists
        yield
            assert(result.nonEmpty, s"discovered repo root must be non-empty")
            assert(exists, s"parseRepoRoot must return a directory containing build.sbt, got: $result")
        end for
    }

    "parseRepoRoot honors an explicit --repo-root flag verbatim" in {
        for
            result <- WebsiteMain.parseRepoRoot(Chunk("--repo-root", "/explicit/root"))
        yield assert(result == "/explicit/root", s"flag value must win verbatim, got: $result")
        end for
    }

    "parseBundleDir honors an explicit --bundle-dir flag verbatim, no discovery" in {
        for
            result <- WebsiteMain.parseBundleDir(Chunk("--bundle-dir", "/explicit/bundle"), "/any/root")
        yield assert(result == "/explicit/bundle", s"flag value must win verbatim, got: $result")
        end for
    }

    /** Build a synthetic target/ tree containing:
      *   - a regular FILE named `scala-3.x` (must be silently skipped, not passed to `.list`)
      *   - a real directory `scala-3.8.3/kyo-website-bundle-opt/` holding `main.js`
      * `discoverBundleDir` must skip the file and select the `-opt/main.js` directory.
      */
    "discoverBundleDir skips a non-directory scala-* entry and selects the -opt/main.js dir" in {
        Sync.defer {
            val repoRoot  = Files.createTempDirectory("kyo-main-discover")
            val targetDir = repoRoot.resolve("kyo-website-bundle/js/target")
            java.nio.file.Files.createDirectories(targetDir)
            // A regular file named scala-3.x: must be skipped, not passed to .list
            java.nio.file.Files.writeString(targetDir.resolve("scala-3.x"), "not a directory")
            // A real scala-3.8.3/kyo-website-bundle-opt/ directory holding main.js
            val optDir = targetDir.resolve("scala-3.8.3/kyo-website-bundle-opt")
            java.nio.file.Files.createDirectories(optDir)
            java.nio.file.Files.writeString(optDir.resolve("main.js"), "// bundle")
            repoRoot.toString
        }.flatMap { repoRoot =>
            WebsiteMain.parseBundleDir(Chunk.empty[String], repoRoot)
        }.map { result =>
            assert(result.endsWith("kyo-website-bundle-opt"), s"must select the -opt dir, got: $result")
            assert(!result.contains("scala-3.x"), s"must not select the non-directory scala-3.x file, got: $result")
        }
    }

end WebsiteMainTest
