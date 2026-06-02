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
  *   <outDir>/<prefix>/index.html    -- per-version intro page (prefix = v<X> or latest)
  *   <outDir>/<prefix>/<slug>/index.html  -- per-module docs page (chrome + transpiled article)
  *   <outDir>/<prefix>/<slug>/content.md  -- raw README Markdown (== module.readme, D6)
  *   <outDir>/<prefix>/manifest.json      -- module/slug list + TOC outlines + prev/next (D5)
  *   <outDir>/versions.json   -- version manifest (INV-010)
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
        val versions = content.map(_.version)
        for
            _ <- emitLanding(versions, outDir)
            _ <- Kyo.foreachDiscard(content)(c => emitVersion(c, versions, outDir))
            _ <- emitLatest(content, versions, outDir)
            _ <- writeVersionsJson(versions, outDir)
            _ <- writeArtifactRootFiles(outDir)
            _ <- copyAssets(outDir, config.repoRoot, config.bundleDir)
        yield ()
        end for
    end emit

    // ---- Private helpers: docs routes (Phase 7) ----

    /** Emit one version's full route set under `prefix` (the version's own tag, e.g. `v1.0.0-RC2`):
      *   - `<prefix>/index.html`: the version intro page (chrome + empty article).
      *   - `<prefix>/<slug>/index.html`: each module's docs page (chrome + transpiled UI article).
      *   - `<prefix>/<slug>/content.md`: the raw README Markdown (== `module.readme`), the single
      *     source the SPA fetches and re-transpiles client-side (D6/INV-009).
      *   - `<prefix>/manifest.json`: the module/slug list with TOC outlines and prev/next order.
      *
      * An intro-only version (empty groups, INV-007) emits its intro page and an empty manifest,
      * with zero `<slug>` pages and zero `content.md` files.
      */
    private def emitDocs(
        c: WebsiteContent,
        versions: Chunk[WebsiteVersion],
        prefix: String,
        outDir: Path
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        val modules = c.groups.flatMap(_.modules)
        for
            _ <- emitIntroPage(c, versions, prefix, outDir)
            _ <- Kyo.foreachDiscard(modules)(m => emitModulePage(c, versions, prefix, m, outDir))
            _ <- writeManifest(c, prefix, outDir)
        yield ()
        end for
    end emitDocs

    private def emitVersion(
        c: WebsiteContent,
        versions: Chunk[WebsiteVersion],
        outDir: Path
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        emitDocs(c, versions, c.version.tag, outDir)

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
                emitDocs(latestContent, versions, "latest", outDir)
        end match
    end emitLatest

    /** The version to serve as `latest`. The caller (the deploy flow / `WebsiteMain.parseContent`)
      * marks the chosen version with `version.latest = true`, so that explicit flag wins. When no
      * version carries the flag, fall back to the newest stable (non-pre-release) version, then to the
      * newest pre-release (Q-005). The content `Chunk` is ordered oldest-first (the deploy workflow's
      * `sort -V`), so "newest" is the last matching entry.
      */
    private def pickLatest(content: Chunk[WebsiteContent]): Maybe[WebsiteContent] =
        val flagged = content.filter(_.version.latest)
        if flagged.nonEmpty then flagged.lastMaybe
        else
            val stable = content.filter(c => isStable(c.version.tag))
            if stable.nonEmpty then stable.lastMaybe
            else content.lastMaybe
        end if
    end pickLatest

    private def isStable(tag: String): Boolean =
        val markers = Seq("-RC", "-M", "-SNAPSHOT", "-alpha", "-beta", "-rc")
        !markers.exists(m => tag.contains(m))
    end isStable

    private def emitIntroPage(
        c: WebsiteContent,
        versions: Chunk[WebsiteVersion],
        prefix: String,
        outDir: Path
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        val route = s"/$prefix/"
        for
            fixedRoute <- Signal.initRef(route)
            view       <- DocsApp.view(c, versions, prefix, fixedRoute, Chunk.empty, UI.empty)
            html       <- wrapFirst(docOpts(c, prefix, "", route), view)
            island = docsIsland(c, versions, "")
            _ <- writeRoute(outDir / prefix / "index.html", injectIslands(html, island, versions))
        yield ()
        end for
    end emitIntroPage

    private def emitModulePage(
        c: WebsiteContent,
        versions: Chunk[WebsiteVersion],
        prefix: String,
        module: WebsiteModule,
        outDir: Path
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        val route = s"/$prefix/${module.slug}/"
        for
            rendered   <- DocsMarkdown.transpile(module.readme)
            fixedRoute <- Signal.initRef(route)
            view       <- DocsApp.view(c, versions, prefix, fixedRoute, rendered.headings, rendered.article)
            html       <- wrapFirst(docOpts(c, prefix, module.slug, route), view)
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

    private def docOpts(c: WebsiteContent, prefix: String, slug: String, route: String): WebsitePage.Options =
        val title =
            if slug.isEmpty then s"Kyo docs ${c.version.label}"
            else s"$slug | Kyo docs ${c.version.label}"
        WebsitePage.Options(
            title = title,
            description = s"Kyo ${c.version.label} documentation.",
            canonical = s"https://getkyo.io$route",
            bundleHref = "/main.js",
            bootScenario = "docs"
        )
    end docOpts

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
        versions: Chunk[WebsiteVersion],
        outDir: Path
    )(using Frame): Unit < (Async & Abort[WebsiteException]) =
        val opts = WebsitePage.Options(
            title = "Kyo | Build with AI. Ship something that holds.",
            description =
                "Kyo is the reliability foundation for AI-built software: structured effects, typed errors, and production-grade concurrency on JVM, JS, and Native.",
            canonical = "https://getkyo.io/",
            bundleHref = "main.js",
            bootScenario = "landing"
        )
        for
            view <- LandingApp.view(versions)
            html <- wrapFirst(opts, view)
            _    <- writeRoute(outDir / "index.html", html)
        yield ()
        end for
    end emitLanding

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
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private def writeArtifactRootFiles(outDir: Path)(using Frame): Unit < (Sync & Abort[WebsiteException]) =
        for
            _ <- writeString("CNAME", outDir / "CNAME", "getkyo.io")
            _ <- writeString(".nojekyll", outDir / ".nojekyll", "")
        yield ()

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
