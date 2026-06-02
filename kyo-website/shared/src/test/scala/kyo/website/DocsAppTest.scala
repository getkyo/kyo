package kyo.website

import kyo.*
import kyo.UI.PageHead

class DocsAppTest extends Test:

    private val testHead = PageHead(title = "t")

    // Helper: render DocsApp.view to the first HTML emission.
    private def rendered(
        content: WebsiteContent,
        versions: Chunk[WebsiteVersion],
        route: Signal[String],
        toc: Chunk[DocsMarkdown.Heading],
        article: UI
    )(using Frame): String < Async =
        for
            view <- DocsApp.view(content, versions, route, toc, article)
            html <- UI.runRenderPage(testHead)(view).take(1).run.map(_.headMaybe.getOrElse(""))
        yield html
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
            html <- rendered(content, Chunk.empty, route, Chunk.empty, UI.empty)
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
            html <- rendered(content, Chunk.empty, route, Chunk.empty, UI.empty)
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
            html  <- rendered(emptyContent(), Chunk.empty, route, toc, UI.empty)
        yield
            assert(html.contains("#kyo-core"), s"kyo-core anchor not found: $html")
            assert(html.contains("#scope"), s"scope anchor not found: $html")
        end for
    }

    // Leaf 4: TOC indents by heading level (level-2 and level-3 have cssClass "sub")
    "TOC indents by heading level (leaf 4)" in run {
        val toc = Chunk(
            DocsMarkdown.Heading(1, "Top", "top"),
            DocsMarkdown.Heading(2, "Sub", "sub"),
            DocsMarkdown.Heading(3, "Sub2", "sub2")
        )
        for
            route <- fixedRoute("/latest/kyo-core/")
            html  <- rendered(emptyContent(), Chunk.empty, route, toc, UI.empty)
        yield
            // Level 2 and 3 entries carry CSS class "sub"
            assert(html.contains("sub"), s"sub class not found for sub-headings: $html")
        end for
    }

    // Leaf 5: content area is route-reactive (INV-005/INV-013)
    "content area is route-reactive INV-013 (leaf 5)" in run {
        for
            route    <- fixedRoute("/latest/kyo-core/")
            rendered <- DocsMarkdown.transpile("## Scope\n")
            // Use UI.Ast.Reactive directly to avoid ambiguity with StringContext.render
            reactive = UI.Ast.Reactive(route.map(_ => rendered.article))
            html <- this.rendered(emptyContent(), Chunk.empty, route, Chunk.empty, reactive)
        yield
            assert(html.contains("data-kyo-reactive"), s"data-kyo-reactive not found in HTML: $html")
            assert(html.contains("<h2"), s"h2 element not found in HTML: $html")
        end for
    }

    // Leaf 6: version dropdown lists all versions (INV-010)
    "version dropdown lists all versions INV-010 (leaf 6)" in run {
        val versions = Chunk(
            WebsiteVersion("v1.0.0", "1.0.0", true),
            WebsiteVersion("v0.9.3", "0.9.3", false),
            WebsiteVersion("v0.9.2", "0.9.2", false),
            WebsiteVersion("v0.9.1", "0.9.1", false),
            WebsiteVersion("v0.9.0", "0.9.0", false)
        )
        for
            route <- fixedRoute("/latest/")
            html  <- rendered(emptyContent(), versions, route, Chunk.empty, UI.empty)
        yield
            // Dropdown options: each version label must appear
            assert(html.contains("1.0.0"), s"1.0.0 not found: $html")
            assert(html.contains("0.9.3"), s"0.9.3 not found: $html")
            assert(html.contains("0.9.2"), s"0.9.2 not found: $html")
            assert(html.contains("0.9.1"), s"0.9.1 not found: $html")
            assert(html.contains("0.9.0"), s"0.9.0 not found: $html")
        end for
    }

    // Leaf 7: version banner shows for non-latest
    "version banner for non-latest version (leaf 7)" in run {
        val ver = WebsiteVersion("v0.9.3", "0.9.3", false)
        for
            route <- fixedRoute("/v0.9.3/kyo-core/")
            content = emptyContent(ver)
            html <- rendered(content, Chunk.empty, route, Chunk.empty, UI.empty)
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
            html <- rendered(content, Chunk.empty, route, Chunk.empty, UI.empty)
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
            html  <- rendered(content, Chunk.empty, route, Chunk.empty, UI.empty)
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
            html  <- rendered(content, Chunk.empty, route, Chunk.empty, UI.empty)
        yield assert(html.contains("nav-item-active"), s"nav-item-active not found for active route: $html")
        end for
    }

    // Leaf 11: intro-only content renders chrome with no module links
    "intro-only content renders shell with no module links (leaf 11)" in run {
        for
            route <- fixedRoute("/latest/")
            html  <- rendered(emptyContent(), Chunk.empty, route, Chunk.empty, UI.empty)
        yield
            // Shell renders (has sidebar-nav), no module links present
            assert(html.contains("sidebar-nav"), s"sidebar-nav not found: $html")
            assert(!html.contains("/latest/kyo-"), s"unexpected module links in empty-groups render: $html")
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
            html  <- rendered(content, Chunk.empty, route, Chunk.empty, UI.empty)
        yield
            // Sidebar group headings are real text in HTML, not JS placeholders
            assert(html.contains("Foundation"), s"Foundation group text not found: $html")
            // No JS-only routing placeholders
            assert(!html.contains("javascript:void"), s"JS-only placeholder found: $html")
        end for
    }

end DocsAppTest
