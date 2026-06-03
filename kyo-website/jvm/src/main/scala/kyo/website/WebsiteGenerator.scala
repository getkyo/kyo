package kyo.website

import kyo.*

/** JVM-only static-site generator: emits the landing page, the per-version docs routes, the
  * versions manifest, artifact-root files, and static assets into an output directory.
  *
  * `emit` is the single public entry point. It delegates to private helpers for each output
  * file, mapping kyo-core `Path` IO errors to `WebsiteEmitException` so the whole emit
  * aborts on the first write failure.
  *
  * The HTML document is produced by draining the first emission of
  * `WebsitePage.wrap(opts)(view)` (which delegates to `UI.runRenderPage` internally).
  * Taking only the first emission gives the initial static render; subsequent reactive
  * re-renders are irrelevant for SSG. Each docs page also embeds a `#docs-island` and a
  * `#versions-island` JSON `<script>` (the boot schema the SPA bundle reads on load) and a
  * sibling `content.md` raw-Markdown file (the single source the client fetches, D6/INV-009).
  *
  * Output directory layout:
  * {{{
  *   <outDir>/index.html             -- landing page (INV-009)
  *   <outDir>/<prefix>/index.html    -- per-version overview page (chrome + transpiled root-README intro)
  *   <outDir>/<prefix>/content.md    -- raw root-README intro Markdown (== content.intro, D6)
  *   <outDir>/<prefix>/<slug>/index.html  -- per-module docs page (chrome + transpiled article)
  *   <outDir>/<prefix>/<slug>/content.md  -- raw README Markdown (== module.readme, D6)
  *   <outDir>/<prefix>/manifest.json      -- module/slug list + TOC outlines + prev/next (D5)
  *   <outDir>/versions.json   -- version manifest (INV-010)
  *   <outDir>/sitemap.xml     -- canonical-indexable route set (SEO-3)
  *   <outDir>/robots.txt      -- allow-all + sitemap directive (SEO-3)
  *   <outDir>/CNAME           -- exactly "getkyo.io" (INV-011)
  *   <outDir>/.nojekyll       -- empty (INV-011)
  *   <outDir>/kyo.png         -- logo (copied from repo root)
  *   <outDir>/kyo.ico         -- favicon (copied from kyo-website/assets/)
  *   <outDir>/main.js         -- bundle (copied from bundleDir)
  *   <outDir>/main.js.map     -- bundle source map (copied from bundleDir)
  * }}}
  */
