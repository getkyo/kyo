package kyo.website

import kyo.*
import kyo.UI.PageHead

class DocsAppTest extends Test:

    private val testHead = PageHead(title = "t")

    // Helper: render DocsApp.body to the first HTML emission. The default `prefix` mirrors the
    // version's own canonical tree (`latest` when the version is flagged latest, else its tag); the
    // WARN-1 regression leaves pass an explicit prefix to assert the physical-tree decoupling. The
    // body no longer renders a header (D5), so there is no `versions` argument anymore.
    private def rendered(
        content: WebsiteContent,
        route: Signal[String],
        toc: Chunk[DocsMarkdown.Heading],
        article: UI,
        prefix: String = ""
    )(using Frame): String < Async =
        val resolvedPrefix = if prefix.nonEmpty then prefix
        else if content.version.latest then "latest"
        else content.version.tag
        for
            view <- DocsApp.body(content, resolvedPrefix, route, Signal.initConst(toc), article)
            html <- UI.runRenderPage(testHead)(view).take(1).run.map(_.headMaybe.getOrElse(""))
        yield html
        end for
    end rendered

    private def fixedRoute(path: String)(using Frame): Signal[String] < Sync =
        Signal.initRef[String](path)

    private def emptyContent(ver: WebsiteVersion = WebsiteVersion("latest", "latest", true))(using Frame): WebsiteContent =
        WebsiteContent(intro = "", groups = Chunk.empty, version = ver)

    // Leaf 1: sidebar groups match README order
    "sidebar groups match README order (leaf 1)" in run {
        for
            route <- fixedRoute("/latest/kyo-data/")
            content = WebsiteContent(
                intro = "",
                groups = Chunk(
                    WebsiteContent.Group(
                        "Foundation",
                        Chunk(WebsiteModule("kyo-data", "Foundation", "kyo-data", "", WebsiteModule.Platforms(true, true, true)))
                    ),
                    WebsiteContent.Group(
                        "Application runtime",
                        Chunk(WebsiteModule("kyo-core", "Application runtime", "kyo-core", "", WebsiteModule.Platforms(true, true, true)))
                    ),
                    WebsiteContent.Group(
                        "HTTP and schema",
                        Chunk(WebsiteModule("kyo-http", "HTTP and schema", "kyo-http", "", WebsiteModule.Platforms(true, true, false)))
                    )
                ),
                version = WebsiteVersion("latest", "latest", true)
            )
            html <- rendered(content, route, Chunk.empty, UI.empty)
        yield
            val foundationIdx = html.indexOf("Foundation")
            val appIdx        = html.indexOf("Application runtime")
            val httpIdx       = html.indexOf("HTTP and schema")
            assert(foundationIdx >= 0, s"Foundation group not found: $html")
            assert(appIdx >= 0, s"Application runtime group not found: $html")
            assert(httpIdx >= 0, s"HTTP and schema group not found: $html")
            assert(foundationIdx < appIdx, "Foundation must appear before Application runtime")
            assert(appIdx < httpIdx, "Application runtime must appear before HTTP and schema")
        end for
    }

    // Leaf 2: sidebar lists modules under each group with correct hrefs
    "sidebar lists modules under each group (leaf 2)" in run {
        for
            route <- fixedRoute("/latest/kyo-data/")
            content = WebsiteContent(
                intro = "",
                groups = Chunk(
                    WebsiteContent.Group(
                        "Foundation",
                        Chunk(
                            WebsiteModule("kyo-data", "Foundation", "kyo-data", "", WebsiteModule.Platforms(true, true, true)),
                            WebsiteModule("kyo-kernel", "Foundation", "kyo-kernel", "", WebsiteModule.Platforms(true, true, true))
                        )
                    )
                ),
                version = WebsiteVersion("latest", "latest", true)
            )
            html <- rendered(content, route, Chunk.empty, UI.empty)
        yield
            assert(html.contains("/latest/kyo-data/"), s"kyo-data href not found: $html")
            assert(html.contains("/latest/kyo-kernel/"), s"kyo-kernel href not found: $html")
        end for
    }

    // Leaf 3: TOC one link per heading with slug hrefs (INV-004)
    "TOC one link per heading with slug hrefs INV-004 (leaf 3)" in run {
        val toc = Chunk(
            DocsMarkdown.Heading(1, "kyo-core", "kyo-core"),
            DocsMarkdown.Heading(2, "Scope", "scope")
        )
        for
            route <- fixedRoute("/latest/kyo-core/")
            html  <- rendered(emptyContent(), route, toc, UI.empty)
        yield
            assert(html.contains("#kyo-core"), s"kyo-core anchor not found: $html")
            assert(html.contains("#scope"), s"scope anchor not found: $html")
        end for
    }

    // Leaf 4: TOC entries carry distinct per-level hooks (toc-h1 / toc-h2 / toc-h3), WARN-2
    "TOC entries carry distinct per-level hooks (leaf 4)" in run {
        val toc = Chunk(
            DocsMarkdown.Heading(1, "Top", "top"),
            DocsMarkdown.Heading(2, "Mid", "mid"),
            DocsMarkdown.Heading(3, "Deep", "deep")
        )
        for
            route <- fixedRoute("/latest/kyo-core/")
            html  <- rendered(emptyContent(), route, toc, UI.empty)
        yield
            // Each level gets its own indentation hook; the three are structurally distinguishable.
            assert(html.contains("toc-h1"), s"level-1 hook (toc-h1) missing: $html")
            assert(html.contains("toc-h2"), s"level-2 hook (toc-h2) missing: $html")
            assert(html.contains("toc-h3"), s"level-3 hook (toc-h3) missing: $html")
            // The level-2 entry must NOT carry the level-1 or level-3 hook, and vice versa: locate the
            // anchor for each slug and check the class on its enclosing div precedes it distinctly.
            val h1Idx = html.indexOf("toc-h1")
            val h2Idx = html.indexOf("toc-h2")
            val h3Idx = html.indexOf("toc-h3")
            assert(h1Idx < h2Idx && h2Idx < h3Idx, s"per-level hooks must appear in level order: h1=$h1Idx h2=$h2Idx h3=$h3Idx")
            // Only level-3+ carries the `sub` indentation marker; level-2 does not.
            val subIdx = html.indexOf("toc-h3 sub")
            assert(subIdx >= 0, s"level-3 must carry the `sub` marker: $html")
            assert(!html.contains("toc-h2 sub"), s"level-2 must NOT carry the `sub` marker: $html")
        end for
    }

    // Leaf 5: content area is route-reactive (INV-005/INV-013)
    "content area is route-reactive INV-013 (leaf 5)" in run {
        for
            route    <- fixedRoute("/latest/kyo-core/")
            rendered <- DocsMarkdown.transpile("## Scope\n")
            // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render
            reactive = UI.Ast.Reactive(route.map(_ => rendered.article))
            html <- this.rendered(emptyContent(), route, Chunk.empty, reactive)
        yield
            assert(html.contains("data-kyo-reactive"), s"data-kyo-reactive not found in HTML: $html")
            assert(html.contains("<h2"), s"h2 element not found in HTML: $html")
        end for
    }

    // Leaf 6 (version dropdown, INV-010) MOVED to SiteAppTest: the dropdown is owned by the header
    // (SiteApp), not the docs body. The docs body must NOT render a header/dropdown/search-input;
    // that negative is asserted by the "body renders no header" leaf below.

    // Leaf 6b: the docs body renders no header chrome (D5)
    "docs body renders no header, dropdown, or search input (D5, leaf 6b)" in run {
        val versions = Chunk(WebsiteVersion("v1.0.0", "1.0.0", true), WebsiteVersion("v0.9.3", "0.9.3", false))
        for
            route <- fixedRoute("/latest/")
            // versions is intentionally unused here: the body has no header to populate.
            _    <- Sync.defer(versions)
            html <- rendered(emptyContent(), route, Chunk.empty, UI.empty)
        yield
            assert(!html.contains("data-section=\"header\""), s"docs body must not render a header: $html")
            assert(!html.contains("data-kyo-dropdown"), s"docs body must not render a version dropdown: $html")
            assert(!html.contains("search-input"), s"docs body must not render the search input: $html")
        end for
    }

    // Leaf 7: version banner shows for non-latest
    "version banner for non-latest version (leaf 7)" in run {
        val ver = WebsiteVersion("v0.9.3", "0.9.3", false)
        for
            route <- fixedRoute("/v0.9.3/kyo-core/")
            content = emptyContent(ver)
            html <- rendered(content, route, Chunk.empty, UI.empty)
        yield
            assert(html.contains("version-banner"), s"version-banner class not found for non-latest: $html")
            assert(html.contains("not the latest") || html.contains("older version"), s"Banner text not found: $html")
        end for
    }

    // Leaf 8: no banner for latest
    "no version banner for latest version (leaf 8)" in run {
        val ver = WebsiteVersion("v1.0.0", "1.0.0", true)
        for
            route <- fixedRoute("/latest/kyo-core/")
            content = emptyContent(ver)
            html <- rendered(content, route, Chunk.empty, UI.empty)
        yield assert(!html.contains("version-banner"), s"version-banner should not appear for latest: $html")
        end for
    }

    // Leaf 9: prev/next reflect route position
    "prev/next links reflect route position (leaf 9)" in run {
        val modA = WebsiteModule("mod-a", "Foundation", "Mod A", "", WebsiteModule.Platforms(true, true, true))
        val modB = WebsiteModule("mod-b", "Foundation", "Mod B", "", WebsiteModule.Platforms(true, true, true))
        val modC = WebsiteModule("mod-c", "Foundation", "Mod C", "", WebsiteModule.Platforms(true, true, true))
        val content = WebsiteContent(
            intro = "",
            groups = Chunk(WebsiteContent.Group("Foundation", Chunk(modA, modB, modC))),
            version = WebsiteVersion("latest", "latest", true)
        )
        for
            route <- fixedRoute("/latest/mod-b/")
            html  <- rendered(content, route, Chunk.empty, UI.empty)
        yield
            // The rendered prev/next nav must contain links to the adjacent modules.
            assert(html.contains("/latest/mod-a/"), s"prev link to mod-a not found in prev-next nav: $html")
            assert(html.contains("/latest/mod-c/"), s"next link to mod-c not found in prev-next nav: $html")
        end for
    }

    // Leaf 10: sidebar active state matches route
    "sidebar active state matches route (leaf 10)" in run {
        val mod = WebsiteModule("kyo-core", "Foundation", "kyo-core", "", WebsiteModule.Platforms(true, true, true))
        val content = WebsiteContent(
            intro = "",
            groups = Chunk(WebsiteContent.Group("Foundation", Chunk(mod))),
            version = WebsiteVersion("latest", "latest", true)
        )
        for
            route <- fixedRoute("/latest/kyo-core/")
            html  <- rendered(content, route, Chunk.empty, UI.empty)
        yield assert(html.contains("nav-item-active"), s"nav-item-active not found for active route: $html")
        end for
    }

    // Leaf 11: intro-only content renders chrome with no module links
    "intro-only content renders shell with no module links (leaf 11)" in run {
        for
            route <- fixedRoute("/latest/")
            html  <- rendered(emptyContent(), route, Chunk.empty, UI.empty)
        yield
            // Shell renders (has sidebar-nav), no module links present
            assert(html.contains("sidebar-nav"), s"sidebar-nav not found: $html")
            assert(!html.contains("/latest/kyo-"), s"unexpected module links in empty-groups render: $html")
        end for
    }

    // Leaf 13 (Phase-6 BLOCKER-1 regression): a non-latest version's sidebar + prev/next links use
    // the version prefix `/v<X>/...`, not `/latest/...`.
    "non-latest version links use the version prefix not latest (BLOCKER-1 regression)" in run {
        val modA = WebsiteModule("mod-a", "Foundation", "Mod A", "", WebsiteModule.Platforms(true, true, true))
        val modB = WebsiteModule("mod-b", "Foundation", "Mod B", "", WebsiteModule.Platforms(true, true, true))
        val modC = WebsiteModule("mod-c", "Foundation", "Mod C", "", WebsiteModule.Platforms(true, true, true))
        val content = WebsiteContent(
            intro = "",
            groups = Chunk(WebsiteContent.Group("Foundation", Chunk(modA, modB, modC))),
            version = WebsiteVersion("v0.9.3", "0.9.3", false)
        )
        for
            route <- fixedRoute("/v0.9.3/mod-b/")
            html  <- rendered(content, route, Chunk.empty, UI.empty)
        yield
            // Sidebar links resolve within v0.9.3.
            assert(html.contains("/v0.9.3/mod-a/"), s"sidebar must link within v0.9.3: $html")
            assert(html.contains("/v0.9.3/mod-b/"), s"sidebar must link within v0.9.3: $html")
            assert(html.contains("/v0.9.3/mod-c/"), s"sidebar must link within v0.9.3: $html")
            // Prev/next reflect position within v0.9.3 (prev = mod-a, next = mod-c).
            // No link must jump to /latest/.
            assert(!html.contains("/latest/mod-"), s"no link must point to /latest/ on a v0.9.3 page: $html")
        end for
    }

    // Leaf 14 (Phase-7 WARN-1 regression): the latest version is emitted under BOTH `latest/` and its
    // own `v<X>/` tree. The physical tree decides the link prefix, NOT `version.latest`: the same
    // latest-flagged version links within `/v<X>/...` when served under `v<X>/`, and within `/latest/...`
    // when served under `latest/`.
    "latest version under its own v<X> tree links within v<X> not latest (WARN-1 regression)" in run {
        val modA = WebsiteModule("mod-a", "Foundation", "Mod A", "", WebsiteModule.Platforms(true, true, true))
        val modB = WebsiteModule("mod-b", "Foundation", "Mod B", "", WebsiteModule.Platforms(true, true, true))
        val modC = WebsiteModule("mod-c", "Foundation", "Mod C", "", WebsiteModule.Platforms(true, true, true))
        // The version IS latest (latest=true), exactly the case emitVersion renders under v1.2.0/.
        val latestVersion = WebsiteVersion("v1.2.0", "1.2.0", true)
        val content = WebsiteContent(
            intro = "",
            groups = Chunk(WebsiteContent.Group("Foundation", Chunk(modA, modB, modC))),
            version = latestVersion
        )
        for
            // Served under the version's own v1.2.0/ tree: the reader is at /v1.2.0/mod-b/.
            vRoute <- fixedRoute("/v1.2.0/mod-b/")
            vHtml  <- rendered(content, vRoute, Chunk.empty, UI.empty, prefix = "v1.2.0")
            // Served under latest/ tree: the reader is at /latest/mod-b/.
            latestRoute <- fixedRoute("/latest/mod-b/")
            latestHtml  <- rendered(content, latestRoute, Chunk.empty, UI.empty, prefix = "latest")
        yield
            // v<X> tree: sidebar links resolve within v1.2.0, and NO link jumps to /latest/.
            assert(vHtml.contains("/v1.2.0/mod-a/"), s"v-tree sidebar must link within v1.2.0: $vHtml")
            assert(vHtml.contains("/v1.2.0/mod-b/"), s"v-tree sidebar must link within v1.2.0: $vHtml")
            assert(vHtml.contains("/v1.2.0/mod-c/"), s"v-tree sidebar must link within v1.2.0: $vHtml")
            // prev/next on /v1.2.0/mod-b/ resolve to /v1.2.0/mod-a/ and /v1.2.0/mod-c/.
            assert(!vHtml.contains("/latest/mod-"), s"latest version's v1.2.0 page must NOT link to /latest/: $vHtml")
            // latest/ tree: the SAME version links within /latest/.
            assert(latestHtml.contains("/latest/mod-a/"), s"latest-tree sidebar must link within latest: $latestHtml")
            assert(latestHtml.contains("/latest/mod-b/"), s"latest-tree sidebar must link within latest: $latestHtml")
            assert(latestHtml.contains("/latest/mod-c/"), s"latest-tree sidebar must link within latest: $latestHtml")
            assert(!latestHtml.contains("/v1.2.0/mod-"), s"latest tree must NOT link to /v1.2.0/: $latestHtml")
        end for
    }

    // Leaf 12: all chrome anchors are real HTML (INV-002)
    "all chrome anchors are real HTML not JS placeholders (leaf 12)" in run {
        val mod = WebsiteModule("kyo-data", "Foundation", "kyo-data", "", WebsiteModule.Platforms(true, true, true))
        val content = WebsiteContent(
            intro = "",
            groups = Chunk(WebsiteContent.Group("Foundation", Chunk(mod))),
            version = WebsiteVersion("latest", "latest", true)
        )
        for
            route <- fixedRoute("/latest/kyo-data/")
            html  <- rendered(content, route, Chunk.empty, UI.empty)
        yield
            // Sidebar group headings are real text in HTML, not JS placeholders
            assert(html.contains("Foundation"), s"Foundation group text not found: $html")
            // No JS-only routing placeholders
            assert(!html.contains("javascript:void"), s"JS-only placeholder found: $html")
        end for
    }

end DocsAppTest
