package kyo.website

import java.nio.file.Files
import java.nio.file.Paths
import kyo.*

/** Tests for `WebsiteGenerator.emit` (Phase 4 scope: landing route + artifact-root files).
  *
  * Each test emits into a fresh temp directory. Tests assert concrete file contents, not just
  * existence. No git commits; output directories are ephemeral.
  */
class WebsiteGeneratorTest extends Test:

    // ---- Test data ----

    private val v1 = WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true)
    private val v0 = WebsiteVersion("v0.9.3", "0.9.3", false)
    private val v2 = WebsiteVersion("v0.9.2", "0.9.2", false)

    private val oneVersion = Chunk(WebsiteContent("intro", Chunk.empty, v1))
    private val threeVersion = Chunk(
        WebsiteContent("intro1", Chunk.empty, v1),
        WebsiteContent("intro2", Chunk.empty, v0),
        WebsiteContent("intro3", Chunk.empty, v2)
    )

    // ---- Helpers ----

    private def tmpDir(using Frame): Path < Sync =
        Sync.defer(Path(Files.createTempDirectory("kyo-gen-test").toString))

    private def stubBundleDir(using Frame): Path < Sync =
        Sync.defer {
            val d = Files.createTempDirectory("kyo-bundle-stub")
            java.nio.file.Files.writeString(d.resolve("main.js"), "// stub")
            java.nio.file.Files.writeString(d.resolve("main.js.map"), "{}")
            Path(d.toString)
        }

    private def repoRoot(using Frame): Path =
        Path(findRepoRoot().toString)

    private def findRepoRoot(): java.nio.file.Path =
        var dir = Paths.get(java.lang.System.getProperty("user.dir")).toAbsolutePath
        while dir != null && !Files.exists(dir.resolve("build.sbt")) do
            dir = dir.getParent
        if dir == null then throw new RuntimeException("repo root not found")
        dir
    end findRepoRoot

    private def emit(
        content: Chunk[WebsiteContent],
        outDir: Path,
        bundleDir: Path
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        WebsiteGenerator.emit(content, outDir, WebsiteGenerator.Config(repoRoot, bundleDir))

    private def readFile(path: Path)(using Frame): String < (Sync & Abort[WebsiteException]) =
        Abort.run[FileReadException](path.read).map {
            case Result.Success(s) => s
            case Result.Failure(e) => Abort.fail(WebsiteEmitException(path.toString, e))
            case p: Result.Panic   => Abort.error(p)
        }

    // ---- Test 1: index.html is a complete document (INV-002/INV-009) ----

    "index.html is a complete HTML document (INV-002, INV-009)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(oneVersion, out, bundleDir)
            html      <- readFile(out / "index.html")
        yield
            assert(html.startsWith("<!DOCTYPE html>"), "must start with <!DOCTYPE html>")
            assert(html.contains("<html lang=\"en\""), "must have <html lang=\"en\"")
            assert(html.contains("<head>"), "must have <head>")
            assert(html.contains("<body>"), "must have <body>")
            assert(html.endsWith("</html>"), s"must end with </html>")
        end for
    }

    // ---- Test 2: index.html contains landing content (INV-009) ----

    "index.html contains landing page content (INV-002, INV-001, INV-012)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(oneVersion, out, bundleDir)
            html      <- readFile(out / "index.html")
        yield
            assert(html.contains("Build with AI."), "hero section missing")
            assert(html.contains("data-section=\"hero\""), "hero data-section missing")
            assert(html.contains("data-section=\"footer\""), "footer section missing")
            assert(html.contains("1.0.0-RC2"), "version label must appear in dropdown")
            // INV-012: the emitted CSS is the rendered WebsiteStyles.sheet, not a raw blob.
            // .feat-grid is a marker selector produced by WebsiteStyles.sheet.render.
            val styleStart = html.indexOf("<style>")
            val styleEnd   = html.indexOf("</style>")
            assert(styleStart >= 0 && styleEnd > styleStart, "head <style> block missing")
            val style      = html.substring(styleStart, styleEnd)
            val baseCssIdx = style.indexOf(UI.baseCss.take(30))
            val sheetIdx   = style.indexOf(".feat-grid {")
            assert(baseCssIdx >= 0, "baseCss reset missing from <style>")
            assert(sheetIdx >= 0, "WebsiteStyles.sheet marker (.feat-grid) missing from <style>")
            // INV-001: baseCss must appear strictly before the rendered sheet so site rules win.
            assert(baseCssIdx < sheetIdx, "baseCss must precede the WebsiteStyles.sheet rules")
        end for
    }

    // ---- Test 3: versions.json has correct shape and length (INV-010) ----

    "versions.json has correct shape with 3 versions (INV-010)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(threeVersion, out, bundleDir)
            json      <- readFile(out / "versions.json")
        yield
            assert(json.startsWith("["), "must be a JSON array")
            assert(json.endsWith("]"), "must end with ]")
            assert(json.contains("\"tag\": \"v1.0.0-RC2\""), "v1 tag missing")
            assert(json.contains("\"label\": \"1.0.0-RC2\""), "v1 label missing")
            assert(json.contains("\"latest\": true"), "latest true missing")
            assert(json.contains("\"tag\": \"v0.9.3\""), "v0.9.3 tag missing")
            assert(json.contains("\"latest\": false"), "latest false missing")
            assert(json.contains("\"tag\": \"v0.9.2\""), "v0.9.2 tag missing")
            val v1Idx = json.indexOf("v1.0.0-RC2")
            val v0Idx = json.indexOf("v0.9.3")
            val v2Idx = json.indexOf("v0.9.2")
            assert(v1Idx < v0Idx, "v1 must precede v0.9.3")
            assert(v0Idx < v2Idx, "v0.9.3 must precede v0.9.2")
        end for
    }

    // ---- Test 4: CNAME exact content (INV-011) ----

    "CNAME content is exactly getkyo.io (INV-011)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(oneVersion, out, bundleDir)
            cname     <- readFile(out / "CNAME")
        yield
            assert(cname.trim == "getkyo.io", s"CNAME must be getkyo.io, got: '$cname'")
            assert(cname == "getkyo.io", s"CNAME must have no trailing newline")
        end for
    }

    // ---- Test 5: .nojekyll exists and is empty (INV-011) ----

    ".nojekyll exists at root and is empty (INV-011)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(oneVersion, out, bundleDir)
            nojekyll  <- readFile(out / ".nojekyll")
        yield assert(nojekyll.isEmpty, s".nojekyll must be empty, got: '$nojekyll'")
        end for
    }

    // ---- Test 6: exactly one main.js referenced (INV-008) ----

    "index.html references exactly one main.js bundle (INV-008)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(oneVersion, out, bundleDir)
            html      <- readFile(out / "index.html")
            exists    <- (out / "main.js").exists
            mapExists <- (out / "main.js.map").exists
        yield
            val scriptSrcCount = countOccurrences(html, "src=\"main.js\"")
            assert(scriptSrcCount == 1, s"expected 1 main.js script ref, got $scriptSrcCount")
            assert(exists, "main.js must be present in outDir")
            assert(mapExists, "main.js.map must be copied into outDir")
        end for
    }

    // ---- Test 7: logo and favicon assets copied ----

    "kyo.png and kyo.ico are copied into outDir" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(oneVersion, out, bundleDir)
            pngExists <- (out / "kyo.png").exists
            icoExists <- (out / "kyo.ico").exists
        yield
            assert(pngExists, "kyo.png must be present in outDir")
            assert(icoExists, "kyo.ico must be present in outDir")
        end for
    }

    // ---- Test 8: write failure is surfaced as WebsiteEmitException ----

    "write failure aborts with WebsiteEmitException" in run {
        for
            bundleDir <- stubBundleDir
            tmp <- Sync.defer {
                val d = Files.createTempDirectory("kyo-gen-fail-test")
                // Create a directory at index.html so writing a file there will fail
                Files.createDirectory(Paths.get(d.toString, "index.html"))
                Path(d.toString)
            }
            result <- Abort.run[WebsiteException](emit(oneVersion, tmp, bundleDir))
        yield
            result match
                // flow-allow: negative-path test 8; Failure(WebsiteEmitException) is the expected pass, all other arms fail()
                case Result.Failure(_: WebsiteEmitException) => succeed
                case Result.Failure(other) =>
                    fail(s"expected WebsiteEmitException, got $other")
                case Result.Success(_) =>
                    fail("expected emit to fail but it succeeded")
                case p: Result.Panic =>
                    fail(s"unexpected panic: ${p.exception}")
            end match
        end for
    }

    // ---- Test 9: idempotent re-emit produces byte-identical files ----

    "idempotent re-emit produces byte-identical files" in run {
        for
            bundleDir <- stubBundleDir
            out1      <- Sync.defer(Path(Files.createTempDirectory("kyo-gen-idem-a").toString))
            out2      <- Sync.defer(Path(Files.createTempDirectory("kyo-gen-idem-b").toString))
            _         <- emit(oneVersion, out1, bundleDir)
            _         <- emit(oneVersion, out2, bundleDir)
            html1     <- readFile(out1 / "index.html")
            html2     <- readFile(out2 / "index.html")
            json1     <- readFile(out1 / "versions.json")
            json2     <- readFile(out2 / "versions.json")
            cname1    <- readFile(out1 / "CNAME")
            cname2    <- readFile(out2 / "CNAME")
        yield
            assert(html1 == html2, "index.html must be byte-identical across two emits")
            assert(json1 == json2, "versions.json must be byte-identical across two emits")
            assert(cname1 == cname2, "CNAME must be byte-identical across two emits")
        end for
    }

    // ---- Test 10: empty content still writes root files ----

    "empty content writes artifact-root files and empty versions.json" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk.empty[WebsiteContent], out, bundleDir)
            html      <- readFile(out / "index.html")
            json      <- readFile(out / "versions.json")
            cname     <- readFile(out / "CNAME")
            nojekyll  <- readFile(out / ".nojekyll")
        yield
            assert(html.startsWith("<!DOCTYPE html>"), "index.html must be present")
            assert(json == "[]", s"empty versions.json must be [], got: $json")
            assert(cname.trim == "getkyo.io", "CNAME must be getkyo.io")
            assert(nojekyll.isEmpty, ".nojekyll must be empty")
        end for
    }

    // ---- Test 13: versions.json length equals content size (INV-010) ----

    "versions.json entry count equals input content size (INV-010)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(threeVersion, out, bundleDir)
            json      <- readFile(out / "versions.json")
        yield
            val tagCount = countOccurrences(json, "\"tag\"")
            assert(tagCount == 3, s"expected 3 entries in versions.json, got $tagCount")
        end for
    }

    // ---- Test 14: bundle href in emitted HTML ends with main.js (INV-008) ----

    "bundle href in emitted index.html ends with main.js, boot-scenario landing (INV-008)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(oneVersion, out, bundleDir)
            html      <- readFile(out / "index.html")
        yield
            assert(
                html.contains("src=\"main.js\""),
                "emitted page must reference main.js as the module script source"
            )
            assert(
                html.contains("""data-boot-scenario="landing""""),
                "boot-scenario attribute missing"
            )
        end for
    }

    // ---- Helpers ----

    private def countOccurrences(haystack: String, needle: String): Int =
        var count = 0
        var idx   = haystack.indexOf(needle)
        while idx >= 0 do
            count += 1
            idx = haystack.indexOf(needle, idx + 1)
        count
    end countOccurrences

end WebsiteGeneratorTest