object WebsiteGenerator:

    /** Filesystem paths the generator reads from, separate from the output target.
      *
      * `repoRoot` and `bundleDir` are inputs the deploy workflow supplies (the repo checkout and
      * the compiled `fullLinkJS` directory); they are not part of the `(content, outDir)` emit
      * contract. Carrying them in a typed config keeps the public `emit` signature to the two
      * arguments that vary per call while still naming the extra paths.
      *
      * @param repoRoot
      *   Absolute path to the repository root, used to locate `kyo.png` and
      *   `kyo-website/assets/kyo.ico`.
      * @param bundleDir
      *   Directory containing the compiled JS bundle (`main.js` and `main.js.map`). Passed in so
      *   the deploy workflow can supply the real `fullLinkJS` path; tests use a temp stub.
      */
    final case class Config(repoRoot: Path, bundleDir: Path) derives CanEqual

    /** Emits all Phase-4 artifact files into `outDir`.
      *
      * @param content
      *   The list of documentation versions to include in the version manifest and the version
      *   dropdown. May be empty; artifact-root files are always written.
      * @param outDir
      *   The root of the output directory tree. Created if it does not exist (`Path.write`
      *   default `createFolders=true`).
      * @param config
      *   The input paths (`repoRoot`, `bundleDir`) the generator copies assets from.
      */
    def emit(
        content: Chunk[WebsiteContent],
        outDir: Path,
        config: Config
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        val versions  = content.map(_.version)
        val latestTag = pickLatest(content).map(_.version.tag)
        for
            // DECISION-SEO-C: sitemap `<lastmod>` is the build date, computed ONCE here and threaded
            // to the pure builders so the emitted artifacts are deterministic for a given run.
            lastmod <- Sync.defer(java.time.LocalDate.now().toString)
            _       <- emitLanding(content, versions, outDir)
            _       <- emit404(content, versions, outDir)
            _       <- Kyo.foreachDiscard(content)(c => emitVersion(c, versions, latestTag, outDir))
            _       <- emitLatest(content, versions, outDir)
            _       <- writeVersionsJson(versions, outDir)
            _       <- writeArtifactRootFiles(outDir)
            _       <- writeSitemap(content, lastmod, outDir)
            _       <- writeRobots(outDir)
            _       <- copyAssets(outDir, config.repoRoot, config.bundleDir)
        yield ()
        end for
    end emit

    // ---- Private helpers: docs routes (Phase 7) ----

    /** Emit one version's full route set under `prefix` (the version's own tag, e.g. `v1.0.0-RC2`):
      *   - `<prefix>/index.html`: the version overview page (chrome + the transpiled root-README intro).
      *   - `<prefix>/content.md`: the raw root-README intro Markdown (== `content.intro`), the single
      *     source the SPA fetches and re-transpiles client-side for the overview (D6/INV-009).
      *   - `<prefix>/<slug>/index.html`: each module's docs page (chrome + transpiled UI article).
      *   - `<prefix>/<slug>/content.md`: the raw README Markdown (== `module.readme`), the single
      *     source the SPA fetches and re-transpiles client-side (D6/INV-009).
      *   - `<prefix>/manifest.json`: the module/slug list with TOC outlines and prev/next order.
      *
      * An intro-only version (empty groups, INV-007) emits its overview page (and its content.md) and
      * an empty manifest, with zero `<slug>` pages and zero per-module `content.md` files.
      */
    private def emitDocs(
        c: WebsiteContent,
        versions: Chunk[WebsiteVersion],
        prefix: String,
        outDir: Path,
        isCurrentLatest: Boolean
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        val modules = c.groups.flatMap(_.modules)
        for
            _ <- emitIntroPage(c, versions, prefix, outDir, isCurrentLatest)
            _ <- Kyo.foreachDiscard(modules)(m => emitModulePage(c, versions, prefix, m, outDir, isCurrentLatest))
            _ <- writeManifest(c, prefix, outDir)
        yield ()
        end for
    end emitDocs

    private def emitVersion(
        c: WebsiteContent,
        versions: Chunk[WebsiteVersion],
        latestTag: Maybe[String],
        outDir: Path
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        // DECISION-SEO-A: the current-latest version's own versioned tree (/v<X>/<slug>/) duplicates
        // /latest/<slug>/, so its module pages canonicalize to /latest/ rather than self.
        emitDocs(c, versions, c.version.tag, outDir, isCurrentLatest = latestTag.contains(c.version.tag))

    /** Mirror the newest stable version (or the newest pre-release when no stable tag exists, Q-005)
      * under `latest/`, as a duplicate emission (Pages serves files, not symlinks). The mirrored
      * version is re-rendered with the `latest` prefix so its intra-page links resolve under
      * `latest/` and its content carries the latest `WebsiteVersion` record.
      */
    private def emitLatest(
        content: Chunk[WebsiteContent],
        versions: Chunk[WebsiteVersion],
        outDir: Path
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        pickLatest(content) match
            case Absent => ()
            case Present(c) =>
                val latestVersion = c.version.copy(latest = true)
                val latestContent = c.copy(version = latestVersion)
                // The /latest/ tree is the canonical home: every page under it is self-canonical.
                emitDocs(latestContent, versions, "latest", outDir, isCurrentLatest = false)
        end match
    end emitLatest

    /** The version to serve as `latest`. The caller (the deploy flow / `WebsiteMain.parseContent`)
      * marks the chosen version with `version.latest = true`, so that explicit flag wins. When no
      * version carries the flag, fall back to the newest stable (non-pre-release) version, then to the
      * newest pre-release (Q-005). The content `Chunk` is ordered oldest-first by semantic version
      * (`WebsiteMain.listTagDirs` sorts via `WebsiteVersion.tagOrdering`, independent of the deploy
      * workflow's `sort -V`), so "newest" is the last matching entry.
      */
    private def pickLatest(content: Chunk[WebsiteContent]): Maybe[WebsiteContent] =
        val flagged = content.filter(_.version.latest)
        if flagged.nonEmpty then flagged.lastMaybe
        else
            // Stability is owned by WebsiteVersion.parse: a tag is stable iff it parses and carries no
            // pre-release suffix. Reusing it keeps ONE definition of "stable" across the generator and
            // WebsiteMain.pickLatestTag, instead of a second substring-marker list that could drift.
            val stable = content.filter(c => WebsiteVersion.parse(c.version.tag).exists(_.preRelease.isEmpty))
            if stable.nonEmpty then stable.lastMaybe
            else content.lastMaybe
        end if
    end pickLatest

    private def emitIntroPage(
        c: WebsiteContent,
        versions: Chunk[WebsiteVersion],
        prefix: String,
        outDir: Path,
        isCurrentLatest: Boolean
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        val route = s"/$prefix/"
        for
            // The intro route renders the root-README overview as its article (no longer an empty
            // article), so the page is real content: SEO-indexable, with its OWN heading outline driving
            // the rail's Overview sections. The raw `content.intro` is written as the route's content.md
            // so the bundle fetches and re-transpiles it exactly like a module page (D6/INV-009).
            rendered   <- DocsMarkdown.transpile(c.intro)
            fixedRoute <- Signal.initRef(route)
            // The SSG emits one fully-loaded static page per route, so content is never mid-load:
            // `contentLoading` is constant false and the prev/next pager renders exactly as the bundle's
            // first paint does (loadingRef initialised false), keeping SSG and bundle output identical.
            body <- DocsApp.body(c, prefix, fixedRoute, Signal.initConst(rendered.headings), rendered.article, Signal.initConst(false))
            view <- siteShell(versions, docsHome(c, prefix), body)
            html <- wrapFirst(introOpts(c, prefix, route, rendered.headings, isCurrentLatest), view)
            island = docsIsland(c, versions, c.intro)
            _ <- writeRoute(outDir / prefix / "index.html", injectIslands(html, island, versions))
            _ <- writeString(s"$prefix/content.md", outDir / prefix / "content.md", c.intro)
        yield ()
        end for
    end emitIntroPage

    private def emitModulePage(
        c: WebsiteContent,
        versions: Chunk[WebsiteVersion],
        prefix: String,
        module: WebsiteModule,
        outDir: Path,
        isCurrentLatest: Boolean
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        val route = s"/$prefix/${module.slug}/"
        for
            rendered   <- DocsMarkdown.transpile(module.readme)
            fixedRoute <- Signal.initRef(route)
            // Constant-false `contentLoading`: a static SSG page is always loaded (see emitIntroPage).
            body <- DocsApp.body(c, prefix, fixedRoute, Signal.initConst(rendered.headings), rendered.article, Signal.initConst(false))
            view <- siteShell(versions, docsHome(c, prefix), body)
            html <- wrapFirst(docOpts(c, prefix, module.slug, route, isCurrentLatest), view)
            island = docsIsland(c, versions, module.readme)
            _ <- writeRoute(outDir / prefix / module.slug / "index.html", injectIslands(html, island, versions))
            _ <- writeString(
                s"$prefix/${module.slug}/content.md",
                outDir / prefix / module.slug / "content.md",
                module.readme
            )
        yield ()
        end for
    end emitModulePage

    /** Wrap a route's content `body` in the unified `SiteApp` shell for SSG. The header inputs (the
      * versions dropdown, the `docsHome` target, the empty search query and index) are exactly the
      * inputs the bundle passes for the same route, so the server-rendered shell and the bundle's
      * first render produce a structurally identical `data-kyo-path` tree (hydration parity, INV-003).
      * The content signal is constant on SSG (one route is emitted per call); the bundle uses a
      * `SignalRef` updated by its nav fiber.
      */
    private def siteShell(versions: Chunk[WebsiteVersion], docsHome: String, body: UI)(using Frame): UI < Sync =
        for
            queryRef <- Signal.initRef("")
            view <- SiteApp.view(
                versions,
                docsHome,
                Signal.initConst(DocsSearch.Index(Chunk.empty)),
                queryRef,
                // The SSG renders one static route: there is no client-side navigation at build time,
                // so search Enter-selection and the lazy heading-index fetch are both no-ops. The
                // bundle supplies the live UILocation.push + the manifest fetch.
                (_: String) => Kyo.unit,
                Kyo.unit,
                Signal.initConst(body)
            )
        yield view

    /** The header "Get started" target for content `c` served under `prefix`: the first module's route
      * `/<prefix>/<firstSlug>/`, falling back to the prefix root `/<prefix>/` when the version has no
      * modules. `SiteApp` derives the "Docs"/"Modules" target (the overview intro route `/<prefix>/`)
      * from this string's first path segment. Reused by every docs emit path and mirrored by the
      * bundle's own `docsHome` computation so SSG and bundle agree (the header target must be identical
      * for parity).
      */
    private def docsHome(c: WebsiteContent, prefix: String): String =
        c.groups.flatMap(_.modules).headOption.fold(s"/$prefix/")(m => s"/$prefix/${m.slug}/")

    private def docOpts(
        c: WebsiteContent,
        prefix: String,
        slug: String,
        route: String,
        isCurrentLatest: Boolean
    ): WebsitePage.Options =
        val title         = s"$slug | Kyo docs ${c.version.label}"
        val selfCanonical = s"https://getkyo.io$route"
        // DECISION-SEO-A: the current-latest version's own versioned module pages (/v<X>/<slug>/)
        // duplicate /latest/<slug>/, so they canonicalize to the /latest/ home instead of self. The
        // /latest/ tree (prefix == "latest") and every other versioned/intro page stays self-canonical.
        val canonical =
            if isCurrentLatest && prefix != "latest" then s"https://getkyo.io/latest/$slug/"
            else selfCanonical
        WebsitePage.Options(
            title = title,
            description = s"Kyo ${c.version.label} documentation.",
            canonical = canonical,
            bundleHref = "/main.js",
            jsonLd = buildJsonLd("docs", title, selfCanonical),
            noindex = false
        )
    end docOpts

    /** Build the page options for the intro/overview route `/<prefix>/`. The intro now renders the
      * root-README overview as a real article (no longer thin), so unlike its former empty form it is
      * SEO-indexable:
      *   - The `/latest/` intro is self-canonical and added to the sitemap (see [[sitemapRoutes]]).
      *   - The current-latest version's own `/v<X>/` intro duplicates `/latest/`, so it canonicalizes
      *     to `/latest/` (DECISION-SEO-A, the same dedup rule the module pages follow) and stays out of
      *     the sitemap. A past-version intro is self-canonical.
      *
      * The page `<title>` is the intro's H1 text when present, else "Overview", suffixed with
      * `| Kyo docs <label>`.
      */
    private def introOpts(
        c: WebsiteContent,
        prefix: String,
        route: String,
        headings: Chunk[DocsMarkdown.Heading],
        isCurrentLatest: Boolean
    ): WebsitePage.Options =
        val heading       = headings.find(_.level == 1).map(_.text).getOrElse("Overview")
        val title         = s"$heading | Kyo docs ${c.version.label}"
        val selfCanonical = s"https://getkyo.io$route"
        val canonical =
            if isCurrentLatest && prefix != "latest" then s"https://getkyo.io/latest/"
            else selfCanonical
        WebsitePage.Options(
            title = title,
            description = s"Kyo ${c.version.label} documentation.",
            canonical = canonical,
            bundleHref = "/main.js",
            jsonLd = buildJsonLd("intro", title, selfCanonical),
            noindex = false
        )
    end introOpts

    /** Build the schema.org JSON-LD payload for a page (SEO-5). `kind` is `"landing"`, `"docs"`, or
      * `"intro"`. `title` is the page headline; `url` is the page's own self URL. The landing emits a
      * `@graph` with `WebSite` + `SoftwareSourceCode`; docs and intro pages emit a single `TechArticle`
      * that is `isPartOf` the site `WebSite`. All string fields are JSON-escaped via `escJson`.
      */
    private def buildJsonLd(kind: String, title: String, url: String): String =
        kind match
            case "landing" =>
                s"""{"@context": "https://schema.org", "@graph": [""" +
                    s"""{"@type": "WebSite", "name": "Kyo", "url": "https://getkyo.io/"}, """ +
                    s"""{"@type": "SoftwareSourceCode", "name": "Kyo", "url": "https://getkyo.io/", """ +
                    s""""codeRepository": "https://github.com/getkyo/kyo", "programmingLanguage": "Scala"}]}"""
            case _ =>
                s"""{"@context": "https://schema.org", "@type": "TechArticle", """ +
                    s""""headline": "${escJson(title)}", "url": "${escJson(url)}", "inLanguage": "en", """ +
                    s""""isPartOf": {"@type": "WebSite", "name": "Kyo", "url": "https://getkyo.io/"}}"""
        end match
    end buildJsonLd

    /** Serialize the version's modules to `<prefix>/manifest.json` as a flat JSON array of module
      * objects, each carrying `slug`, `group`, `title`, `prev`, `next`, and a `toc` outline. The flat
      * array is what `DocsClient.routeTable` parses (its `splitJsonArray` is depth-aware and its
      * `extractString` reads the leading string fields); the extra `prev`/`next`/`toc` fields are the
      * route-table metadata the SPA router consumes.
      */
    private def writeManifest(c: WebsiteContent, prefix: String, outDir: Path)(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        val modules = c.groups.flatMap(_.modules)
        for
            entries <- Kyo.foreach(modules.toSeq.zipWithIndex) { case (m, i) =>
                val prev = if i > 0 then Present(modules(i - 1).slug) else Absent
                val next = if i < modules.size - 1 then Present(modules(i + 1).slug) else Absent
                DocsMarkdown.transpile(m.readme).map(r => manifestEntry(m, prev, next, r.headings))
            }
            json = if entries.isEmpty then "[]" else entries.mkString("[\n", ",\n", "\n]")
            _ <- writeString(s"$prefix/manifest.json", outDir / prefix / "manifest.json", json)
        yield ()
        end for
    end writeManifest

    private def manifestEntry(
        m: WebsiteModule,
        prev: Maybe[String],
        next: Maybe[String],
        toc: Chunk[DocsMarkdown.Heading]
    ): String =
        val tocJson = toc.toSeq.map { h =>
            s"""{"level": ${h.level}, "text": "${escJson(h.text)}", "slug": "${escJson(h.slug)}"}"""
        }.mkString("[", ", ", "]")
        val prevJson = prev.map(s => s""""${escJson(s)}"""").getOrElse("null")
        val nextJson = next.map(s => s""""${escJson(s)}"""").getOrElse("null")
        s"""  {"slug": "${escJson(m.slug)}", "group": "${escJson(m.group)}", "title": "${escJson(
                m.title
            )}", "prev": $prevJson, "next": $nextJson, "toc": $tocJson}"""
    end manifestEntry

    // ---- Boot islands (Phase 6 forward-dep: the schema DocsClient parses) ----

    /** Build the `#docs-island` JSON payload for a route: the version record, intro, grouped modules
      * (slug/group/title), the full version list, and the route's raw Markdown. `WebsiteBundleMain`
      * reads this at bundle entry to seed the SPA with the current page's content before navigation.
      */
    private def docsIsland(c: WebsiteContent, versions: Chunk[WebsiteVersion], markdown: String): String =
        val v          = c.version
        val versionObj = s"""{"tag": "${escJson(v.tag)}", "label": "${escJson(v.label)}", "latest": ${v.latest}}"""
        val groupsJson = c.groups.toSeq.map { g =>
            val mods = g.modules.toSeq.map { m =>
                s"""{"slug": "${escJson(m.slug)}", "group": "${escJson(m.group)}", "title": "${escJson(m.title)}"}"""
            }.mkString("[", ", ", "]")
            s"""{"name": "${escJson(g.name)}", "modules": $mods}"""
        }.mkString("[", ", ", "]")
        s"""{"version": $versionObj, "intro": "${escJson(c.intro)}", "groups": $groupsJson, "versions": ${buildVersionsJson(
                versions
            )}, "markdown": "${escJson(markdown)}"}"""
    end docsIsland

    /** Inject the `#docs-island` and `#versions-island` `<script type="application/json">` elements
      * immediately before `</body>` in the rendered SSG HTML. The JSON has its angle brackets escaped
      * to their JSON unicode escapes (via escScript) so a `</script>` substring in any field cannot
      * close the element early. This is a string-level SSG concern, not a kyo-ui API or a `UI.rawHtml`
      * use, so the INV-005/INV-012 raw-HTML boundary (the transpiler's inline-HTML node) is preserved.
      */
    private def injectIslands(html: String, docsIslandJson: String, versions: Chunk[WebsiteVersion]): String =
        val versionsIslandJson = buildVersionsJson(versions)
        val scripts =
            s"""<script type="application/json" id="docs-island">${escScript(docsIslandJson)}</script>""" +
                s"""<script type="application/json" id="versions-island">${escScript(versionsIslandJson)}</script>"""
        val marker = "</body>"
        val idx    = html.lastIndexOf(marker)
        if idx < 0 then html + scripts
        else html.substring(0, idx) + scripts + html.substring(idx)
    end injectIslands

    private def escScript(json: String): String =
        json.replace("<", "\\u003c").replace(">", "\\u003e")

    // ---- Private helpers ----

    private def emitLanding(
        content: Chunk[WebsiteContent],
        versions: Chunk[WebsiteVersion],
        outDir: Path
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        val opts = WebsitePage.Options(
            title = "Kyo | Build with AI. Ship something that holds.",
            description =
                "Kyo is the reliability foundation for AI-built software: structured effects, typed errors, and production-grade concurrency on JVM, JS, and Native.",
            canonical = "https://getkyo.io/",
            bundleHref = "main.js",
            jsonLd = buildJsonLd("landing", "Kyo", "https://getkyo.io/")
        )
        // The landing's header Docs/Modules/Get-started target and its seeded island are the latest
        // version's first module under `latest/`. The same content seeds the #docs-island so the
        // bundle hydrates `/` with the same docsHome the SSG header used (D4: islands now ship on `/`).
        val latest      = pickLatest(content)
        val landingHome = latest.fold("/")(c => docsHome(c, "latest"))
        for
            body <- LandingApp.body(landingHome)
            view <- siteShell(versions, landingHome, body)
            html <- wrapFirst(opts, view)
            island      = latest.fold("")(c => docsIsland(c.copy(version = c.version.copy(latest = true)), versions, ""))
            withIslands = if island.isEmpty then html else injectIslands(html, island, versions)
            _ <- writeRoute(outDir / "index.html", withIslands)
        yield ()
        end for
    end emitLanding

    /** Emit a styled `404.html` at the site root (B14). GitHub Pages serves this file for any unknown
      * path, so an off-tree deep-link (AF-4) lands on the unified shell carrying a "page not found"
      * message and a link home rather than a bare server 404. The page is noindex (it is an error
      * surface, not content) and is intentionally absent from the sitemap. The header uses the latest
      * version's docsHome so its nav targets resolve, matching the landing.
      */
    private def emit404(
        content: Chunk[WebsiteContent],
        versions: Chunk[WebsiteVersion],
        outDir: Path
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        val latest = pickLatest(content)
        val home   = latest.fold("/")(c => docsHome(c, "latest"))
        // No bundle on the 404: GitHub Pages serves this file for an unknown URL while the browser
        // address bar keeps that unknown path, so booting the SPA there would classify the path as
        // off-tree and full-navigate in a loop, blanking the static "page not found" content. Leaving
        // the bundle off keeps the styled static page as the terminal surface; the "Back to home" link
        // re-enters the SPA cleanly.
        val opts = WebsitePage.Options(
            title = "Page not found | Kyo",
            description = "The page you are looking for does not exist.",
            canonical = "",
            bundleHref = "",
            noindex = true
        )
        val body =
            UI.div.cssClass("notfound")(
                UI.div.cssClass("notfound-code")("404"),
                UI.h1.cssClass("notfound-title")("Page not found"),
                UI.p.cssClass("notfound-text")(
                    UI.span("The page you are looking for does not exist or has moved.")
                ),
                UI.a.cssClass("btn").cssClass("btn-primary").href(UI.Href.Path("/"))("Back to home")
            )
        for
            view <- siteShell(versions, home, body)
            html <- wrapFirst(opts, view)
            _    <- writeRoute(outDir / "404.html", html)
        yield ()
        end for
    end emit404

    private def wrapFirst(opts: WebsitePage.Options, view: UI)(using Frame): String < Async =
        WebsitePage.wrap(opts)(view).take(1).run.map(_.headMaybe.getOrElse(""))

    private def writeRoute(path: Path, html: String)(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        Abort.run[FileWriteException](path.write(html)).map {
            case Result.Success(_) => ()
            case Result.Failure(e) => Abort.fail(WebsiteEmitException(path.toString, e))
            case p: Result.Panic   => Abort.error(p)
        }

    private def writeString(route: String, path: Path, content: String)(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        Abort.run[FileWriteException](path.write(content)).map {
            case Result.Success(_) => ()
            case Result.Failure(e) => Abort.fail(WebsiteEmitException(route, e))
            case p: Result.Panic   => Abort.error(p)
        }

    private def writeVersionsJson(
        versions: Chunk[WebsiteVersion],
        outDir: Path
    )(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        val json = buildVersionsJson(versions)
        writeString("versions.json", outDir / "versions.json", json)
    end writeVersionsJson

    private def buildVersionsJson(versions: Chunk[WebsiteVersion]): String =
        if versions.isEmpty then "[]"
        else
            val entries = versions.toSeq.map { v =>
                s"""  {"tag": "${escJson(v.tag)}", "label": "${escJson(v.label)}", "latest": ${v.latest}}"""
            }
            entries.mkString("[\n", ",\n", "\n]")
        end if
    end buildVersionsJson

    private def escJson(s: String): String =
        val sb = new java.lang.StringBuilder(s.length + 8)
        var i  = 0
        while i < s.length do
            val c = s.charAt(i)
            c match
                case '\\' => sb.append("\\\\"); ()
                case '"'  => sb.append("\\\""); ()
                case '\n' => sb.append("\\n"); ()
                case '\r' => sb.append("\\r"); ()
                case '\t' => sb.append("\\t"); ()
                case _ if c < 0x20 =>
                    val hex = Integer.toHexString(c.toInt)
                    if hex.length == 1 then sb.append("\\u000").append(hex)
                    else sb.append("\\u00").append(hex)
                    ()
                case _ => sb.append(c); ()
            end match
            i += 1
        end while
        sb.toString
    end escJson

    private def writeArtifactRootFiles(outDir: Path)(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        for
            _ <- writeString("CNAME", outDir / "CNAME", "getkyo.io")
            _ <- writeString(".nojekyll", outDir / ".nojekyll", "")
        yield ()

    // ---- sitemap.xml + robots.txt (SEO-3) ----

    /** The canonical-indexable route set (DECISION-SEO-A): the landing root `/`, the `/latest/` overview
      * (the root-README intro, now real indexable content), plus each `/latest/<slug>/` for the
      * picked-latest version's modules. This is the single source of truth for sitemap.xml, so the
      * sitemap never lists the duplicate `/v<current>/<slug>/` versioned tree, the duplicate
      * `/v<current>/` intro, historical versions, or any non-page file. The `/latest/` overview is only
      * added when a latest version exists (so empty content still lists just the root).
      */
    private def sitemapRoutes(content: Chunk[WebsiteContent]): Chunk[String] =
        Chunk("/") ++ pickLatest(content).fold(Chunk.empty)(c =>
            Chunk("/latest/") ++ c.groups.flatMap(_.modules).map(m => s"/latest/${m.slug}/")
        )

    /** Build the sitemap.xml document. `routes` are absolute-URL paths (each starting with `/`);
      * `lastmod` is a `YYYY-MM-DD` build date (DECISION-SEO-C). Pure and deterministic so tests can
      * inject a fixed date. Each `<loc>` is `https://getkyo.io` + route.
      */
    private def buildSitemapXml(routes: Chunk[String], lastmod: String): String =
        val urls = routes.toSeq.map { route =>
            s"""  <url>
               |    <loc>https://getkyo.io$route</loc>
               |    <lastmod>$lastmod</lastmod>
               |  </url>""".stripMargin
        }.mkString("\n")
        s"""<?xml version="1.0" encoding="UTF-8"?>
           |<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
           |$urls
           |</urlset>""".stripMargin
    end buildSitemapXml

    /** Build the robots.txt document: allow all crawlers (no `Disallow`, so the AI crawlers
      * GPTBot/ClaudeBot/PerplexityBot can read the server-rendered HTML for SEO-1) and declare the
      * sitemap URL. Pure.
      */
    private def buildRobotsTxt(): String =
        """User-agent: *
          |Allow: /
          |Sitemap: https://getkyo.io/sitemap.xml
          |""".stripMargin

    private def writeSitemap(
        content: Chunk[WebsiteContent],
        lastmod: String,
        outDir: Path
    )(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        writeString("sitemap.xml", outDir / "sitemap.xml", buildSitemapXml(sitemapRoutes(content), lastmod))

    private def writeRobots(outDir: Path)(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        writeString("robots.txt", outDir / "robots.txt", buildRobotsTxt())

    private def copyFile(route: String, src: Path, dst: Path)(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        Abort.run[FileFsException](src.copy(dst, replaceExisting = true)).map {
            case Result.Success(_) => ()
            case Result.Failure(e) => Abort.fail(WebsiteEmitException(route, e))
            case p: Result.Panic   => Abort.error(p)
        }

    private def copyAssets(
        outDir: Path,
        repoRoot: Path,
        bundleDir: Path
    )(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        for
            _ <- copyFile("kyo.png", repoRoot / "kyo.png", outDir / "kyo.png")
            _ <- copyFile("kyo.ico", repoRoot / "kyo-website" / "assets" / "kyo.ico", outDir / "kyo.ico")
            _ <- copyFile("main.js", bundleDir / "main.js", outDir / "main.js")
            _ <- copyFile("main.js.map", bundleDir / "main.js.map", outDir / "main.js.map")
        yield ()

end WebsiteGenerator
