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

    // ---- Docs-route fixtures (Phase 7) ----

    private val dataReadme   = "# kyo-data\n## Overview\nData types.\n"
    private val kernelReadme = "# kyo-kernel\n## Effects\nThe effect kernel.\n"

    private val moduleData = WebsiteModule("kyo-data", "Foundation", "kyo-data", dataReadme, WebsiteModule.Platforms(true, true, true))
    private val moduleKernel =
        WebsiteModule("kyo-kernel", "Foundation", "kyo-kernel", kernelReadme, WebsiteModule.Platforms(true, true, true))

    private val vWithModules =
        WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group("Foundation", Chunk(moduleData, moduleKernel))),
            WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true)
        )

    private val vIntroOnly = WebsiteContent("old intro", Chunk.empty, WebsiteVersion("v0.9.3", "0.9.3", false))

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
                case Result.Failure(e: WebsiteEmitException) =>
                    // NOTE-3: the typed failure must carry a non-empty route so the caller can locate it.
                    assert(e.route.nonEmpty, "WebsiteEmitException.route must be non-empty")
                    assert(e.route.contains("index.html"), s"route must name the failed file, got: ${e.route}")
                    succeed
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

    // ---- Test P7-7: full route set + per-route content.md + per-version manifest.json (INV-009) ----

    "full route set + per-route content.md + per-version manifest.json (INV-009) (P7-7)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules, vIntroOnly), out, bundleDir)
            vIndex    <- (out / "v1.0.0-RC2" / "index.html").exists
            dataIndex <- (out / "v1.0.0-RC2" / "kyo-data" / "index.html").exists
            dataMd    <- readFile(out / "v1.0.0-RC2" / "kyo-data" / "content.md")
            kernelMd  <- readFile(out / "v1.0.0-RC2" / "kyo-kernel" / "content.md")
            kernelIdx <- (out / "v1.0.0-RC2" / "kyo-kernel" / "index.html").exists
            vManifest <- readFile(out / "v1.0.0-RC2" / "manifest.json")
            introIdx  <- (out / "v0.9.3" / "index.html").exists
            introMan  <- readFile(out / "v0.9.3" / "manifest.json")
            introSlug <- (out / "v0.9.3" / "kyo-data" / "index.html").exists
            latestIdx <- (out / "latest" / "index.html").exists
            latestMd  <- readFile(out / "latest" / "kyo-data" / "content.md")
            latestMan <- (out / "latest" / "manifest.json").exists
            versions  <- (out / "versions.json").exists
        yield
            assert(vIndex, "v1.0.0-RC2/index.html must exist")
            assert(dataIndex, "v1.0.0-RC2/kyo-data/index.html must exist")
            assert(dataMd == dataReadme, s"content.md must equal module.readme, got: $dataMd")
            assert(kernelMd == kernelReadme, s"kyo-kernel content.md must equal readme, got: $kernelMd")
            assert(kernelIdx, "v1.0.0-RC2/kyo-kernel/index.html must exist")
            assert(vManifest.contains("\"slug\": \"kyo-data\""), s"manifest must list kyo-data: $vManifest")
            assert(vManifest.contains("\"slug\": \"kyo-kernel\""), s"manifest must list kyo-kernel: $vManifest")
            assert(introIdx, "v0.9.3/index.html must exist")
            assert(introMan == "[]", s"intro-only manifest must be empty array, got: $introMan")
            assert(!introSlug, "v0.9.3 must have zero per-module pages")
            assert(latestIdx, "latest/index.html must exist (mirrors v1.0.0-RC2)")
            assert(latestMd == dataReadme, "latest/kyo-data/content.md must mirror the readme")
            assert(latestMan, "latest/manifest.json must exist")
            assert(versions, "versions.json must exist")
        end for
    }

    // ---- Test P7-8: intro-only version emits intro alone (INV-007 consumer) ----

    "intro-only version emits intro alone, zero per-module pages (INV-007) (P7-8)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vIntroOnly), out, bundleDir)
            introIdx  <- (out / "v0.9.3" / "index.html").exists
            slugIdx   <- (out / "v0.9.3" / "kyo-data" / "index.html").exists
            manifest  <- readFile(out / "v0.9.3" / "manifest.json")
        yield
            assert(introIdx, "v0.9.3/index.html must exist")
            assert(!slugIdx, "intro-only version must have zero per-module pages")
            assert(manifest == "[]", s"intro-only manifest must be [], got: $manifest")
        end for
    }

    // ---- Test P7-9: latest mirrors newest stable, not the RC (Q-005) ----

    "latest mirrors newest stable, not the RC (Q-005) (P7-9)" in run {
        val stableV1 = WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group(
                "Foundation",
                Chunk(
                    WebsiteModule("kyo-stable", "Foundation", "kyo-stable", "# stable\n", WebsiteModule.Platforms(true, true, true))
                )
            )),
            WebsiteVersion("v1.0.0", "1.0.0", false)
        )
        val rcV2 = WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group(
                "Foundation",
                Chunk(
                    WebsiteModule("kyo-rc", "Foundation", "kyo-rc", "# rc\n", WebsiteModule.Platforms(true, true, true))
                )
            )),
            WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", false)
        )
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            // Order is oldest-first by sort -V: stable v1.0.0 then... but RC sorts before final.
            // Provide [stableV1, rcV2]; pickLatest must choose the stable one regardless of position.
            _            <- emit(Chunk(stableV1, rcV2), out, bundleDir)
            stableMirror <- (out / "latest" / "kyo-stable" / "index.html").exists
            rcMirror     <- (out / "latest" / "kyo-rc" / "index.html").exists
        yield
            assert(stableMirror, "latest/ must mirror the stable v1.0.0 module")
            assert(!rcMirror, "latest/ must NOT mirror the RC module")
        end for
    }

    // ---- Test P7-10: latest falls back to newest pre-release (Q-005) ----

    "latest falls back to newest pre-release when no stable tag (Q-005) (P7-10)" in run {
        val rc1 = WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group(
                "Foundation",
                Chunk(
                    WebsiteModule("kyo-rc1", "Foundation", "kyo-rc1", "# rc1\n", WebsiteModule.Platforms(true, true, true))
                )
            )),
            WebsiteVersion("v1.0.0-RC1", "1.0.0-RC1", false)
        )
        val rc2 = WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group(
                "Foundation",
                Chunk(
                    WebsiteModule("kyo-rc2", "Foundation", "kyo-rc2", "# rc2\n", WebsiteModule.Platforms(true, true, true))
                )
            )),
            WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", false)
        )
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            // Oldest-first: [rc1, rc2]; newest pre-release is rc2 (the last entry).
            _    <- emit(Chunk(rc1, rc2), out, bundleDir)
            rc2m <- (out / "latest" / "kyo-rc2" / "index.html").exists
            rc1m <- (out / "latest" / "kyo-rc1" / "index.html").exists
        yield
            assert(rc2m, "latest/ must mirror rc2 (the newest pre-release)")
            assert(!rc1m, "latest/ must NOT mirror rc1")
        end for
    }

    // ---- Test P7-11: docs page embeds transpiled article AND content.md equals source (INV-005/INV-009) ----

    "docs page embeds transpiled article AND content.md equals the source (INV-005/INV-009) (P7-11)" in run {
        val readme = "# MyModule\n## Scope\nDoes things.\n```scala\nval x = 1\n```\n"
        val mod    = WebsiteModule("my-module", "Foundation", "my-module", readme, WebsiteModule.Platforms(true, true, true))
        val content =
            WebsiteContent("intro", Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(content), out, bundleDir)
            html      <- readFile(out / "v1.0.0" / "my-module" / "index.html")
            md        <- readFile(out / "v1.0.0" / "my-module" / "content.md")
        yield
            assert(html.contains("""id="mymodule""""), s"transpiled h1 anchor missing: $html")
            assert(html.contains("""id="scope""""), s"transpiled h2 anchor missing: $html")
            assert(html.contains("tok-keyword"), s"highlighted code span missing: $html")
            assert(md == readme, s"content.md must equal module.readme byte for byte, got: $md")
        end for
    }

    // ---- Test P7-12: TOC links resolve to article anchors (INV-004 e2e) ----

    "TOC links resolve to article anchors (INV-004 e2e) (P7-12)" in run {
        val readme = "# Alpha\n## Beta\nText.\n### Gamma\nMore.\n"
        val mod    = WebsiteModule("anchors", "Foundation", "anchors", readme, WebsiteModule.Platforms(true, true, true))
        val content =
            WebsiteContent("intro", Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(content), out, bundleDir)
            html      <- readFile(out / "v1.0.0" / "anchors" / "index.html")
        yield
            // Every TOC href="#slug" must have a matching id="slug" in the article.
            val hrefs = "href=\"#([a-z0-9-]+)\"".r.findAllMatchIn(html).map(_.group(1)).toSet
            assert(hrefs.nonEmpty, s"expected TOC fragment hrefs, found none: $html")
            for slug <- hrefs do
                assert(html.contains(s"""id="$slug""""), s"TOC href #$slug has no matching article id: $html")
            assert(
                hrefs.contains("alpha") && hrefs.contains("beta") && hrefs.contains("gamma"),
                s"expected alpha/beta/gamma slugs, got: $hrefs"
            )
        end for
    }

    // ---- Test NOTE-2: escJson escapes quote and backslash in versions.json ----

    "escJson escapes quote and backslash in versions.json (NOTE-2)" in run {
        val specialVersion = WebsiteContent(
            "intro",
            Chunk.empty,
            WebsiteVersion("v1.0.0", "1.0.0 \"special\" \\path", false)
        )
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(specialVersion), out, bundleDir)
            json      <- readFile(out / "versions.json")
        yield assert(
            json.contains("1.0.0 \\\"special\\\" \\\\path"),
            s"escJson must escape \" as \\\" and \\ as \\\\, got: $json"
        )
        end for
    }

    // ---- Test: boot island round-trip (emit -> parse seeds the SPA) ----

    "docs page embeds #docs-island + #versions-island that parse back to seeded content" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules), out, bundleDir)
            html      <- readFile(out / "v1.0.0-RC2" / "kyo-data" / "index.html")
        yield
            assert(html.contains("""<script type="application/json" id="docs-island">"""), s"docs-island script missing: $html")
            assert(html.contains("""<script type="application/json" id="versions-island">"""), s"versions-island script missing: $html")
            val islandJson = extractIsland(html, "docs-island")
            // The emitted island JSON, when its <-escape is reversed, carries the version, the module,
            // and the route's raw Markdown so the bundle parser (DocsClient.parseDocsIsland) seeds it.
            val decoded = islandJson.replace("\\u003c", "<").replace("\\u003e", ">")
            assert(decoded.contains("\"tag\": \"v1.0.0-RC2\""), s"island must carry the version tag: $decoded")
            assert(decoded.contains("\"slug\": \"kyo-data\""), s"island must carry the kyo-data module: $decoded")
            assert(decoded.contains("kyo-data"), "island markdown must carry the module heading")
            assert(!islandJson.contains("</script>"), "island JSON must not contain a literal </script> that closes the element")
        end for
    }

    // ---- Test WARN-1: the latest version's own v<X>/ pages link within v<X>/, not latest/ ----

    "latest version's own v<X> tree links within v<X> not latest (WARN-1 regression)" in run {
        // The single version is flagged latest=true, so emit writes it under BOTH v1.0.0/ (emitVersion)
        // and latest/ (emitLatest). The v1.0.0/ copy must link within /v1.0.0/, not /latest/.
        val readme  = "# kyo-data\n## Overview\nData types.\n"
        val readmeB = "# kyo-core\n## Effects\nCore.\n"
        val modA    = WebsiteModule("kyo-data", "Foundation", "kyo-data", readme, WebsiteModule.Platforms(true, true, true))
        val modB    = WebsiteModule("kyo-core", "Foundation", "kyo-core", readmeB, WebsiteModule.Platforms(true, true, true))
        val content = WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group("Foundation", Chunk(modA, modB))),
            WebsiteVersion("v1.0.0", "1.0.0", true)
        )
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(content), out, bundleDir)
            // The latest version's OWN versioned tree.
            vHtml <- readFile(out / "v1.0.0" / "kyo-data" / "index.html")
            // The mirrored latest tree.
            latestHtml <- readFile(out / "latest" / "kyo-data" / "index.html")
        yield
            // The v1.0.0/ page links within /v1.0.0/, never to /latest/.
            assert(vHtml.contains("/v1.0.0/kyo-data/"), s"v1.0.0 page must link within v1.0.0: $vHtml")
            assert(vHtml.contains("/v1.0.0/kyo-core/"), s"v1.0.0 page must link within v1.0.0: $vHtml")
            assert(
                !vHtml.contains("/latest/kyo-data/") && !vHtml.contains("/latest/kyo-core/"),
                s"latest version's v1.0.0 page must NOT link to /latest/: $vHtml"
            )
            // The latest/ page links within /latest/.
            assert(latestHtml.contains("/latest/kyo-data/"), s"latest page must link within latest: $latestHtml")
            assert(latestHtml.contains("/latest/kyo-core/"), s"latest page must link within latest: $latestHtml")
            assert(
                !latestHtml.contains("/v1.0.0/kyo-data/") && !latestHtml.contains("/v1.0.0/kyo-core/"),
                s"latest page must NOT link to /v1.0.0/: $latestHtml"
            )
        end for
    }

    // ---- Helpers ----

    private def extractIsland(html: String, id: String): String =
        val open  = s"""<script type="application/json" id="$id">"""
        val start = html.indexOf(open)
        val from  = start + open.length
        val end   = html.indexOf("</script>", from)
        html.substring(from, end)
    end extractIsland

    private def countOccurrences(haystack: String, needle: String): Int =
        var count = 0
        var idx   = haystack.indexOf(needle)
        while idx >= 0 do
            count += 1
            idx = haystack.indexOf(needle, idx + 1)
        count
    end countOccurrences

end WebsiteGeneratorTest
