package kyo.website

import java.nio.file.Files
import java.nio.file.Paths
import kyo.*

/** Tests for `WebsiteMain` CLI (Phase 4 scope: arg parsing + smoke emit). */
class WebsiteMainTest extends Test:

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

    "CLI smoke: emit via WebsiteGenerator writes complete artifact" in run {
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

    // ---- Test 12: --out flag is parsed correctly ----

    "emit to the specified output directory" in run {
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

end WebsiteMainTest
