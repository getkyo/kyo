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
        val start = Paths.get(java.lang.System.getProperty("user.dir")).toAbsolutePath
        Iterator
            .iterate(start)(_.getParent)
            .takeWhile(_ != null)
            .find(dir => Files.exists(dir.resolve("build.sbt")))
            .getOrElse(throw new RuntimeException("repo root not found"))
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

    "kyo.svg, kyo.png and kyo.ico are copied into outDir" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(oneVersion, out, bundleDir)
            svgExists <- (out / "kyo.svg").exists
            pngExists <- (out / "kyo.png").exists
            icoExists <- (out / "kyo.ico").exists
        yield
            assert(svgExists, "kyo.svg (the vector brand mark) must be present in outDir")
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
                // negative-path test 8; Failure(WebsiteEmitException) is the expected pass, all other arms fail()
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

    "bundle href in emitted index.html ends with main.js (INV-008)" in run {
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
            // G3: the data-boot-scenario wrapper is removed; the page root is the SiteApp div directly.
            assert(
                !html.contains("data-boot-scenario"),
                "G3: the boot-scenario wrapper must be gone from the emitted page"
            )
        end for
    }

    // ---- Test D4: the landing page carries the unified header + islands ----

    "landing index.html carries the unified SiteApp header and #docs-island + #versions-island (D4)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules), out, bundleDir)
            html      <- readFile(out / "index.html")
        yield
            // The unified header is now on the landing page too.
            assert(html.contains("class=\"site-header\""), s"landing must carry the unified site-header: $html")
            assert(html.contains("search-input"), s"landing header must carry the search input: $html")
            // D4: the islands now ship on `/` (today emitLanding skipped them), so the bundle can
            // hydrate `/` and the search/version dropdown have their seed data.
            assert(
                html.contains("""<script type="application/json" id="docs-island">"""),
                s"landing must carry #docs-island (D4): $html"
            )
            assert(
                html.contains("""<script type="application/json" id="versions-island">"""),
                s"landing must carry #versions-island (D4): $html"
            )
            // The landing content body is still present under the header.
            assert(html.contains("data-section=\"hero\""), s"landing hero must still be present: $html")
        end for
    }

    // ---- SEO-2: the unified shell keeps a route-specific <head> per route ----

    "route-specific <head>: /, /latest/, and a module route differ and each matches its own route (SEO-2)" in run {
        for
            out         <- tmpDir
            bundleDir   <- stubBundleDir
            _           <- emit(Chunk(vWithModules), out, bundleDir)
            landingHtml <- readFile(out / "index.html")
            introHtml   <- readFile(out / "latest" / "index.html")
            moduleHtml  <- readFile(out / "latest" / "kyo-data" / "index.html")
        yield
            val landingTitle = headField(landingHtml, "title")
            val introTitle   = headField(introHtml, "title")
            val moduleTitle  = headField(moduleHtml, "title")
            // The three titles are route-specific and all differ (the shell did NOT flatten the head).
            assert(landingTitle.contains("Build with AI"), s"landing title must be the landing title, got: $landingTitle")
            assert(introTitle.contains("Kyo docs"), s"intro title must be the docs title, got: $introTitle")
            assert(moduleTitle.contains("kyo-data"), s"module title must name the module, got: $moduleTitle")
            assert(landingTitle != introTitle, "landing and intro titles must differ")
            assert(introTitle != moduleTitle, "intro and module titles must differ")
            assert(landingTitle != moduleTitle, "landing and module titles must differ")
            // Each page's canonical link is its own route.
            assert(canonical(landingHtml) == "https://getkyo.io/", s"landing canonical wrong: ${canonical(landingHtml)}")
            assert(canonical(introHtml) == "https://getkyo.io/latest/", s"intro canonical wrong: ${canonical(introHtml)}")
            assert(
                canonical(moduleHtml) == "https://getkyo.io/latest/kyo-data/",
                s"module canonical wrong: ${canonical(moduleHtml)}"
            )
            // og:url mirrors the canonical per route.
            assert(moduleHtml.contains("https://getkyo.io/latest/kyo-data/"), "module og:url must match its route")
        end for
    }

    // ---- SEO-1: the full transpiled article prose ships in the raw module HTML ----

    "module index.html ships the full transpiled article prose in raw HTML (SEO-1)" in run {
        // The fixture README carries a distinctive prose sentence; the SSG must ship its transpiled
        // text in the page HTML (not a JS-hydrated stub) so non-JS crawlers index it.
        val prose  = "Channels carry values between fibers without blocking a thread."
        val readme = s"# kyo-distinct\n## Overview\n$prose\n"
        val mod    = WebsiteModule("kyo-distinct", "Foundation", "kyo-distinct", readme, WebsiteModule.Platforms(true, true, true))
        val content =
            WebsiteContent("intro", Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(content), out, bundleDir)
            html      <- readFile(out / "latest" / "kyo-distinct" / "index.html")
        yield
            assert(html.contains(prose), s"the full article prose must be present in the raw module HTML (SEO-1): $html")
            assert(html.contains("""id="overview""""), s"the transpiled heading anchor must be present (SEO-1): $html")
        end for
    }

    // ---- Test P7-7: full route set + per-route content.md + per-version manifest.json (INV-009) ----

    "full route set + per-route content.md + per-version manifest.json (INV-009) (P7-7)" in run {
        for
            out           <- tmpDir
            bundleDir     <- stubBundleDir
            _             <- emit(Chunk(vWithModules, vIntroOnly), out, bundleDir)
            vIndex        <- (out / "v1.0.0-RC2" / "index.html").exists
            dataIndex     <- (out / "v1.0.0-RC2" / "kyo-data" / "index.html").exists
            dataMd        <- readFile(out / "v1.0.0-RC2" / "kyo-data" / "content.md")
            kernelMd      <- readFile(out / "v1.0.0-RC2" / "kyo-kernel" / "content.md")
            kernelIdx     <- (out / "v1.0.0-RC2" / "kyo-kernel" / "index.html").exists
            vManifest     <- readFile(out / "v1.0.0-RC2" / "manifest.json")
            vIntroMd      <- readFile(out / "v1.0.0-RC2" / "content.md")
            introIdx      <- (out / "v0.9.3" / "index.html").exists
            introMan      <- readFile(out / "v0.9.3" / "manifest.json")
            introSlug     <- (out / "v0.9.3" / "kyo-data" / "index.html").exists
            introIntroMd  <- readFile(out / "v0.9.3" / "content.md")
            latestIdx     <- (out / "latest" / "index.html").exists
            latestMd      <- readFile(out / "latest" / "kyo-data" / "content.md")
            latestIntroMd <- readFile(out / "latest" / "content.md")
            latestMan     <- (out / "latest" / "manifest.json").exists
            versions      <- (out / "versions.json").exists
        yield
            assert(vIndex, "v1.0.0-RC2/index.html must exist")
            assert(dataIndex, "v1.0.0-RC2/kyo-data/index.html must exist")
            assert(dataMd == dataReadme, s"content.md must equal module.readme, got: $dataMd")
            assert(kernelMd == kernelReadme, s"kyo-kernel content.md must equal readme, got: $kernelMd")
            assert(kernelIdx, "v1.0.0-RC2/kyo-kernel/index.html must exist")
            assert(vManifest.contains("\"slug\": \"kyo-data\""), s"manifest must list kyo-data: $vManifest")
            assert(vManifest.contains("\"slug\": \"kyo-kernel\""), s"manifest must list kyo-kernel: $vManifest")
            // The intro route now writes content.md == content.intro (the root-README overview source).
            assert(vIntroMd == "intro", s"v1.0.0-RC2/content.md must equal content.intro, got: $vIntroMd")
            assert(introIdx, "v0.9.3/index.html must exist")
            assert(introMan == "[]", s"intro-only manifest must be empty array, got: $introMan")
            assert(!introSlug, "v0.9.3 must have zero per-module pages")
            assert(introIntroMd == "old intro", s"v0.9.3/content.md must equal its intro, got: $introIntroMd")
            assert(latestIdx, "latest/index.html must exist (mirrors v1.0.0-RC2)")
            assert(latestMd == dataReadme, "latest/kyo-data/content.md must mirror the readme")
            assert(latestIntroMd == "intro", s"latest/content.md must equal the overview intro, got: $latestIntroMd")
            assert(latestMan, "latest/manifest.json must exist")
            assert(versions, "versions.json must exist")
        end for
    }

    // ---- The intro route renders the root-README overview as a real article (not an empty one) ----

    "intro route renders the transpiled overview article, not an empty article (overview-as-home)" in run {
        // A distinctive prose sentence + a couple of `## ` sections in the intro: the SSG must ship the
        // transpiled overview text AND its level-2 section anchors in the intro page HTML, and write the
        // raw intro as content.md.
        val prose = "Kyo is a Scala 3 toolkit for building applications across platforms."
        val intro = s"## Introduction\n$prose\n## Coming from ZIO\nNotes.\n"
        val mod   = WebsiteModule("kyo-core", "Foundation", "kyo-core", "# kyo-core\nCore.\n", WebsiteModule.Platforms(true, true, true))
        val content =
            WebsiteContent(intro, Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(content), out, bundleDir)
            introHtml <- readFile(out / "latest" / "index.html")
            introMd   <- readFile(out / "latest" / "content.md")
        yield
            // The overview prose ships in the raw intro HTML (real article, not empty + a hint).
            assert(introHtml.contains(prose), s"intro HTML must ship the transpiled overview prose: $introHtml")
            // The intro's level-2 sections carry article anchors.
            assert(introHtml.contains("""id="introduction""""), s"intro must carry the Introduction h2 anchor: $introHtml")
            assert(introHtml.contains("""id="coming-from-zio""""), s"intro must carry the Coming-from-ZIO h2 anchor: $introHtml")
            // The rail's Overview item is the active expanded item with its #slug sections.
            assert(introHtml.contains("#introduction"), s"intro rail must list the #introduction section: $introHtml")
            assert(introHtml.contains("nav-item-active"), s"intro rail Overview must be the active item: $introHtml")
            // The old empty-article docs-home hint is gone.
            assert(!introHtml.contains("docs-home-hint"), s"the old docs-home hint must be gone: $introHtml")
            assert(!introHtml.contains("Pick a module from the sidebar"), s"the old pick-a-module hint text must be gone: $introHtml")
            // content.md is the raw intro source.
            assert(introMd == intro, s"intro content.md must equal content.intro, got: $introMd")
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

    // ---- Test P7-10b: stable wins over a NEWER pre-release (pickLatest stability consolidation) ----

    "latest mirrors an older stable over a newer pre-release (stability via WebsiteVersion.parse) (P7-10b)" in run {
        // pickLatest's stable filter is `WebsiteVersion.parse(tag).exists(_.preRelease.isEmpty)` (one
        // shared stability definition, not a substring-marker list). Here v1.0.1-RC1 is a NEWER triple
        // than the stable v1.0.0 but carries a pre-release suffix, so the stable v1.0.0 must still be
        // chosen as latest. This locks the consolidated semantics: a higher version number does not win
        // when it is a pre-release and a stable release exists.
        val stable100 = WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group(
                "Foundation",
                Chunk(WebsiteModule(
                    "kyo-stable100",
                    "Foundation",
                    "kyo-stable100",
                    "# stable\n",
                    WebsiteModule.Platforms(true, true, true)
                ))
            )),
            WebsiteVersion("v1.0.0", "1.0.0", false)
        )
        val rc101 = WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group(
                "Foundation",
                Chunk(WebsiteModule("kyo-rc101", "Foundation", "kyo-rc101", "# rc\n", WebsiteModule.Platforms(true, true, true)))
            )),
            WebsiteVersion("v1.0.1-RC1", "1.0.1-RC1", false)
        )
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            // Oldest-first by semantic order: stable v1.0.0 sorts before the v1.0.1-RC1 pre-release.
            _            <- emit(Chunk(stable100, rc101), out, bundleDir)
            stableMirror <- (out / "latest" / "kyo-stable100" / "index.html").exists
            rcMirror     <- (out / "latest" / "kyo-rc101" / "index.html").exists
        yield
            assert(stableMirror, "latest/ must mirror the stable v1.0.0 module, not the newer pre-release")
            assert(!rcMirror, "latest/ must NOT mirror the v1.0.1-RC1 pre-release module")
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

    // ---- Test P7-12: rail section links resolve to article anchors (INV-004 e2e) ----

    "rail section links resolve to article anchors (INV-004 e2e) (P7-12)" in run {
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
            // Every in-rail section href="#slug" must have a matching id="slug" in the article.
            val hrefs = "href=\"#([a-z0-9-]+)\"".r.findAllMatchIn(html).map(_.group(1)).toSet
            assert(hrefs.nonEmpty, s"expected rail section fragment hrefs, found none: $html")
            for slug <- hrefs do
                assert(html.contains(s"""id="$slug""""), s"section href #$slug has no matching article id: $html")
            // The one-level rail lists ONLY level-2 (`## `) sections: beta is the lone rail section. The
            // level-1 page title (alpha) is the module link, and the level-3 heading (gamma) is dropped
            // from the rail, even though the article still carries both their id anchors (so any external
            // or deep link to them still resolves).
            assert(hrefs.contains("beta"), s"expected the level-2 beta section slug in the rail, got: $hrefs")
            assert(!hrefs.contains("gamma"), s"the level-3 gamma heading must NOT be a rail section link, got: $hrefs")
            assert(!hrefs.contains("alpha"), s"the level-1 page title (alpha) must not be a section link, got: $hrefs")
            assert(html.contains("""id="alpha""""), s"the article must still carry the h1 id=alpha anchor: $html")
            assert(html.contains("""id="gamma""""), s"the article must still carry the level-3 id=gamma anchor: $html")
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

    // ---- Test AF-11: escJson escapes control chars (newline, tab, CR, SOH) so JSON-LD stays valid ----

    "escJson escapes control chars (newline, tab, carriage-return, SOH 0x01) in JSON-LD (AF-11)" in run {
        // Inject a label with embedded control characters into the versions.json pipeline.
        // The label value uses Scala escape sequences so scalac embeds the actual control chars:
        // \n = 0x0A (LF), \t = 0x09 (tab), \r = 0x0D (CR), \u0001 = 0x01 (SOH).
        val ctrlVersion = WebsiteContent(
            "intro",
            Chunk.empty,
            WebsiteVersion("v1.0.0", "line1\nline2\ttabbed\rCR\u0001raw", false)
        )
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(ctrlVersion), out, bundleDir)
            json      <- readFile(out / "versions.json")
        yield
            // Extract the label value by finding the "label": " key and reading until the next
            // unescaped closing quote. This avoids confusion with the structural newlines in
            // the surrounding JSON array formatting.
            val labelKey = "\"label\": \""
            val lk       = json.indexOf(labelKey)
            assert(lk >= 0, s"label key not found in versions.json: $json")
            val vs = lk + labelKey.length
            // Scan forward until the closing unescaped double-quote.
            var i     = vs
            var label = ""
            while i < json.length && json.charAt(i) != '"' do
                i += 1
            label = json.substring(vs, i)
            assert(label.contains("\\n"), s"LF must be escaped as \\n in label: $label")
            assert(label.contains("\\t"), s"tab must be escaped as \\t in label: $label")
            assert(label.contains("\\r"), s"CR must be escaped as \\r in label: $label")
            assert(label.contains("\\u0001"), s"SOH must be escaped as \\u0001 in label: $label")
            assert(!label.contains("\n"), s"literal LF must not appear in label value: $label")
            assert(!label.contains("\t"), s"literal tab must not appear in label value: $label")
            assert(!label.contains("\r"), s"literal CR must not appear in label value: $label")
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
            // The v1.0.0/ page's BODY links within /v1.0.0/, never to /latest/. The check is scoped to
            // the <body> because the current-latest version's <head> now carries the DECISION-SEO-A
            // canonical -> /latest/kyo-data/ (this version IS the latest, so its versioned tree
            // canonicalizes to the /latest/ home); that intentional head tag is not a body nav link.
            val vBody = vHtml.substring(vHtml.indexOf("<body>"), vHtml.indexOf("</body>"))
            assert(vBody.contains("/v1.0.0/kyo-data/"), s"v1.0.0 page body must link within v1.0.0: $vBody")
            assert(vBody.contains("/v1.0.0/kyo-core/"), s"v1.0.0 page body must link within v1.0.0: $vBody")
            assert(
                !vBody.contains("/latest/kyo-data/") && !vBody.contains("/latest/kyo-core/"),
                s"latest version's v1.0.0 page body must NOT link to /latest/: $vBody"
            )
            // The head canonical IS the /latest/ dedup target (DECISION-SEO-A), distinct from body nav.
            assert(
                canonical(vHtml) == "https://getkyo.io/latest/kyo-data/",
                s"v1.0.0 canonical must dedup to /latest/: ${canonical(vHtml)}"
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

    // ---- SEO-3: sitemap.xml lists /, the /latest/ overview, and every latest slug; excludes versioned URLs ----

    "sitemap.xml lists /, the /latest/ overview, and every latest slug; no /v<X>/ or asset URLs (SEO-3)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules, vIntroOnly), out, bundleDir)
            sitemap   <- readFile(out / "sitemap.xml")
            // Cross-check the <loc> count against the actual emitted latest/<slug>/ pages + 2 (the root
            // and the /latest/ overview).
            latestSlugPages <- Sync.defer {
                val latestDir = java.nio.file.Paths.get(out.toString, "latest")
                if Files.exists(latestDir) then
                    Files.list(latestDir).filter(Files.isDirectory(_)).count().toInt
                else 0
            }
        yield
            assert(sitemap.startsWith("<?xml"), s"sitemap must be valid XML: $sitemap")
            assert(sitemap.contains("<urlset"), s"sitemap must contain a <urlset>: $sitemap")
            // Included: the root, the /latest/ overview, and each /latest/<slug>/.
            assert(sitemap.contains("<loc>https://getkyo.io/</loc>"), s"sitemap must list the root: $sitemap")
            assert(sitemap.contains("<loc>https://getkyo.io/latest/</loc>"), s"sitemap must list the /latest/ overview: $sitemap")
            assert(sitemap.contains("<loc>https://getkyo.io/latest/kyo-data/</loc>"), s"sitemap must list kyo-data: $sitemap")
            assert(sitemap.contains("<loc>https://getkyo.io/latest/kyo-kernel/</loc>"), s"sitemap must list kyo-kernel: $sitemap")
            // Excluded: the current-latest versioned tree, the historical version, and non-pages.
            assert(!sitemap.contains("/v1.0.0-RC2/"), s"sitemap must NOT list the versioned tree: $sitemap")
            assert(!sitemap.contains("/v0.9.3/"), s"sitemap must NOT list the old version: $sitemap")
            assert(!sitemap.contains("content.md"), s"sitemap must NOT list content.md: $sitemap")
            assert(!sitemap.contains("manifest.json"), s"sitemap must NOT list manifest.json: $sitemap")
            assert(!sitemap.contains("versions.json"), s"sitemap must NOT list versions.json: $sitemap")
            // Every <url> block carries a non-empty <lastmod>.
            val locCount     = countOccurrences(sitemap, "<loc>")
            val lastmodCount = countOccurrences(sitemap, "<lastmod>")
            assert(locCount == lastmodCount, s"every <loc> must have a <lastmod>, got $locCount loc / $lastmodCount lastmod")
            assert(!sitemap.contains("<lastmod></lastmod>"), s"<lastmod> must be non-empty: $sitemap")
            // Cross-check: <loc> count == count of latest/<slug>/ dirs + 2 (the root / and the /latest/ overview).
            assert(
                locCount == latestSlugPages + 2,
                s"sitemap <loc> count ($locCount) must equal latest slug pages ($latestSlugPages) + 2 (root + overview)"
            )
        end for
    }

    // ---- SEO-3: robots.txt allows all and points to the sitemap ----

    "robots.txt allows all crawlers and points to sitemap, blocks no AI crawler (SEO-3)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules), out, bundleDir)
            robots    <- readFile(out / "robots.txt")
        yield
            assert(robots.contains("User-agent: *"), s"robots must allow all user-agents: $robots")
            assert(robots.contains("Allow: /"), s"robots must allow crawling: $robots")
            assert(robots.contains("Sitemap: https://getkyo.io/sitemap.xml"), s"robots must declare the sitemap: $robots")
            assert(!robots.contains("Disallow"), s"robots must NOT block any path (AI crawlers need raw HTML): $robots")
            assert(!robots.contains("GPTBot"), s"robots must NOT name-block GPTBot: $robots")
            assert(!robots.contains("ClaudeBot"), s"robots must NOT name-block ClaudeBot: $robots")
        end for
    }

    // ---- SEO-3: sitemap.xml and robots.txt are always written, even for empty content ----

    "sitemap.xml and robots.txt always written, even for empty content (SEO-3)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk.empty[WebsiteContent], out, bundleDir)
            sitemap   <- readFile(out / "sitemap.xml")
            robots    <- readFile(out / "robots.txt")
        yield
            // Empty content: pickLatest is Absent, so the sitemap lists only the always-indexable root.
            assert(sitemap.contains("<loc>https://getkyo.io/</loc>"), s"empty sitemap must still list the root: $sitemap")
            assert(countOccurrences(sitemap, "<loc>") == 1, s"empty sitemap must have exactly one <loc> (the root): $sitemap")
            assert(robots.contains("Sitemap: https://getkyo.io/sitemap.xml"), s"robots must declare the sitemap: $robots")
        end for
    }

    // ---- SEO-5: module page head carries TechArticle JSON-LD ----

    "module page head has TechArticle JSON-LD with the correct self url (SEO-5)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules), out, bundleDir)
            html      <- readFile(out / "latest" / "kyo-data" / "index.html")
        yield
            assert(html.contains("""<script type="application/ld+json">"""), s"module head must carry a JSON-LD block: $html")
            val ld = extractJsonLd(html)
            assert(ld.contains(""""@type": "TechArticle""""), s"module JSON-LD must be a TechArticle: $ld")
            assert(ld.contains("https://getkyo.io/latest/kyo-data/"), s"module JSON-LD url must be the self route: $ld")
            assert(!ld.contains("</script>"), s"JSON-LD must not contain a literal </script>: $ld")
            // The JSON-LD block must be inside the <head>.
            val ldIdx   = html.indexOf("application/ld+json")
            val headEnd = html.indexOf("</head>")
            assert(ldIdx >= 0 && ldIdx < headEnd, "JSON-LD must be inside the <head>")
        end for
    }

    // ---- SEO-5: landing page head carries WebSite + SoftwareSourceCode JSON-LD ----

    "landing page head has WebSite + SoftwareSourceCode JSON-LD (SEO-5)" in run {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules), out, bundleDir)
            html      <- readFile(out / "index.html")
        yield
            assert(html.contains("""<script type="application/ld+json">"""), s"landing head must carry a JSON-LD block: $html")
            val ld = extractJsonLd(html)
            assert(ld.contains("WebSite"), s"landing JSON-LD must declare a WebSite: $ld")
            assert(ld.contains("SoftwareSourceCode"), s"landing JSON-LD must declare a SoftwareSourceCode: $ld")
            assert(ld.contains("https://github.com/getkyo/kyo"), s"landing JSON-LD must carry the repo url: $ld")
            assert(!ld.contains("</script>"), s"JSON-LD must not contain a literal </script>: $ld")
        end for
    }

    // ---- DECISION-SEO-A/B: canonical dedup + intro now indexable ----

    "current-latest /v<X>/ module + intro canonicalize to /latest/; /latest/ intro is indexable (DECISION-SEO-A/B)" in run {
        for
            out            <- tmpDir
            bundleDir      <- stubBundleDir
            _              <- emit(Chunk(vWithModules), out, bundleDir)
            versionedHtml  <- readFile(out / "v1.0.0-RC2" / "kyo-data" / "index.html")
            versionedIntro <- readFile(out / "v1.0.0-RC2" / "index.html")
            latestHtml     <- readFile(out / "latest" / "kyo-data" / "index.html")
            latestIntro    <- readFile(out / "latest" / "index.html")
        yield
            // DECISION-SEO-A: the current-latest versioned page points to /latest/, not self.
            assert(
                canonical(versionedHtml) == "https://getkyo.io/latest/kyo-data/",
                s"versioned page canonical must be /latest/, got: ${canonical(versionedHtml)}"
            )
            // The /latest/ page is self-canonical.
            assert(
                canonical(latestHtml) == "https://getkyo.io/latest/kyo-data/",
                s"latest page must be self-canonical, got: ${canonical(latestHtml)}"
            )
            // DECISION-SEO-A (intro): the current-latest /v<X>/ intro canonicalizes to /latest/.
            assert(
                canonical(versionedIntro) == "https://getkyo.io/latest/",
                s"versioned intro canonical must dedup to /latest/, got: ${canonical(versionedIntro)}"
            )
            // DECISION-SEO-B (new): the /latest/ intro is the overview (real content), self-canonical
            // and INDEXABLE (no noindex). Module pages and the intro are both indexable now.
            assert(
                canonical(latestIntro) == "https://getkyo.io/latest/",
                s"latest intro must be self-canonical, got: ${canonical(latestIntro)}"
            )
            assert(
                !latestIntro.contains("""content="noindex""""),
                s"the /latest/ intro must NOT be noindex (it is now the overview content): $latestIntro"
            )
            assert(
                !versionedIntro.contains("""content="noindex""""),
                s"a versioned intro must NOT be noindex: $versionedIntro"
            )
            assert(
                !versionedHtml.contains("""content="noindex""""),
                s"a module page must NOT be noindex: $versionedHtml"
            )
        end for
    }

    // ---- SEO-4 golden: SSG title/canonical match updateHead strings for each route kind ----

    "SEO-4 golden: SSG title/canonical match updateHead strings for each route kind" in run {
        // These are the EXACT strings WebsiteBundleMain.updateHead emits for the same route kinds; the
        // cross-check pins the SSG and bundle head strings together so they cannot drift silently.
        for
            out         <- tmpDir
            bundleDir   <- stubBundleDir
            _           <- emit(Chunk(vWithModules), out, bundleDir)
            landingHtml <- readFile(out / "index.html")
            introHtml   <- readFile(out / "latest" / "index.html")
            moduleHtml  <- readFile(out / "latest" / "kyo-data" / "index.html")
        yield
            assert(
                headField(landingHtml, "title") == "Kyo | Build with AI. Ship something that holds.",
                s"landing title mismatch: ${headField(landingHtml, "title")}"
            )
            assert(canonical(landingHtml) == "https://getkyo.io/", s"landing canonical mismatch: ${canonical(landingHtml)}")
            assert(
                headField(introHtml, "title") == "Overview | Kyo docs 1.0.0-RC2",
                s"intro title mismatch: ${headField(introHtml, "title")}"
            )
            assert(canonical(introHtml) == "https://getkyo.io/latest/", s"intro canonical mismatch: ${canonical(introHtml)}")
            assert(
                headField(moduleHtml, "title") == "kyo-data | Kyo docs 1.0.0-RC2",
                s"module title mismatch: ${headField(moduleHtml, "title")}"
            )
            assert(
                canonical(moduleHtml) == "https://getkyo.io/latest/kyo-data/",
                s"module canonical mismatch: ${canonical(moduleHtml)}"
            )
        end for
    }

    // ---- Helpers ----

    /** The JSON text inside the first `<script type="application/ld+json">...</script>` block. */
    private def extractJsonLd(html: String): String =
        val open  = """<script type="application/ld+json">"""
        val start = html.indexOf(open)
        if start < 0 then ""
        else
            val from = start + open.length
            val end  = html.indexOf("</script>", from)
            if end < 0 then "" else html.substring(from, end)
        end if
    end extractJsonLd

    private def extractIsland(html: String, id: String): String =
        val open  = s"""<script type="application/json" id="$id">"""
        val start = html.indexOf(open)
        val from  = start + open.length
        val end   = html.indexOf("</script>", from)
        html.substring(from, end)
    end extractIsland

    /** The text content of a `<tag>...</tag>` element in the document head (e.g. `<title>`). */
    private def headField(html: String, tag: String): String =
        val open  = s"<$tag>"
        val start = html.indexOf(open)
        if start < 0 then ""
        else
            val from = start + open.length
            val end  = html.indexOf(s"</$tag>", from)
            if end < 0 then "" else html.substring(from, end)
        end if
    end headField

    /** The href of the `<link rel="canonical">` element (SEO-2). */
    private def canonical(html: String): String =
        val marker = "<link rel=\"canonical\" href=\""
        val start  = html.indexOf(marker)
        if start < 0 then ""
        else
            val from = start + marker.length
            val end  = html.indexOf('"', from)
            if end < 0 then "" else html.substring(from, end)
        end if
    end canonical

    private def countOccurrences(haystack: String, needle: String): Int =
        var count = 0
        var idx   = haystack.indexOf(needle)
        while idx >= 0 do
            count += 1
            idx = haystack.indexOf(needle, idx + 1)
        count
    end countOccurrences

end WebsiteGeneratorTest
