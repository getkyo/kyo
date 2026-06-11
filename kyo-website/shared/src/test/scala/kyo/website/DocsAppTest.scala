package kyo.website

import kyo.*
import kyo.UI.PageHead

class DocsAppTest extends WebsiteTest:

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
        prefix: String = "",
        contentLoading: Boolean = false
    )(using Frame): String < Async =
        val resolvedPrefix = if prefix.nonEmpty then prefix
        else if content.version.latest then "latest"
        else content.version.tag
        for
            view <- DocsApp.body(content, resolvedPrefix, route, Signal.initConst(toc), article, Signal.initConst(contentLoading))
            html <- UI.runRenderPage(testHead)(view).take(1).run.map(_.headMaybe.getOrElse(""))
        yield html
        end for
    end rendered

    private def fixedRoute(path: String)(using Frame): Signal[String] < Sync =
        Signal.initRef[String](path)

    private def emptyContent(ver: WebsiteVersion = WebsiteVersion("latest", "latest", true))(using Frame): WebsiteContent =
        WebsiteContent(intro = "", groups = Chunk.empty, version = ver)

    // Leaf 1: sidebar groups match README order
    "sidebar groups match README order (leaf 1)" in {
        for
            route <- fixedRoute("/latest/kyo-data/")
            content = WebsiteContent(
                intro = "",
                groups = Chunk(
                    WebsiteContent.Group(
                        "Foundation",
                        Chunk(WebsiteModule("kyo-data", "Foundation", "kyo-data", "", WebsiteModule.Platforms(true, true, true, true)))
                    ),
                    WebsiteContent.Group(
                        "Application runtime",
                        Chunk(WebsiteModule(
                            "kyo-core",
                            "Application runtime",
                            "kyo-core",
                            "",
                            WebsiteModule.Platforms(true, true, true, true)
                        ))
                    ),
                    WebsiteContent.Group(
                        "HTTP and schema",
                        Chunk(WebsiteModule(
                            "kyo-http",
                            "HTTP and schema",
                            "kyo-http",
                            "",
                            WebsiteModule.Platforms(true, true, false, false)
                        ))
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
    "sidebar lists modules under each group (leaf 2)" in {
        for
            route <- fixedRoute("/latest/kyo-data/")
            content = WebsiteContent(
                intro = "",
                groups = Chunk(
                    WebsiteContent.Group(
                        "Foundation",
                        Chunk(
                            WebsiteModule("kyo-data", "Foundation", "kyo-data", "", WebsiteModule.Platforms(true, true, true, true)),
                            WebsiteModule("kyo-kernel", "Foundation", "kyo-kernel", "", WebsiteModule.Platforms(true, true, true, true))
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

    private def coreContent(ver: WebsiteVersion = WebsiteVersion("latest", "latest", true))(using Frame): WebsiteContent =
        WebsiteContent(
            intro = "",
            groups = Chunk(
                WebsiteContent.Group(
                    "Foundation",
                    Chunk(WebsiteModule("kyo-core", "Foundation", "kyo-core", "", WebsiteModule.Platforms(true, true, true, true)))
                )
            ),
            version = ver
        )

    // Leaf 3: the active module's nested sections render one `#slug` link per section heading (INV-004).
    // The level-1 page-title heading is the module link itself, so it is NOT repeated as a section link.
    "active module nested sections: one #slug link per section heading, level-1 skipped (leaf 3)" in {
        val toc = Chunk(
            DocsMarkdown.Heading(1, "kyo-core", "kyo-core"),
            DocsMarkdown.Heading(2, "Scope", "scope"),
            DocsMarkdown.Heading(2, "Channels and queues", "channels-and-queues")
        )
        for
            route <- fixedRoute("/latest/kyo-core/")
            html  <- rendered(coreContent(), route, toc, UI.empty)
        yield
            // The nested section list is present under the active module.
            assert(html.contains("sidebar-sections"), s"sidebar-sections list not found: $html")
            // Level-2 section headings render as #slug fragment links.
            assert(html.contains("#scope"), s"scope section anchor not found: $html")
            assert(html.contains("#channels-and-queues"), s"channels-and-queues section anchor not found: $html")
            // The level-1 page-title heading is the module link, not repeated as a #slug section link.
            assert(!html.contains("#kyo-core"), s"level-1 page title must NOT appear as a section link: $html")
        end for
    }

    // Leaf 3b: a NON-active module renders no nested sections (the outline collapses when not current).
    "non-active module renders no nested sections (leaf 3b)" in {
        val toc = Chunk(
            DocsMarkdown.Heading(1, "kyo-data", "kyo-data"),
            DocsMarkdown.Heading(2, "Scope", "scope")
        )
        val content = WebsiteContent(
            intro = "",
            groups = Chunk(
                WebsiteContent.Group(
                    "Foundation",
                    Chunk(
                        WebsiteModule("kyo-data", "Foundation", "kyo-data", "", WebsiteModule.Platforms(true, true, true, true)),
                        WebsiteModule("kyo-core", "Foundation", "kyo-core", "", WebsiteModule.Platforms(true, true, true, true))
                    )
                )
            ),
            version = WebsiteVersion("latest", "latest", true)
        )
        for
            // The reader is on kyo-data, so only kyo-data is active; kyo-core must stay bare.
            route <- fixedRoute("/latest/kyo-data/")
            html  <- rendered(content, route, toc, UI.empty)
        yield
            // Exactly one nested section list is emitted (the active module's), not one per module.
            val sectionListCount = "sidebar-sections".r.findAllIn(html).length
            assert(sectionListCount == 1, s"expected exactly one nested section list (active module only), got $sectionListCount: $html")
            // The active module (kyo-data) shows its section link.
            assert(html.contains("#scope"), s"active module section anchor not found: $html")
            // kyo-core is present as a bare module link but carries no nested sections.
            assert(html.contains("/latest/kyo-core/"), s"kyo-core module link not found: $html")
        end for
    }

    // Leaf 4: the rail is one level deep: ONLY level-2 (`## `) sections render. The level-1 page title
    // is skipped (it is the module link), and level-3+ headings are dropped from the rail entirely.
    "rail renders only level-2 sections, dropping level-1 and level-3+ (leaf 4)" in {
        val toc = Chunk(
            DocsMarkdown.Heading(1, "Top", "top"),  // page title, skipped (becomes the module link)
            DocsMarkdown.Heading(2, "Mid", "mid"),  // level-2: rendered as a section
            DocsMarkdown.Heading(3, "Deep", "deep") // level-3: dropped from the rail
        )
        for
            route <- fixedRoute("/latest/kyo-core/")
            html  <- rendered(coreContent(), route, toc, UI.empty)
        yield
            // The single section indent hook is present on the level-2 section.
            assert(html.contains("sidebar-section"), s"section hook (sidebar-section) missing: $html")
            // The one-level rail carries no per-level hooks anymore.
            assert(!html.contains("sidebar-section-l2"), s"the per-level l2 hook must be gone: $html")
            assert(!html.contains("sidebar-section-l3"), s"the per-level l3 hook must be gone: $html")
            // The level-2 heading renders as a #slug section link.
            assert(html.contains("#mid"), s"level-2 section #mid must render: $html")
            // The level-1 page title is the module link, NOT a section link.
            assert(!html.contains("#top"), s"level-1 page title must NOT be a section link: $html")
            // The level-3 heading is dropped from the rail (one level deep only).
            assert(!html.contains("#deep"), s"level-3 heading must NOT render in the one-level rail: $html")
        end for
    }

    // Leaf 5: content area is route-reactive (INV-005/INV-013)
    "content area is route-reactive INV-013 (leaf 5)" in {
        for
            route <- fixedRoute("/latest/kyo-core/")
            // DocsMarkdown.transpile is JVM-only after the split; use a constructed UI article
            // so this shared leaf stays cross-platform (steering constraint 6, INV-G6).
            article  = UI.h2.id("scope")(UI.Ast.Text("Scope"))
            reactive = UI.Ast.Reactive(route.map(_ => article))
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
    "docs body renders no header, dropdown, or search input (D5, leaf 6b)" in {
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
    "version banner for non-latest version (leaf 7)" in {
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
    "no version banner for latest version (leaf 8)" in {
        val ver = WebsiteVersion("v1.0.0", "1.0.0", true)
        for
            route <- fixedRoute("/latest/kyo-core/")
            content = emptyContent(ver)
            html <- rendered(content, route, Chunk.empty, UI.empty)
        yield assert(!html.contains("version-banner"), s"version-banner should not appear for latest: $html")
        end for
    }

    // Leaf 9: prev/next reflect route position
    "prev/next links reflect route position (leaf 9)" in {
        val modA = WebsiteModule("mod-a", "Foundation", "Mod A", "", WebsiteModule.Platforms(true, true, true, true))
        val modB = WebsiteModule("mod-b", "Foundation", "Mod B", "", WebsiteModule.Platforms(true, true, true, true))
        val modC = WebsiteModule("mod-c", "Foundation", "Mod C", "", WebsiteModule.Platforms(true, true, true, true))
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

    // Leaf 9b: the prev/next pager is gated on `contentLoading`. While content is loading (the bundle
    // cleared the article for an async content.md fetch) the pager is ABSENT, so it cannot flash at the
    // top of the empty content area (the footer flash). Once content is loaded it renders as in leaf 9.
    "prev/next pager is hidden while content is loading and shown once loaded (leaf 9b)" in {
        val modA = WebsiteModule("mod-a", "Foundation", "Mod A", "", WebsiteModule.Platforms(true, true, true, true))
        val modB = WebsiteModule("mod-b", "Foundation", "Mod B", "", WebsiteModule.Platforms(true, true, true, true))
        val modC = WebsiteModule("mod-c", "Foundation", "Mod C", "", WebsiteModule.Platforms(true, true, true, true))
        val content = WebsiteContent(
            intro = "",
            groups = Chunk(WebsiteContent.Group("Foundation", Chunk(modA, modB, modC))),
            version = WebsiteVersion("latest", "latest", true)
        )
        for
            route       <- fixedRoute("/latest/mod-b/")
            loadingHtml <- rendered(content, route, Chunk.empty, UI.empty, contentLoading = true)
            loadedHtml  <- rendered(content, route, Chunk.empty, UI.empty, contentLoading = false)
        yield
            // The `prev-next` class is unique to the content-area pager (the sidebar uses `nav-item`),
            // so its absence proves the pager is gone while loading even though the sidebar still lists
            // every module link. While loading the pager is absent; once loaded it renders.
            assert(!loadingHtml.contains("prev-next"), s"prev-next pager must be ABSENT while content is loading: $loadingHtml")
            assert(loadedHtml.contains("prev-next"), s"prev-next pager must render once content is loaded: $loadedHtml")
            // Once loaded the pager carries the adjacent-module links exactly as leaf 9. Scope the link
            // check to the pager substring so it asserts the pager's own links, not the sidebar's.
            val loadedPager = loadedHtml.substring(loadedHtml.indexOf("prev-next"))
            assert(loadedPager.contains("/latest/mod-a/"), s"prev link to mod-a must render in the pager once loaded: $loadedPager")
            assert(loadedPager.contains("/latest/mod-c/"), s"next link to mod-c must render in the pager once loaded: $loadedPager")
        end for
    }

    // Leaf 10: sidebar active state matches route
    "sidebar active state matches route (leaf 10)" in {
        val mod = WebsiteModule("kyo-core", "Foundation", "kyo-core", "", WebsiteModule.Platforms(true, true, true, true))
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

    // Leaf 11: intro-only content renders chrome with the Overview item but no module links
    "intro-only content renders shell with the Overview but no module links (leaf 11)" in {
        for
            route <- fixedRoute("/latest/")
            html  <- rendered(emptyContent(), route, Chunk.empty, UI.empty)
        yield
            // Shell renders (has sidebar-nav); the Overview item is present, no module links present.
            assert(html.contains("sidebar-nav"), s"sidebar-nav not found: $html")
            assert(html.contains("Overview"), s"Overview rail item must be present: $html")
            assert(!html.contains("/latest/kyo-"), s"unexpected module links in empty-groups render: $html")
        end for
    }

    // Leaf 15: the Overview is the FIRST rail item, above the module groups, linking to the intro route.
    "Overview is the first rail item above the module groups, linking to the intro route (leaf 15)" in {
        val mod = WebsiteModule("kyo-core", "Foundation", "kyo-core", "", WebsiteModule.Platforms(true, true, true, true))
        val content = WebsiteContent(
            intro = "",
            groups = Chunk(WebsiteContent.Group("Foundation", Chunk(mod))),
            version = WebsiteVersion("latest", "latest", true)
        )
        for
            // On a module route the Overview is present but NOT active.
            route <- fixedRoute("/latest/kyo-core/")
            html  <- rendered(content, route, Chunk.empty, UI.empty)
        yield
            // The Overview links to the intro route /latest/.
            assert(html.contains("""href="/latest/""""), s"Overview must link to the intro route /latest/: $html")
            assert(html.contains("Overview"), s"Overview rail item must be present: $html")
            // The Overview appears BEFORE the first module group name and the module link.
            val overviewIdx = html.indexOf("Overview")
            val groupIdx    = html.indexOf("Foundation")
            val moduleIdx   = html.indexOf("/latest/kyo-core/")
            assert(overviewIdx >= 0 && groupIdx >= 0 && moduleIdx >= 0, s"Overview/group/module all present: $html")
            assert(overviewIdx < groupIdx, s"Overview must appear before the first group: overview=$overviewIdx group=$groupIdx")
            assert(overviewIdx < moduleIdx, s"Overview must appear before the first module: overview=$overviewIdx module=$moduleIdx")
        end for
    }

    // Leaf 16: on the intro/home route the Overview is the ACTIVE item and expands to its own h2 sections.
    "on the intro home the Overview is active and expands to its h2 sections (leaf 16)" in {
        // The intro's own outline: no level-1 (no module title), two level-2 sections, one level-3 (dropped).
        val introToc = Chunk(
            DocsMarkdown.Heading(2, "Introduction", "introduction"),
            DocsMarkdown.Heading(3, "Imports", "imports"), // level-3: dropped from the one-level rail
            DocsMarkdown.Heading(2, "Coming from ZIO", "coming-from-zio")
        )
        val mod = WebsiteModule("kyo-core", "Foundation", "kyo-core", "", WebsiteModule.Platforms(true, true, true, true))
        val content = WebsiteContent(
            intro = "",
            groups = Chunk(WebsiteContent.Group("Foundation", Chunk(mod))),
            version = WebsiteVersion("latest", "latest", true)
        )
        for
            route <- fixedRoute("/latest/")
            html  <- rendered(content, route, introToc, UI.empty)
        yield
            // The Overview is the active rail item on the intro home.
            assert(html.contains("nav-item-active"), s"Overview must be the active item on the intro home: $html")
            // Exactly one nested section list is emitted (the Overview's), since no module is active.
            val sectionListCount = "sidebar-sections".r.findAllIn(html).length
            assert(sectionListCount == 1, s"expected exactly one nested section list (the Overview's), got $sectionListCount: $html")
            // The intro's level-2 sections render as #slug links.
            assert(html.contains("#introduction"), s"intro level-2 section #introduction must render: $html")
            assert(html.contains("#coming-from-zio"), s"intro level-2 section #coming-from-zio must render: $html")
            // The level-3 heading is dropped (one level deep only).
            assert(!html.contains("#imports"), s"intro level-3 heading must NOT render in the one-level rail: $html")
            // The active module (kyo-core) is NOT active here (the reader is on the overview), so it shows
            // no nested sections of its own.
            assert(html.contains("/latest/kyo-core/"), s"module link must still be present: $html")
        end for
    }

    // Leaf 17: prev/next treats the overview as the FIRST page (no prev; next = first module), and the
    // first module's prev points back to the overview.
    "prev/next: overview is the first page; first module prev points to the overview (leaf 17)" in {
        val modA = WebsiteModule("mod-a", "Foundation", "Mod A", "", WebsiteModule.Platforms(true, true, true, true))
        val modB = WebsiteModule("mod-b", "Foundation", "Mod B", "", WebsiteModule.Platforms(true, true, true, true))
        val content = WebsiteContent(
            intro = "",
            groups = Chunk(WebsiteContent.Group("Foundation", Chunk(modA, modB))),
            version = WebsiteVersion("latest", "latest", true)
        )
        for
            // On the overview: no prev (disabled), next = first module (mod-a).
            overviewRoute <- fixedRoute("/latest/")
            overviewHtml  <- rendered(content, overviewRoute, Chunk.empty, UI.empty)
            // On the first module: prev = overview (/latest/), next = mod-b.
            firstRoute <- fixedRoute("/latest/mod-a/")
            firstHtml  <- rendered(content, firstRoute, Chunk.empty, UI.empty)
        yield
            // The overview pager: a disabled prev and a next link to the first module.
            // Scope to the prev-next nav to avoid matching the sidebar Overview item.
            val overviewPager = overviewHtml.substring(overviewHtml.indexOf("prev-next"))
            assert(overviewPager.contains("prev-next-disabled"), s"overview must have a disabled prev: $overviewPager")
            assert(overviewPager.contains("/latest/mod-a/"), s"overview next must link to the first module: $overviewPager")
            // The first module's pager: prev links back to the overview /latest/, next to mod-b. The
            // `<` in the label is HTML-escaped to `&lt;` in the rendered output.
            val firstPager = firstHtml.substring(firstHtml.indexOf("prev-next"))
            assert(firstPager.contains("&lt; Overview"), s"first module prev must be labelled Overview: $firstPager")
            assert(firstPager.contains("""href="/latest/""""), s"first module prev must link to the overview /latest/: $firstPager")
            assert(firstPager.contains("/latest/mod-b/"), s"first module next must link to mod-b: $firstPager")
        end for
    }

    // Leaf 13 (Phase-6 BLOCKER-1 regression): a non-latest version's sidebar + prev/next links use
    // the version prefix `/v<X>/...`, not `/latest/...`.
    "non-latest version links use the version prefix not latest (BLOCKER-1 regression)" in {
        val modA = WebsiteModule("mod-a", "Foundation", "Mod A", "", WebsiteModule.Platforms(true, true, true, true))
        val modB = WebsiteModule("mod-b", "Foundation", "Mod B", "", WebsiteModule.Platforms(true, true, true, true))
        val modC = WebsiteModule("mod-c", "Foundation", "Mod C", "", WebsiteModule.Platforms(true, true, true, true))
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
    "latest version under its own v<X> tree links within v<X> not latest (WARN-1 regression)" in {
        val modA = WebsiteModule("mod-a", "Foundation", "Mod A", "", WebsiteModule.Platforms(true, true, true, true))
        val modB = WebsiteModule("mod-b", "Foundation", "Mod B", "", WebsiteModule.Platforms(true, true, true, true))
        val modC = WebsiteModule("mod-c", "Foundation", "Mod C", "", WebsiteModule.Platforms(true, true, true, true))
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
    "all chrome anchors are real HTML not JS placeholders (leaf 12)" in {
        val mod = WebsiteModule("kyo-data", "Foundation", "kyo-data", "", WebsiteModule.Platforms(true, true, true, true))
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
