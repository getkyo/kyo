package kyo.website

import kyo.*

/** Tests for `WebsiteGenerator.emit`.
  *
  * Each test emits into a fresh temp directory. Tests assert concrete file contents, not just
  * existence. No git commits; output directories are ephemeral.
  */
class WebsiteGeneratorTest extends WebsiteTest:

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

    // ---- Docs-route fixtures ----

    private val dataReadme   = "# kyo-data\n## Overview\nData types.\n"
    private val kernelReadme = "# kyo-kernel\n## Effects\nThe effect kernel.\n"

    private val moduleData =
        WebsiteModule("kyo-data", "Foundation", "kyo-data", dataReadme, WebsiteModule.Platforms(true, true, true, true))
    private val moduleKernel =
        WebsiteModule("kyo-kernel", "Foundation", "kyo-kernel", kernelReadme, WebsiteModule.Platforms(true, true, true, true))

    private val vWithModules =
        WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group("Foundation", Chunk(moduleData, moduleKernel))),
            WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true)
        )

    private val vIntroOnly = WebsiteContent("old intro", Chunk.empty, WebsiteVersion("v0.9.3", "0.9.3", false))

    // ---- Helpers ----

    private def deleteDir(d: java.nio.file.Path): Unit =
        if java.nio.file.Files.exists(d) then
            scala.util.Using.resource(java.nio.file.Files.walk(d)) { stream =>
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p => java.nio.file.Files.delete(p))
            }

    private def tmpDir(using Frame): Path < (Sync & Scope) =
        Scope.acquireRelease(
            Sync.defer(java.nio.file.Files.createTempDirectory("kyo-gen-test"))
        )(d => Sync.defer(deleteDir(d))).map(d => Path(d.toString))

    private def stubBundleDir(using Frame): Path < (Sync & Scope) =
        Scope.acquireRelease(
            Sync.defer {
                val d = java.nio.file.Files.createTempDirectory("kyo-bundle-stub")
                java.nio.file.Files.writeString(d.resolve("main.js"), "// stub")
                java.nio.file.Files.writeString(d.resolve("main.js.map"), "{}")
                d
            }
        )(d => Sync.defer(deleteDir(d))).map(d => Path(d.toString))

    private def repoRoot(using Frame): Path =
        import AllowUnsafe.embrace.danger
        @scala.annotation.tailrec
        def loop(dir: Path): Path =
            if (dir / "build.sbt").unsafe.exists() then dir
            else
                dir.parent match
                    case Maybe.Present(parent) => loop(parent)
                    case Maybe.Absent          => throw new RuntimeException("repo root not found")
        loop(Path(java.lang.System.getProperty("user.dir").nn))
    end repoRoot

    private def emit(
        content: Chunk[WebsiteContent],
        outDir: Path,
        bundleDir: Path
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        WebsiteGenerator.emit(content, outDir, WebsiteGenerator.Config(repoRoot, bundleDir))

    private def readFile(path: Path)(using Frame): String < (Sync & Abort[WebsiteException]) =
        Abort.run[FileException](Path.runReadOnly(path.read)).map {
            case Result.Success(s) => s
            case Result.Failure(e) => Abort.fail(WebsiteEmitException(path.toString, e))
            case p: Result.Panic   => Abort.error(p)
        }

    private def fileExists(path: Path)(using Frame): Boolean < (Sync & Abort[WebsiteException]) =
        Abort.run[FileException](Path.runReadOnly(path.exists)).map {
            case Result.Success(b) => b
            case Result.Failure(e) => Abort.fail(WebsiteEmitException(path.toString, e))
            case p: Result.Panic   => Abort.error(p)
        }

    // ---- Test 1: index.html is a complete document ----

    "index.html is a complete HTML document" in {
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

    // ---- Test 2: index.html contains landing content ----

    "index.html contains landing page content" in {
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
            // The emitted CSS is the rendered WebsiteStyles.sheet, not a raw blob.
            // .feat-grid is a marker selector produced by WebsiteStyles.sheet.render.
            val styleStart = html.indexOf("<style>")
            val styleEnd   = html.indexOf("</style>")
            assert(styleStart >= 0 && styleEnd > styleStart, "head <style> block missing")
            val style      = html.substring(styleStart, styleEnd)
            val baseCssIdx = style.indexOf(UI.baseCss.take(30))
            val sheetIdx   = style.indexOf(".feat-grid {")
            assert(baseCssIdx >= 0, "baseCss reset missing from <style>")
            assert(sheetIdx >= 0, "WebsiteStyles.sheet marker (.feat-grid) missing from <style>")
            // baseCss must appear strictly before the rendered sheet so site rules win.
            assert(baseCssIdx < sheetIdx, "baseCss must precede the WebsiteStyles.sheet rules")
        end for
    }

    // ---- Test 3: versions.json has correct shape and length ----

    "versions.json has correct shape with 3 versions" in {
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

    // ---- Test 4: CNAME exact content ----

    "CNAME content is exactly getkyo.io" in {
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

    // ---- Test 5: .nojekyll exists and is empty ----

    ".nojekyll exists at root and is empty" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(oneVersion, out, bundleDir)
            nojekyll  <- readFile(out / ".nojekyll")
        yield assert(nojekyll.isEmpty, s".nojekyll must be empty, got: '$nojekyll'")
        end for
    }

    // ---- Test 6: exactly one main.js referenced ----

    "index.html references exactly one main.js bundle" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(oneVersion, out, bundleDir)
            html      <- readFile(out / "index.html")
            exists    <- fileExists(out / "main.js")
            mapExists <- fileExists(out / "main.js.map")
        yield
            val scriptSrcCount = countOccurrences(html, "src=\"main.js\"")
            assert(scriptSrcCount == 1, s"expected 1 main.js script ref, got $scriptSrcCount")
            assert(exists, "main.js must be present in outDir")
            assert(mapExists, "main.js.map must be copied into outDir")
        end for
    }

    // ---- Test 7: logo and favicon assets copied ----

    "kyo.svg, kyo.png and kyo.ico are copied into outDir" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(oneVersion, out, bundleDir)
            svgExists <- fileExists(out / "kyo.svg")
            pngExists <- fileExists(out / "kyo.png")
            icoExists <- fileExists(out / "kyo.ico")
        yield
            assert(svgExists, "kyo.svg (the vector brand mark) must be present in outDir")
            assert(pngExists, "kyo.png must be present in outDir")
            assert(icoExists, "kyo.ico must be present in outDir")
        end for
    }

    // ---- Test 8: write failure is surfaced as WebsiteEmitException ----

    "write failure aborts with WebsiteEmitException" in {
        for
            bundleDir <- stubBundleDir
            tmp <- Scope.acquireRelease(
                Sync.defer {
                    val d = java.nio.file.Files.createTempDirectory("kyo-gen-fail-test")
                    // Create a directory at index.html so writing a file there will fail
                    java.nio.file.Files.createDirectory(d.resolve("index.html"))
                    d
                }
            )(d => Sync.defer(deleteDir(d))).map(d => Path(d.toString))
            result <- Abort.run[WebsiteException](emit(oneVersion, tmp, bundleDir))
        yield
            result match
                // Failure(WebsiteEmitException) is the expected pass, all other arms fail()
                case Result.Failure(e: WebsiteEmitException) =>
                    // The typed failure must carry a non-empty route so the caller can locate it.
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

    "idempotent re-emit produces byte-identical files" in {
        for
            bundleDir <- stubBundleDir
            out1 <- Scope.acquireRelease(
                Sync.defer(java.nio.file.Files.createTempDirectory("kyo-gen-idem-a"))
            )(d => Sync.defer(deleteDir(d))).map(d => Path(d.toString))
            out2 <- Scope.acquireRelease(
                Sync.defer(java.nio.file.Files.createTempDirectory("kyo-gen-idem-b"))
            )(d => Sync.defer(deleteDir(d))).map(d => Path(d.toString))
            _      <- emit(oneVersion, out1, bundleDir)
            _      <- emit(oneVersion, out2, bundleDir)
            html1  <- readFile(out1 / "index.html")
            html2  <- readFile(out2 / "index.html")
            json1  <- readFile(out1 / "versions.json")
            json2  <- readFile(out2 / "versions.json")
            cname1 <- readFile(out1 / "CNAME")
            cname2 <- readFile(out2 / "CNAME")
        yield
            assert(html1 == html2, "index.html must be byte-identical across two emits")
            assert(json1 == json2, "versions.json must be byte-identical across two emits")
            assert(cname1 == cname2, "CNAME must be byte-identical across two emits")
        end for
    }

    // ---- Test 10: empty content still writes root files ----

    "empty content writes artifact-root files and empty versions.json" in {
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

    // ---- Test 13: versions.json length equals content size ----

    "versions.json entry count equals input content size" in {
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

    // ---- Test 14: bundle href in emitted HTML ends with main.js ----

    "bundle href in emitted index.html ends with main.js" in {
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

    // ---- The landing page carries the unified header + islands ----

    "landing index.html carries the unified SiteApp header and #docs-island + #versions-island" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules), out, bundleDir)
            html      <- readFile(out / "index.html")
        yield
            // The unified header is now on the landing page too.
            assert(html.contains("class=\"site-header\""), s"landing must carry the unified site-header: $html")
            assert(html.contains("search-input"), s"landing header must carry the search input: $html")
            // The islands ship on `/` so the bundle can hydrate `/` and the search/version
            // dropdown have their seed data.
            assert(
                html.contains("""<script type="application/json" id="docs-island">"""),
                s"landing must carry #docs-island: $html"
            )
            // Regression: the landing #docs-island must carry the latest version's per-route outline map,
            // not an empty one. The bundle reuses THIS island's docs body when the reader navigates from
            // `/` into the docs, so an empty outline map leaves the rail unable to expand any module's
            // sections on a landing-first visit.
            assert(
                html.contains("\"outlines\": [{\"route\": \"/latest/"),
                s"landing #docs-island must carry the per-route outline map (not empty): $html"
            )
            assert(
                html.contains("""<script type="application/json" id="versions-island">"""),
                s"landing must carry #versions-island: $html"
            )
            // The landing content body is still present under the header.
            assert(html.contains("data-section=\"hero\""), s"landing hero must still be present: $html")
        end for
    }

    // ---- route-specific <head> per route ----

    "route-specific <head>: /, /latest/, and a module route differ and each matches its own route" in {
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

    // ---- the full transpiled article prose ships in the raw module HTML ----

    "module index.html ships the full transpiled article prose in raw HTML" in {
        // The fixture README carries a distinctive prose sentence; the SSG must ship its transpiled
        // text in the page HTML (not a JS-hydrated stub) so non-JS crawlers index it.
        val prose  = "Channels carry values between fibers without blocking a thread."
        val readme = s"# kyo-distinct\n## Overview\n$prose\n"
        val mod    = WebsiteModule("kyo-distinct", "Foundation", "kyo-distinct", readme, WebsiteModule.Platforms(true, true, true, true))
        val content =
            WebsiteContent("intro", Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(content), out, bundleDir)
            html      <- readFile(out / "latest" / "kyo-distinct" / "index.html")
        yield
            assert(html.contains(prose), s"the full article prose must be present in the raw module HTML: $html")
            assert(html.contains("""id="overview""""), s"the transpiled heading anchor must be present: $html")
        end for
    }

    // ---- intra-repo source links resolve to GitHub at the version tag ----

    "module page rewrites a demo source link to a GitHub URL pinned to the version tag" in {
        // A module README's demo link is README-relative; the docs site hosts no source tree, so the
        // emitted page must point at the file on GitHub. The ref is the version's tag and the path is
        // prefixed with the module slug, in both the versioned tree and the /latest/ mirror.
        val readme = "# kyo-http\n## Demos\nRun the [ChatRoom](shared/src/test/scala/demo/ChatRoom.scala) demo.\n"
        val mod    = WebsiteModule("kyo-http", "Applications", "kyo-http", readme, WebsiteModule.Platforms(true, true, true, true))
        val content =
            WebsiteContent(
                "intro",
                Chunk(WebsiteContent.Group("Applications", Chunk(mod))),
                WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true)
            )
        val expected = "https://github.com/getkyo/kyo/blob/v1.0.0-RC2/kyo-http/shared/src/test/scala/demo/ChatRoom.scala"
        for
            out         <- tmpDir
            bundleDir   <- stubBundleDir
            _           <- emit(Chunk(content), out, bundleDir)
            latestHtml  <- readFile(out / "latest" / "kyo-http" / "index.html")
            versionHtml <- readFile(out / "v1.0.0-RC2" / "kyo-http" / "index.html")
        yield
            assert(latestHtml.contains(expected), s"/latest/ page must link the demo on GitHub: $latestHtml")
            assert(versionHtml.contains(expected), s"versioned page must link the demo on GitHub: $versionHtml")
            assert(!latestHtml.contains("href=\"shared/src"), s"the same-origin path must be gone: $latestHtml")
        end for
    }

    // ---- full route set + per-route content.md + per-version manifest.json ----

    "full route set + per-route content.md + per-version manifest.json" in {
        for
            out           <- tmpDir
            bundleDir     <- stubBundleDir
            _             <- emit(Chunk(vWithModules, vIntroOnly), out, bundleDir)
            vIndex        <- fileExists(out / "v1.0.0-RC2" / "index.html")
            dataIndex     <- fileExists(out / "v1.0.0-RC2" / "kyo-data" / "index.html")
            dataMd        <- readFile(out / "v1.0.0-RC2" / "kyo-data" / "content.md")
            kernelMd      <- readFile(out / "v1.0.0-RC2" / "kyo-kernel" / "content.md")
            kernelIdx     <- fileExists(out / "v1.0.0-RC2" / "kyo-kernel" / "index.html")
            vManifest     <- readFile(out / "v1.0.0-RC2" / "manifest.json")
            vIntroMd      <- readFile(out / "v1.0.0-RC2" / "content.md")
            introIdx      <- fileExists(out / "v0.9.3" / "index.html")
            introMan      <- readFile(out / "v0.9.3" / "manifest.json")
            introSlug     <- fileExists(out / "v0.9.3" / "kyo-data" / "index.html")
            introIntroMd  <- readFile(out / "v0.9.3" / "content.md")
            latestIdx     <- fileExists(out / "latest" / "index.html")
            latestMd      <- readFile(out / "latest" / "kyo-data" / "content.md")
            latestIntroMd <- readFile(out / "latest" / "content.md")
            latestMan     <- fileExists(out / "latest" / "manifest.json")
            versions      <- fileExists(out / "versions.json")
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

    "intro route renders the transpiled overview article, not an empty article (overview-as-home)" in {
        // A distinctive prose sentence + a couple of `## ` sections in the intro: the SSG must ship the
        // transpiled overview text AND its level-2 section anchors in the intro page HTML, and write the
        // raw intro as content.md.
        val prose = "Kyo is a Scala 3 toolkit for building applications across platforms."
        val intro = s"## Introduction\n$prose\n## Coming from ZIO\nNotes.\n"
        val mod =
            WebsiteModule("kyo-core", "Foundation", "kyo-core", "# kyo-core\nCore.\n", WebsiteModule.Platforms(true, true, true, true))
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

    // ---- intro-only version emits intro alone ----

    "intro-only version emits intro alone, zero per-module pages" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vIntroOnly), out, bundleDir)
            introIdx  <- fileExists(out / "v0.9.3" / "index.html")
            slugIdx   <- fileExists(out / "v0.9.3" / "kyo-data" / "index.html")
            manifest  <- readFile(out / "v0.9.3" / "manifest.json")
        yield
            assert(introIdx, "v0.9.3/index.html must exist")
            assert(!slugIdx, "intro-only version must have zero per-module pages")
            assert(manifest == "[]", s"intro-only manifest must be [], got: $manifest")
        end for
    }

    // ---- latest mirrors newest stable, not the RC ----

    "latest mirrors newest stable, not the RC" in {
        val stableV1 = WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group(
                "Foundation",
                Chunk(
                    WebsiteModule("kyo-stable", "Foundation", "kyo-stable", "# stable\n", WebsiteModule.Platforms(true, true, true, true))
                )
            )),
            WebsiteVersion("v1.0.0", "1.0.0", false)
        )
        val rcV2 = WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group(
                "Foundation",
                Chunk(
                    WebsiteModule("kyo-rc", "Foundation", "kyo-rc", "# rc\n", WebsiteModule.Platforms(true, true, true, true))
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
            stableMirror <- fileExists(out / "latest" / "kyo-stable" / "index.html")
            rcMirror     <- fileExists(out / "latest" / "kyo-rc" / "index.html")
        yield
            assert(stableMirror, "latest/ must mirror the stable v1.0.0 module")
            assert(!rcMirror, "latest/ must NOT mirror the RC module")
        end for
    }

    // ---- latest falls back to newest pre-release ----

    "latest falls back to newest pre-release when no stable tag" in {
        val rc1 = WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group(
                "Foundation",
                Chunk(
                    WebsiteModule("kyo-rc1", "Foundation", "kyo-rc1", "# rc1\n", WebsiteModule.Platforms(true, true, true, true))
                )
            )),
            WebsiteVersion("v1.0.0-RC1", "1.0.0-RC1", false)
        )
        val rc2 = WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group(
                "Foundation",
                Chunk(
                    WebsiteModule("kyo-rc2", "Foundation", "kyo-rc2", "# rc2\n", WebsiteModule.Platforms(true, true, true, true))
                )
            )),
            WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", false)
        )
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            // Oldest-first: [rc1, rc2]; newest pre-release is rc2 (the last entry).
            _    <- emit(Chunk(rc1, rc2), out, bundleDir)
            rc2m <- fileExists(out / "latest" / "kyo-rc2" / "index.html")
            rc1m <- fileExists(out / "latest" / "kyo-rc1" / "index.html")
        yield
            assert(rc2m, "latest/ must mirror rc2 (the newest pre-release)")
            assert(!rc1m, "latest/ must NOT mirror rc1")
        end for
    }

    // ---- stable wins over a NEWER pre-release (pickLatest stability consolidation) ----

    "latest mirrors an older stable over a newer pre-release (stability via WebsiteVersion.parse)" in {
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
                    WebsiteModule.Platforms(true, true, true, true)
                ))
            )),
            WebsiteVersion("v1.0.0", "1.0.0", false)
        )
        val rc101 = WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group(
                "Foundation",
                Chunk(WebsiteModule("kyo-rc101", "Foundation", "kyo-rc101", "# rc\n", WebsiteModule.Platforms(true, true, true, true)))
            )),
            WebsiteVersion("v1.0.1-RC1", "1.0.1-RC1", false)
        )
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            // Oldest-first by semantic order: stable v1.0.0 sorts before the v1.0.1-RC1 pre-release.
            _            <- emit(Chunk(stable100, rc101), out, bundleDir)
            stableMirror <- fileExists(out / "latest" / "kyo-stable100" / "index.html")
            rcMirror     <- fileExists(out / "latest" / "kyo-rc101" / "index.html")
        yield
            assert(stableMirror, "latest/ must mirror the stable v1.0.0 module, not the newer pre-release")
            assert(!rcMirror, "latest/ must NOT mirror the v1.0.1-RC1 pre-release module")
        end for
    }

    // ---- docs page embeds transpiled article AND content.md equals source ----

    "docs page embeds transpiled article AND content.md equals the source" in {
        val readme = "# MyModule\n## Scope\nDoes things.\n```scala\nval x = 1\n```\n"
        val mod    = WebsiteModule("my-module", "Foundation", "my-module", readme, WebsiteModule.Platforms(true, true, true, true))
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

    // ---- rail section links resolve to article anchors ----

    "rail section links resolve to article anchors" in {
        val readme = "# Alpha\n## Beta\nText.\n### Gamma\nMore.\n"
        val mod    = WebsiteModule("anchors", "Foundation", "anchors", readme, WebsiteModule.Platforms(true, true, true, true))
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

    // ---- escJson escapes quote and backslash in versions.json ----

    "escJson escapes quote and backslash in versions.json" in {
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

    // ---- escJson escapes control chars (newline, tab, CR, SOH) so JSON-LD stays valid ----

    "escJson escapes control chars (newline, tab, carriage-return, SOH 0x01) in JSON-LD" in {
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

    "docs page embeds #docs-island + #versions-island that parse back to seeded content" in {
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
            // the pre-rendered article HTML, and the heading outline.
            val decoded = islandJson.replace("\\u003c", "<").replace("\\u003e", ">")
            assert(decoded.contains("\"tag\": \"v1.0.0-RC2\""), s"island must carry the version tag: $decoded")
            assert(decoded.contains("\"slug\": \"kyo-data\""), s"island must carry the kyo-data module: $decoded")
            // The island carries "article" + "headings", not "markdown".
            assert(decoded.contains("\"article\""), s"island must carry article field: $decoded")
            assert(decoded.contains("\"headings\""), s"island must carry headings field: $decoded")
            assert(!decoded.contains("\"markdown\""), s"island must NOT contain markdown field: $decoded")
            assert(!islandJson.contains("</script>"), "island JSON must not contain a literal </script> that closes the element")
        end for
    }

    // ---- the latest version's own v<X>/ pages link within v<X>/, not latest/ ----

    "latest version's own v<X> tree links within v<X> not latest" in {
        // The single version is flagged latest=true, so emit writes it under BOTH v1.0.0/ (emitVersion)
        // and latest/ (emitLatest). The v1.0.0/ copy must link within /v1.0.0/, not /latest/.
        val readme  = "# kyo-data\n## Overview\nData types.\n"
        val readmeB = "# kyo-core\n## Effects\nCore.\n"
        val modA    = WebsiteModule("kyo-data", "Foundation", "kyo-data", readme, WebsiteModule.Platforms(true, true, true, true))
        val modB    = WebsiteModule("kyo-core", "Foundation", "kyo-core", readmeB, WebsiteModule.Platforms(true, true, true, true))
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
            // the <body> because the current-latest version's <head> now carries the
            // canonical -> /latest/kyo-data/ (this version IS the latest, so its versioned tree
            // canonicalizes to the /latest/ home); that intentional head tag is not a body nav link.
            val vBody = vHtml.substring(vHtml.indexOf("<body>"), vHtml.indexOf("</body>"))
            assert(vBody.contains("/v1.0.0/kyo-data/"), s"v1.0.0 page body must link within v1.0.0: $vBody")
            assert(vBody.contains("/v1.0.0/kyo-core/"), s"v1.0.0 page body must link within v1.0.0: $vBody")
            assert(
                !vBody.contains("/latest/kyo-data/") && !vBody.contains("/latest/kyo-core/"),
                s"latest version's v1.0.0 page body must NOT link to /latest/: $vBody"
            )
            // The head canonical IS the /latest/ dedup target, distinct from body nav.
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

    // ---- sitemap.xml lists /, the /latest/ overview, and every latest slug; excludes versioned URLs ----

    "sitemap.xml lists /, the /latest/ overview, and every latest slug; no /v<X>/ or asset URLs" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules, vIntroOnly), out, bundleDir)
            sitemap   <- readFile(out / "sitemap.xml")
            // Cross-check the <loc> count against the actual emitted latest/<slug>/ pages + 2 (the root
            // and the /latest/ overview).
            latestSlugPages <-
                val latestDir = out / "latest"
                Abort.run[FileException](Path.runReadOnly {
                    latestDir.exists.map {
                        case false => 0
                        case true =>
                            latestDir.list.map { entries =>
                                Kyo.foreach(entries)(_.isDirectory).map(_.count(identity))
                            }
                    }
                }).map {
                    case Result.Success(n) => n
                    case Result.Failure(e) => Abort.fail(WebsiteEmitException(latestDir.toString, e))
                    case p: Result.Panic   => Abort.error(p)
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

    // ---- robots.txt allows all and points to the sitemap ----

    "robots.txt allows all crawlers and points to sitemap, blocks no AI crawler" in {
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

    // ---- sitemap.xml and robots.txt are always written, even for empty content ----

    "sitemap.xml and robots.txt always written, even for empty content" in {
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

    // ---- module page head carries TechArticle JSON-LD ----

    "module page head has TechArticle JSON-LD with the correct self url" in {
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

    // ---- landing page head carries WebSite + SoftwareSourceCode JSON-LD ----

    "landing page head has WebSite + SoftwareSourceCode JSON-LD" in {
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

    // ---- canonical dedup + intro now indexable ----

    "current-latest /v<X>/ module + intro canonicalize to /latest/; /latest/ intro is indexable" in {
        for
            out            <- tmpDir
            bundleDir      <- stubBundleDir
            _              <- emit(Chunk(vWithModules), out, bundleDir)
            versionedHtml  <- readFile(out / "v1.0.0-RC2" / "kyo-data" / "index.html")
            versionedIntro <- readFile(out / "v1.0.0-RC2" / "index.html")
            latestHtml     <- readFile(out / "latest" / "kyo-data" / "index.html")
            latestIntro    <- readFile(out / "latest" / "index.html")
        yield
            // the current-latest versioned page points to /latest/, not self.
            assert(
                canonical(versionedHtml) == "https://getkyo.io/latest/kyo-data/",
                s"versioned page canonical must be /latest/, got: ${canonical(versionedHtml)}"
            )
            // The /latest/ page is self-canonical.
            assert(
                canonical(latestHtml) == "https://getkyo.io/latest/kyo-data/",
                s"latest page must be self-canonical, got: ${canonical(latestHtml)}"
            )
            // the current-latest /v<X>/ intro canonicalizes to /latest/.
            assert(
                canonical(versionedIntro) == "https://getkyo.io/latest/",
                s"versioned intro canonical must dedup to /latest/, got: ${canonical(versionedIntro)}"
            )
            // the /latest/ intro is the overview (real content), self-canonical
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

    // ---- golden: SSG title/canonical match updateHead strings for each route kind ----

    "golden: SSG title/canonical match updateHead strings for each route kind" in {
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

    /** The href of the `<link rel="canonical">` element. */
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

    // ---- content.html, island reshape, landing seed, sitemap, manifest, escape ----

    "content.html written per route with html + headings" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules), out, bundleDir)
            // Read the content.html for the kyo-data module.
            contentHtml <- readFile(out / "v1.0.0-RC2" / "kyo-data" / "content.html")
        yield
            assert(contentHtml.contains("\"html\""), s"content.html must carry an html field: $contentHtml")
            assert(contentHtml.contains("\"headings\""), s"content.html must carry a headings array: $contentHtml")
            // The html field must contain heading tags from the rendered README.
            val raw = contentHtml.replace("\\u003c", "<").replace("\\u003e", ">").replace("\\\"", "\"")
            assert(raw.contains("<h"), s"html field must contain rendered heading elements: $raw")
            // The headings array must carry level entries.
            assert(contentHtml.contains("\"level\""), s"headings must carry level entries: $contentHtml")
        end for
    }

    "article ids equal shipped heading slugs in content.html" in {
        val readme = "# Title\n## Section One\n### Sub\n"
        val mod    = WebsiteModule("inv004", "Foundation", "inv004", readme, WebsiteModule.Platforms(true, true, true, true))
        val content =
            WebsiteContent("intro", Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out         <- tmpDir
            bundleDir   <- stubBundleDir
            _           <- emit(Chunk(content), out, bundleDir)
            indexHtml   <- readFile(out / "v1.0.0" / "inv004" / "index.html")
            contentHtml <- readFile(out / "v1.0.0" / "inv004" / "content.html")
        yield
            // Extract all id="..." values from the article HTML in index.html.
            val idPattern  = """id="([^"]+)"""".r
            val articleIds = idPattern.findAllMatchIn(indexHtml).map(_.group(1)).toSet
            // Extract all slug values from the headings array in content.html.
            val slugPattern = """"slug":\s*"([^"]+)"""".r
            val slugs       = slugPattern.findAllMatchIn(contentHtml).map(_.group(1)).toSet
            assert(articleIds.nonEmpty, s"Expected article id attributes in index.html: $indexHtml")
            assert(slugs.nonEmpty, s"Expected slug entries in content.html: $contentHtml")
            assert(
                slugs.subsetOf(articleIds),
                s"every heading slug must have a matching article id; slugs=$slugs, ids=$articleIds"
            )
        end for
    }

    "island carries article + headings, not markdown field" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules), out, bundleDir)
            html      <- readFile(out / "latest" / "kyo-data" / "index.html")
        yield
            val islandJson = extractIsland(html, "docs-island")
            val decoded    = islandJson.replace("\\u003c", "<").replace("\\u003e", ">")
            assert(decoded.contains("\"article\""), s"island must carry an article field: $decoded")
            assert(decoded.contains("\"headings\""), s"island must carry a headings array: $decoded")
            assert(!decoded.contains("\"markdown\""), s"island must NOT carry a markdown field: $decoded")
        end for
    }

    "landing seeds an empty-article island" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules), out, bundleDir)
            html      <- readFile(out / "index.html")
        yield
            val islandJson = extractIsland(html, "docs-island")
            val decoded    = islandJson.replace("\\u003c", "<").replace("\\u003e", ">")
            // The landing island has article="" and headings=[] (empty seeds; no article to render).
            assert(decoded.contains(""""article": """""), s"landing island must have empty article field: $decoded")
            assert(decoded.contains(""""headings": []"""), s"landing island must have empty headings array: $decoded")
        end for
    }

    "content.md still emitted per route" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules, vIntroOnly), out, bundleDir)
            // Module page.
            dataMd <- readFile(out / "v1.0.0-RC2" / "kyo-data" / "content.md")
            // Intro page.
            introMd <- readFile(out / "v1.0.0-RC2" / "content.md")
            // Latest mirror.
            latestMd <- readFile(out / "latest" / "kyo-data" / "content.md")
        yield
            assert(dataMd == dataReadme, s"content.md must equal module.readme, got: $dataMd")
            assert(introMd == "intro", s"intro content.md must equal content.intro, got: $introMd")
            assert(latestMd == dataReadme, s"latest content.md must equal module.readme, got: $latestMd")
        end for
    }

    "sitemap excludes content.html as well as content.md" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules, vIntroOnly), out, bundleDir)
            sitemap   <- readFile(out / "sitemap.xml")
        yield
            assert(!sitemap.contains("content.md"), s"sitemap must NOT list content.md: $sitemap")
            assert(!sitemap.contains("content.html"), s"sitemap must NOT list content.html: $sitemap")
        end for
    }

    "manifest toc level-carrying, unchanged after island reshape" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules), out, bundleDir)
            manifest  <- readFile(out / "v1.0.0-RC2" / "manifest.json")
        yield
            // The manifest carries level-carrying toc entries (shape from manifestEntry, unchanged).
            assert(manifest.contains("\"level\""), s"manifest toc must carry level entries: $manifest")
            assert(manifest.contains("\"text\""), s"manifest toc must carry text entries: $manifest")
            assert(manifest.contains("\"slug\""), s"manifest toc must carry slug entries: $manifest")
            assert(manifest.contains("kyo-data"), s"manifest must list kyo-data module: $manifest")
        end for
    }

    "island article HTML round-trips an escaped < tag on emit" in {
        // Build a README whose rendered article will contain < characters (via a heading with a code
        // snippet; the backtick renders to <code>, so the rendered HTML contains <code>...</code>).
        val readme = "# Test\n## Usage\n`myFunc` does things.\n"
        val mod    = WebsiteModule("escape-test", "Foundation", "escape-test", readme, WebsiteModule.Platforms(true, true, true, true))
        val content =
            WebsiteContent("intro", Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(content), out, bundleDir)
            html      <- readFile(out / "v1.0.0" / "escape-test" / "index.html")
        yield
            val islandJson = extractIsland(html, "docs-island")
            // The rendered article HTML contains <p> tags; those must be unicode-escaped in the island.
            // escJson escapes < as literal <; escScript then replaces < with <.
            // So the raw island text must contain < where < appeared in the HTML.
            assert(
                islandJson.contains("\\u003c"),
                s"island must contain unicode-escaped < (\\u003c) from the article HTML: $islandJson"
            )
            // No literal </script> must appear in the raw island text.
            assert(
                !islandJson.contains("</script>"),
                s"island must not contain literal </script>: $islandJson"
            )
        end for
    }

    "module page carries both islands before </body>, byte-identical to prior splice behavior" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules), out, bundleDir)
            html      <- readFile(out / "v1.0.0-RC2" / "kyo-data" / "index.html")
        yield
            assert(
                html.contains("""<script type="application/json" id="docs-island">"""),
                s"docs-island must be present: $html"
            )
            assert(
                html.contains("""<script type="application/json" id="versions-island">"""),
                s"versions-island must be present: $html"
            )
            val docsIdx    = html.indexOf("""id="docs-island">""")
            val versIdx    = html.indexOf("""id="versions-island">""")
            val bodyEndIdx = html.indexOf("</body>")
            assert(docsIdx < versIdx, "docs-island must precede versions-island")
            assert(versIdx < bodyEndIdx, "both islands must be before </body>")
            val islandJson = extractIsland(html, "docs-island")
            val decoded    = islandJson.replace("\\u003c", "<").replace("\\u003e", ">")
            assert(decoded.contains("\"slug\": \"kyo-data\""), s"decoded island must carry kyo-data slug: $decoded")
        end for
    }

    "docs-island JSON escapes </script> to the JS-unicode form (no literal closing tag)" in {
        val readme = "# Test\n## Usage\n`myFunc` does things.\n"
        val mod    = WebsiteModule("escape-chk", "Foundation", "escape-chk", readme, WebsiteModule.Platforms(true, true, true, true))
        val content =
            WebsiteContent("intro", Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(content), out, bundleDir)
            html      <- readFile(out / "v1.0.0" / "escape-chk" / "index.html")
        yield
            val islandJson = extractIsland(html, "docs-island")
            assert(
                islandJson.contains("\\u003c"),
                s"island must contain unicode-escaped < (\\u003c): $islandJson"
            )
            assert(
                !islandJson.contains("</script>"),
                s"island must not contain literal </script>: $islandJson"
            )
        end for
    }

    // No-regression roll-up: concrete level==2 check across island, content.html,
    // content.md, sitemap, manifest, and article ids in a single multi-version emit.
    "level-carrying headings in island, content.html, and manifest" in {
        // Use a README with explicit level-2 headings so the level==2 assertion is concrete.
        val readme = "# Alpha\n## Beta\nText.\n## Gamma\nMore.\n"
        val mod    = WebsiteModule("inv010", "Foundation", "inv010", readme, WebsiteModule.Platforms(true, true, true, true))
        val content =
            WebsiteContent("intro", Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out         <- tmpDir
            bundleDir   <- stubBundleDir
            _           <- emit(Chunk(content), out, bundleDir)
            indexHtml   <- readFile(out / "v1.0.0" / "inv010" / "index.html")
            contentHtml <- readFile(out / "v1.0.0" / "inv010" / "content.html")
            contentMd   <- readFile(out / "v1.0.0" / "inv010" / "content.md")
            manifest    <- readFile(out / "v1.0.0" / "manifest.json")
            sitemap     <- readFile(out / "sitemap.xml")
        yield
            // (a) island JSON carries level==2 heading entry.
            val islandJson    = extractIsland(indexHtml, "docs-island")
            val decodedIsland = islandJson.replace("\\u003c", "<").replace("\\u003e", ">")
            assert(decodedIsland.contains("\"level\": 2"), s"island must carry a level==2 heading entry: $decodedIsland")
            // (a) content.html headings array carries level==2 entry.
            val decodedContent = contentHtml.replace("\\u003c", "<").replace("\\u003e", ">")
            assert(decodedContent.contains("\"level\": 2"), s"content.html must carry a level==2 heading entry: $decodedContent")
            // (b) content.md still emitted and equals the source.
            assert(contentMd == readme, s"content.md must equal module.readme, got: $contentMd")
            // (b) sitemap excludes both content.md and content.html.
            assert(!sitemap.contains("content.md"), s"sitemap must not list content.md: $sitemap")
            assert(!sitemap.contains("content.html"), s"sitemap must not list content.html: $sitemap")
            // (c) manifest toc carries level, text, and slug fields.
            assert(manifest.contains("\"level\""), s"manifest toc must carry level: $manifest")
            assert(manifest.contains("\"text\""), s"manifest toc must carry text: $manifest")
            assert(manifest.contains("\"slug\""), s"manifest toc must carry slug: $manifest")
            // (c) manifest toc carries a level==2 entry (sidebar auto-expand requires this).
            assert(manifest.contains("\"level\": 2"), s"manifest toc must carry a level==2 entry: $manifest")
            // (d) article ids equal shipped slugs: extract id="..." from index.html and slug values from content.html.
            val idPattern   = """id="([^"]+)"""".r
            val slugPattern = """"slug":\s*"([^"]+)"""".r
            val articleIds  = idPattern.findAllMatchIn(indexHtml).map(_.group(1)).toSet
            val slugs       = slugPattern.findAllMatchIn(contentHtml).map(_.group(1)).toSet
            assert(slugs.nonEmpty, s"content.html must carry slug entries: $contentHtml")
            assert(
                slugs.subsetOf(articleIds),
                s"all heading slugs must have matching article ids; slugs=$slugs, ids=$articleIds"
            )
        end for
    }

    // ---- emit writes one search-index.json per prefix next to manifest.json ----

    "emit writes one search-index.json per prefix next to manifest.json" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules), out, bundleDir)
            exists    <- fileExists(out / "v1.0.0-RC2" / "search-index.json")
            latestEx  <- fileExists(out / "latest" / "search-index.json")
            json      <- readFile(out / "v1.0.0-RC2" / "search-index.json")
        yield
            assert(exists, "v1.0.0-RC2/search-index.json must exist next to manifest.json")
            assert(latestEx, "latest/search-index.json must exist")
            assert(json.startsWith("["), s"search-index.json must be a JSON array: $json")
            assert(json.endsWith("]"), s"search-index.json must end with ]: $json")
            // One object per module.
            assert(json.contains("\"slug\": \"kyo-data\""), s"search-index must carry kyo-data: $json")
            assert(json.contains("\"slug\": \"kyo-kernel\""), s"search-index must carry kyo-kernel: $json")
        end for
    }

    // ---- each section carries level + text + slug + symbols + body ----

    "each section in search-index.json carries level, text, slug, symbols, and body" in {
        val readme = "## Fibers and forks\nFibers are lightweight threads.\n### Interruption\nInterrupt a fiber.\n"
        val mod    = WebsiteModule("kyo-async", "Foundation", "kyo-async", readme, WebsiteModule.Platforms(true, true, true, true))
        val content =
            WebsiteContent("intro", Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(content), out, bundleDir)
            json      <- readFile(out / "v1.0.0" / "search-index.json")
        yield
            assert(json.contains("\"text\": \"Fibers and forks\""), s"first section text missing: $json")
            assert(json.contains("\"slug\": \"fibers-and-forks\""), s"first section slug missing: $json")
            assert(json.contains("\"level\": 2"), s"first section level missing: $json")
            assert(json.contains("Fibers are lightweight threads"), s"first section body missing: $json")
            assert(json.contains("\"symbols\": \""), s"each section must carry a symbols field: $json")
            assert(json.contains("\"body\": \""), s"each section must carry a body field: $json")
            assert(json.contains("\"text\": \"Interruption\""), s"second section text missing: $json")
            assert(json.contains("\"slug\": \"interruption\""), s"second section slug missing: $json")
            assert(json.contains("\"level\": 3"), s"second section level missing: $json")
            assert(json.contains("Interrupt a fiber"), s"second section snippet missing: $json")
            // Document order: fibers-and-forks before interruption.
            assert(json.indexOf("fibers-and-forks") < json.indexOf("interruption"), "sections must be in document order")
        end for
    }

    // ---- body <= 600 chars, word-boundary, no ellipsis ----

    "body is at most 600 chars, word-boundary truncated, and has no ellipsis" in {
        // Produce a prose block that exceeds 600 characters.
        val longProse = ("The quick brown fox jumps over the lazy dog " * 20).trim
        val readme    = s"## Section\n$longProse\n"
        val mod       = WebsiteModule("kyo-long", "Foundation", "kyo-long", readme, WebsiteModule.Platforms(true, true, true, true))
        val content =
            WebsiteContent("intro", Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(content), out, bundleDir)
            json      <- readFile(out / "v1.0.0" / "search-index.json")
        yield
            // Extract the body value from the JSON.
            val bodyKey = "\"body\": \""
            val sk      = json.indexOf(bodyKey)
            assert(sk >= 0, s"body field not found: $json")
            val from    = sk + bodyKey.length
            var i       = from
            var snippet = ""
            while i < json.length && json.charAt(i) != '"' do i += 1
            snippet = json.substring(from, i)
            assert(snippet.length <= 600, s"body must be at most 600 chars, got ${snippet.length}: $snippet")
            assert(!snippet.endsWith("..."), s"body must not end with ...: $snippet")
            assert(!snippet.endsWith("…"), s"body must not end with ellipsis char: $snippet")
            // The snippet must land on a whole-word boundary: it must be a prefix of the
            // whitespace-collapsed source prose, and the character at position snippet.length
            // in the collapsed string must be whitespace (proving the cut did not split a word).
            val collapsed = longProse.replaceAll("\\s+", " ").trim
            assert(
                collapsed.startsWith(snippet),
                s"snippet must be a prefix of the collapsed prose: collapsed='$collapsed', snippet='$snippet'"
            )
            assert(
                snippet.length < collapsed.length && collapsed.charAt(snippet.length).isWhitespace,
                s"character after cut must be whitespace (whole-word boundary): collapsed.charAt(${snippet.length})='${
                        if snippet.length < collapsed.length then collapsed.charAt(snippet.length) else '?'
                    }'"
            )
        end for
    }

    // ---- heading-less module emits sections [] ----

    "a heading-less module emits sections [] in search-index.json" in {
        val readme = "Prose only, no headings.\n"
        val mod    = WebsiteModule("kyo-noh", "Foundation", "kyo-noh", readme, WebsiteModule.Platforms(true, true, true, true))
        val content =
            WebsiteContent("intro", Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(content), out, bundleDir)
            json      <- readFile(out / "v1.0.0" / "search-index.json")
        yield
            assert(json.contains("\"slug\": \"kyo-noh\""), s"module slug must be present: $json")
            assert(json.contains("\"sections\": []"), s"heading-less module must emit empty sections: $json")
        end for
    }

    // ---- manifest.json is byte-unchanged after adding search-index emit ----

    "manifest.json is byte-unchanged after adding the search-index emit" in {
        // Golden string: the exact deterministic output of writeManifest for vWithModules. The two
        // fixture modules (kyo-data then kyo-kernel) are followed by the manifesto, which emit appends
        // as the final docs page of every version that has a module table (read from the repo-root
        // MANIFESTO.md, whose only heading is the level-1 "After Scarcity"). The pager chains through
        // it: kyo-kernel's `next` is now "manifesto", and the manifesto's `prev` is "kyo-kernel". This
        // guards against any future manifest reformatting and proves the search-index emit is purely
        // additive (the manifest bytes are identical before and after adding writeSearchIndex).
        val expectedManifest =
            """|[
               |  {"slug": "kyo-data", "group": "Foundation", "title": "kyo-data", "prev": null, "next": "kyo-kernel", "toc": [{"level": 1, "text": "kyo-data", "slug": "kyo-data"}, {"level": 2, "text": "Overview", "slug": "overview"}]},
               |  {"slug": "kyo-kernel", "group": "Foundation", "title": "kyo-kernel", "prev": "kyo-data", "next": "manifesto", "toc": [{"level": 1, "text": "kyo-kernel", "slug": "kyo-kernel"}, {"level": 2, "text": "Effects", "slug": "effects"}]},
               |  {"slug": "manifesto", "group": "Manifesto", "title": "Manifesto", "prev": "kyo-kernel", "next": null, "toc": [{"level": 1, "text": "After Scarcity", "slug": "after-scarcity"}]}
               |]""".stripMargin
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules), out, bundleDir)
            manifest  <- readFile(out / "v1.0.0-RC2" / "manifest.json")
            siExists  <- fileExists(out / "v1.0.0-RC2" / "search-index.json")
        yield
            assert(
                manifest == expectedManifest,
                s"manifest.json must be byte-identical to the golden string.\nExpected:\n$expectedManifest\nActual:\n$manifest"
            )
            assert(siExists, "search-index.json must be a separate file next to manifest.json")
        end for
    }

    // ---- search-index.json escapes JSON-special characters ----

    "search-index.json escapes JSON-special characters in fields" in {
        val headingText = "Results: \"quoted\" & <angle>"
        val snippetText = "See the reference for more."
        val readme      = s"## $headingText\n$snippetText\n"
        val mod         = WebsiteModule("kyo-esc", "Foundation", "kyo-esc", readme, WebsiteModule.Platforms(true, true, true, true))
        val content =
            WebsiteContent("intro", Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(content), out, bundleDir)
            json      <- readFile(out / "v1.0.0" / "search-index.json")
        yield
            // The JSON must not contain an unescaped double-quote that would break the array.
            // Verify the text field carries properly escaped quotes.
            assert(json.contains("\\\"quoted\\\""), s"double quotes must be escaped in heading text: $json")
            // The file must be a valid JSON array (starts with [ and ends with ]).
            assert(json.startsWith("["), s"must start with [: $json")
            assert(json.endsWith("]"), s"must end with ]: $json")
            assert(json.contains("\"slug\": \"kyo-esc\""), s"module slug must be present: $json")
        end for
    }

    // ---- escJson char-class contracts ----

    "escJson escapes control chars below 0x20 to \\u00XX" in {
        // Build a module whose title contains control characters in [0x01, 0x1f] plus the
        // other special-char cases: backslash, double-quote, \n, \r, \t.
        // These all pass through escJson when the module title is serialized into the
        // search-index.json "title" field.
        val ctrl1  = 0x01.toChar.toString // should become 
        val ctrl1f = 0x1f.toChar.toString // should become
        val title  = s"x${ctrl1}y${ctrl1f}z"
        val readme = s"# $title\n"
        val mod    = WebsiteModule("kyo-ctrl", "Foundation", "kyo-ctrl", readme, WebsiteModule.Platforms(true, true, true, true))
        val content =
            WebsiteContent("intro", Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(content), out, bundleDir)
            json      <- readFile(out / "v1.0.0" / "search-index.json")
        yield
            assert(json.contains("\\u0001"), s"0x01 must be escaped as \\u0001 in the JSON: $json")
            assert(json.contains("\\u001f"), s"0x1f must be escaped as \\u001f in the JSON: $json")
            assert(!json.contains(ctrl1), s"raw 0x01 must not appear unescaped in the JSON: $json")
            assert(!json.contains(ctrl1f), s"raw 0x1f must not appear unescaped in the JSON: $json")
        end for
    }

    "escJson leaves plain ASCII and unicode above 0x20 unchanged" in {
        // A title with plain ASCII text and non-control unicode chars must pass through
        // escJson unmodified (the default arm of the match just appends the char).
        val title  = "Aborté日本語" // "Aborté日本語"
        val readme = s"# $title\n"
        val mod    = WebsiteModule("kyo-uni", "Foundation", "kyo-uni", readme, WebsiteModule.Platforms(true, true, true, true))
        val content =
            WebsiteContent("intro", Chunk(WebsiteContent.Group("Foundation", Chunk(mod))), WebsiteVersion("v1.0.0", "1.0.0", true))
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(content), out, bundleDir)
            json      <- readFile(out / "v1.0.0" / "search-index.json")
        yield assert(json.contains(title), s"plain ASCII and unicode-above-0x20 title must appear verbatim in the JSON: $json")
        end for
    }

    // ---- manifesto: appended as the final docs page of a version with a module menu ----

    "the manifesto is appended as the final docs page of versions with a module menu" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithModules), out, bundleDir)
            vManPage  <- fileExists(out / "v1.0.0-RC2" / "manifesto" / "index.html")
            latestMan <- fileExists(out / "latest" / "manifesto" / "index.html")
            manHtml   <- readFile(out / "latest" / "manifesto" / "index.html")
            manMd     <- readFile(out / "latest" / "manifesto" / "content.md")
            manifest  <- readFile(out / "latest" / "manifest.json")
        yield
            assert(vManPage, "v1.0.0-RC2/manifesto/index.html must exist (the manifesto is a docs page)")
            assert(latestMan, "latest/manifesto/index.html must exist")
            // It renders like any other docs page: the article carries the manifesto's heading, and the
            // sidebar shows the Manifesto group.
            assert(manHtml.contains("After Scarcity"), s"manifesto page must render its title: ${manHtml.take(400)}")
            assert(manHtml.contains("sidebar-group-name"), "manifesto page must carry the docs sidebar chrome")
            // content.md is the raw MANIFESTO.md, exactly like a module page ships its README.
            assert(
                manMd.contains("After Scarcity") && manMd.contains("Scarcity built the world"),
                s"manifesto content.md must be the raw markdown, got: ${manMd.take(200)}"
            )
            // It is the LAST manifest entry, in its own "Manifesto" group, after the modules.
            val dataIdx = manifest.indexOf("\"slug\": \"kyo-data\"")
            val manIdx  = manifest.indexOf("\"slug\": \"manifesto\"")
            assert(dataIdx >= 0 && manIdx > dataIdx, s"manifesto must come after the modules in the manifest: $manifest")
            assert(manifest.contains("\"group\": \"Manifesto\""), s"manifesto must be in the Manifesto group: $manifest")
        end for
    }

    "a missing MANIFESTO.md aborts the emit (the manifesto is required, not optional)" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            noManifesto <- Scope.acquireRelease(
                Sync.defer(java.nio.file.Files.createTempDirectory("kyo-no-manifesto"))
            )(d => Sync.defer(deleteDir(d))).map(d => Path(d.toString))
            result <- Abort.run[WebsiteException](
                WebsiteGenerator.emit(Chunk(vWithModules), out, WebsiteGenerator.Config(noManifesto, bundleDir))
            )
        yield
            val aborted = result match
                case Result.Failure(e: WebsiteReadmeException) =>
                    e.detail == WebsiteReadmeException.ReadmeFailure.Missing && e.path.toString.endsWith("MANIFESTO.md")
                case _ => false
            assert(aborted, s"a missing MANIFESTO.md must abort with WebsiteReadmeException(Missing) naming the file, got: $result")
        end for
    }

    // ---- tutorial child pages: first-class SSG emission + manifest/search/sitemap inclusion ----

    private val tutorialContent = "# Basic EventLog\n\n## Setup\nprose"
    private val tutorialDecl =
        WebsiteTutorial.Declaration("basic-eventlog", "Basic EventLog", Path("kyo-eventlog/docs/basic-eventlog.md"))
    private val eventlogModule =
        WebsiteModule(
            "kyo-eventlog",
            "Foundation",
            "kyo-eventlog",
            "# kyo-eventlog\n## Intro\nText.\n",
            WebsiteModule.Platforms(true, true, true, true)
        )
    private val vWithTutorial =
        WebsiteContent(
            "intro",
            Chunk(WebsiteContent.Group("Foundation", Chunk(eventlogModule))),
            WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true),
            Chunk(WebsiteContent.Tutorial("kyo-eventlog", tutorialDecl, tutorialContent))
        )

    "emit writes a first-class tutorial page at <prefix>/<module>/tutorials/<slug>/" in {
        for
            out         <- tmpDir
            bundleDir   <- stubBundleDir
            _           <- emit(Chunk(vWithTutorial), out, bundleDir)
            indexExists <- fileExists(out / "latest" / "kyo-eventlog" / "tutorials" / "basic-eventlog" / "index.html")
            contentHtml <- readFile(out / "latest" / "kyo-eventlog" / "tutorials" / "basic-eventlog" / "content.html")
            contentMd   <- readFile(out / "latest" / "kyo-eventlog" / "tutorials" / "basic-eventlog" / "content.md")
        yield
            assert(indexExists, "latest/kyo-eventlog/tutorials/basic-eventlog/index.html must exist")
            // content.html carries the pre-rendered article JSON; its html field holds the rendered article.
            assert(contentHtml.contains("\"html\""), s"content.html must carry an html field: $contentHtml")
            assert(contentHtml.contains("\"headings\""), s"content.html must carry a headings array: $contentHtml")
            val raw = contentHtml.replace("\\u003c", "<").replace("\\u003e", ">").replace("\\\"", "\"")
            assert(raw.contains("<h"), s"content.html html field must carry rendered heading elements: $raw")
            assert(raw.contains("Basic EventLog"), s"content.html must carry the tutorial title text: $raw")
            assert(raw.contains("Setup"), s"content.html must carry the tutorial section text: $raw")
            // content.md is the raw tutorial source, byte for byte.
            assert(contentMd == tutorialContent, s"content.md must equal the raw tutorial content, got: $contentMd")
        end for
    }

    "emit lists the tutorial route in manifest.json, search-index.json, and sitemap.xml" in {
        for
            out       <- tmpDir
            bundleDir <- stubBundleDir
            _         <- emit(Chunk(vWithTutorial), out, bundleDir)
            manifest  <- readFile(out / "latest" / "manifest.json")
            search    <- readFile(out / "latest" / "search-index.json")
            sitemap   <- readFile(out / "sitemap.xml")
        yield
            assert(
                manifest.contains("\"slug\": \"kyo-eventlog/tutorials/basic-eventlog\""),
                s"manifest must list the tutorial route slug: $manifest"
            )
            assert(manifest.contains("\"title\": \"Basic EventLog\""), s"manifest tutorial entry must carry the title: $manifest")
            assert(
                search.contains("\"slug\": \"kyo-eventlog/tutorials/basic-eventlog\""),
                s"search-index must list the tutorial route slug: $search"
            )
            assert(search.contains("\"title\": \"Basic EventLog\""), s"search-index tutorial entry must carry the title: $search")
            assert(
                sitemap.contains("<loc>https://getkyo.io/latest/kyo-eventlog/tutorials/basic-eventlog/</loc>"),
                s"sitemap must list the tutorial route loc: $sitemap"
            )
        end for
    }

end WebsiteGeneratorTest
